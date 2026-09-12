-- ============================================================================
--  SISTEMA DE LOCALIZACION DUAL - Esquema Supabase (plan gratuito)
--  Ejecutar COMPLETO una sola vez en: Supabase Dashboard > SQL Editor > New query
-- ============================================================================

-- 1) Extensiones -------------------------------------------------------------
create extension if not exists pgcrypto; -- digest() (HMAC/SHA-256) + gen_random_uuid()

-- 2) Tablas ------------------------------------------------------------------
create table if not exists public.devices (
  id          uuid primary key default gen_random_uuid(),
  label       text not null unique,
  secret_hash text not null,                 -- sha256 hex del token del agente (nunca el token)
  created_at  timestamptz not null default now(),
  last_seen   timestamptz
);

create table if not exists public.positions (
  id          bigint generated always as identity primary key,
  device_id   uuid not null references public.devices(id) on delete cascade,
  lat         double precision not null check (lat between  -90 and  90),
  lon         double precision not null check (lon between -180 and 180),
  accuracy    double precision,
  speed       double precision,
  altitude    double precision,
  bearing     double precision,
  battery_pct integer,
  charging    boolean,
  source      text,                          -- FUSED | GPS | NETWORK
  provider    text,                          -- fused | gps | network
  cell_wifi   jsonb,                         -- metadatos Precision+ (celdas / APs WiFi)
  recorded_at timestamptz not null,
  created_at  timestamptz not null default now()
);

create index if not exists positions_device_time_idx
  on public.positions (device_id, recorded_at desc);
create index if not exists positions_recorded_idx
  on public.positions (recorded_at desc);

-- 3) Vista publica (anon solo ve la ULTIMA posicion por dispositivo) ---------
create or replace view public.latest_positions as
  select distinct on (p.device_id)
    p.device_id,
    d.label,
    p.lat, p.lon, p.accuracy, p.speed, p.battery_pct, p.charging,
    p.source, p.provider, p.recorded_at
  from public.positions p
  join public.devices d on d.id = p.device_id
  order by p.device_id, p.recorded_at desc;

grant select on public.latest_positions to anon, authenticated;

-- 4) RLS ---------------------------------------------------------------------
alter table public.devices   enable row level security;
alter table public.positions enable row level security;

-- El anon NO tiene ninguna politica sobre las tablas => no puede leer nada.
-- El admin autenticado (Supabase Auth) lee y gestiona:
drop policy if exists devices_admin_read    on public.devices;
drop policy if exists devices_admin_update  on public.devices;
drop policy if exists positions_admin_read  on public.positions;
create policy devices_admin_read    on public.devices   for select to authenticated using (true);
create policy devices_admin_update  on public.devices   for update to authenticated using (true) with check (true);
create policy positions_admin_read  on public.positions for select to authenticated using (true);

-- 5) RPC de emparejamiento (se llama SOLO desde el SQL Editor) ---------------
create or replace function public.pair_device(p_label text, p_token text)
returns uuid
language plpgsql
security definer
set search_path = public
as $$
declare
  v_id uuid;
begin
  if p_token is null or length(p_token) < 16 then
    raise exception 'El token debe tener al menos 16 caracteres';
  end if;
  insert into public.devices (label, secret_hash)
  values (p_label, encode(digest(p_token, 'sha256'), 'hex'))
  on conflict (label) do update set secret_hash = excluded.secret_hash
  returning id into v_id;
  return v_id;
end;
$$;

-- 6) RPC de ingesta: el anon SOLO puede insertar con device_id+token validos -
create or replace function public.ingest_positions(
  p_device_id uuid,
  p_token     text,
  p_positions jsonb      -- array de objetos {lat, lon, accuracy, ..., recorded_at}
)
returns integer
language plpgsql
security definer
set search_path = public
as $$
declare
  v_hash  text;
  v_elem  jsonb;
  v_count integer := 0;
begin
  select secret_hash into v_hash from public.devices where id = p_device_id;
  if v_hash is null then
    perform pg_sleep(0.4);
    raise exception 'Credenciales invalidas';
  end if;
  if encode(digest(coalesce(p_token, ''), 'sha256'), 'hex') <> v_hash then
    perform pg_sleep(0.4);
    raise exception 'Credenciales invalidas';
  end if;

  if jsonb_typeof(p_positions) <> 'array' then
    raise exception 'p_positions debe ser un array JSON';
  end if;

  for v_elem in select * from jsonb_array_elements(p_positions) loop
    insert into public.positions
      (device_id, lat, lon, accuracy, speed, altitude, bearing,
       battery_pct, charging, source, provider, cell_wifi, recorded_at)
    values
      (p_device_id,
       (v_elem->>'lat')::double precision,
       (v_elem->>'lon')::double precision,
       nullif(v_elem->>'accuracy','')::double precision,
       nullif(v_elem->>'speed','')::double precision,
       nullif(v_elem->>'altitude','')::double precision,
       nullif(v_elem->>'bearing','')::double precision,
       nullif(v_elem->>'battery_pct','')::integer,
       (v_elem->>'charging')::boolean,
       v_elem->>'source',
       v_elem->>'provider',
       v_elem->'cell',
       (v_elem->>'recorded_at')::timestamptz);
    v_count := v_count + 1;
  end loop;

  update public.devices set last_seen = now() where id = p_device_id;
  return v_count;
end;
$$;

-- El agente (anon key) necesita ejecutar la ingesta; el admin, emparejar.
-- NOTA: las firmas deben coincidir EXACTAMENTE con las funciones de arriba.
grant execute on function public.pair_device(text, text)             to authenticated;
grant execute on function public.ingest_positions(uuid, text, jsonb) to anon, authenticated;

-- 7) Retencion: 7 dias (mantiene plano el uso del free tier) -----------------
create or replace function public.prune_positions()
returns void
language sql
security definer
set search_path = public
as $$
  delete from public.positions where recorded_at < now() - interval '7 days';
$$;

-- pg_cron (si esta disponible en tu proyecto):
do $$
begin
  if exists (select 1 from pg_available_extensions where name = 'pg_cron') then
    perform cron.schedule('prune-positions', '0 3 * * *', 'select public.prune_positions();');
  end if;
exception when others then
  raise notice 'pg_cron no disponible: programa prune_positions() manualmente';
end $$;

-- ============================================================================
--  EMPAREJAR TU DISPOSITIVO (paso final):
--  1. Cambia el token de ejemplo por uno largo y aleatorio (>=16 chars).
--  2. Ejecuta y COPIA el uuid devuelto.
--  3. Pega uuid + token en la app Android (Ajustes > Emparejamiento).
--
--  select public.pair_device('Mi Telefono', 'CAMBIA-ESTE-TOKEN-LARGO-123456');
-- ============================================================================
