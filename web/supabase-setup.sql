-- ============================================================================
--  SISTEMA DE LOCALIZACION DUAL - Esquema Supabase (plan gratuito)
--  Ejecutar COMPLETO en: Supabase Dashboard > SQL Editor > New query
--
--  Contenido: tablas devices/positions, vista publica latest_positions, RLS,
--  ingest_positions, pair_device, retencion (prune_positions) y, desde la
--  v1.1, CONTROL REMOTO: device_commands + enqueue/pull/ack, revocacion de
--  dispositivos (revoke_self / revoke_device) y vista device_health.-- v1.2, ESTADO Y CONTROL PARENTAL: device_checks + report_health (deteccion de
--  manipulacion y postura de seguridad del dispositivo: bloqueo, parche, root,
--  depuracion USB, Play Protect y apps con accesibilidad/notificaciones/admins),
--  geofences/geofence_events (zonas seguras con aviso de entrada/salida),
--  device_events + report_event (SOS y "llegue bien") y, desde la v1.3,
--  CONTROL PARENTAL: lista de apps instaladas SOLO por nombre (sin uso, sin
--  horarios y desactivable en el telefono). Ver README 6.
--  Es idempotente: reejecutarlo entero actualiza las funciones existentes.
-- ============================================================================

-- 1) Extensiones -------------------------------------------------------------
-- pgcrypto (digest() SHA-256 + gen_random_uuid()). En Supabase la extension vive
-- en el schema "extensions": las funciones de abajo incluyen ese schema en su
-- search_path para que digest() se resuelva SIEMPRE (evita el error
-- "function digest(text, unknown) does not exist" de ingest_positions).
create extension if not exists pgcrypto with schema extensions;

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

-- 2.1) Metadatos de cada persona/dispositivo (panel familiar).
-- color:           color del marcador en los mapas (hex, p. ej. '#f472b6')
-- show_on_public:  false = ese dispositivo NO aparece en el panel público
--                  (el historial del admin sigue viéndolo, es solo visibilidad)
alter table public.devices add column if not exists color text;
alter table public.devices add column if not exists show_on_public boolean not null default true;

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
    p.source, p.provider, p.recorded_at,
    d.color
  from public.positions p
  join public.devices d on d.id = p.device_id
  where d.show_on_public
  order by p.device_id, p.recorded_at desc;

grant select on public.latest_positions to anon, authenticated;

-- 3.1) Directorio publico: permite al panel familiar listar a TODO el mundo,
-- incluso cuando un movil lleva horas sin dar senal (que es justo cuando
-- importa saber que esta apagado, sin bateria o sin cobertura). Sin esta vista,
-- un dispositivo silencioso desaparecia del mapa como si no existiera.
-- Solo sale lo que ya decidiste publicar con show_on_public.
create or replace view public.public_devices as
  select d.id, d.label, d.color, d.last_seen
    from public.devices d
   where d.show_on_public
   order by d.label;

grant select on public.public_devices to anon, authenticated;

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
set search_path = public, extensions
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
set search_path = public, extensions
as $$
declare
  v_hash     text;
  v_elem     jsonb;
  v_count    integer := 0;
  v_last_lat double precision;
  v_last_lon double precision;
  v_last_at  timestamptz;
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

    -- Se guarda la ultima posicion del lote para evaluar geovallas una sola vez
    v_last_lat := (v_elem->>'lat')::double precision;
    v_last_lon := (v_elem->>'lon')::double precision;
    v_last_at  := (v_elem->>'recorded_at')::timestamptz;
  end loop;

  update public.devices set last_seen = now() where id = p_device_id;

  -- Control parental: avisos de entrada/salida de zonas seguras
  if v_count > 0 then
    perform public.geofences_eval(p_device_id, v_last_lat, v_last_lon, v_last_at);
  end if;

  return v_count;
end;
$$;

-- El agente (anon key) necesita ejecutar la ingesta; el admin, emparejar.
-- NOTA: las firmas deben coincidir EXACTAMENTE con las funciones de arriba.
grant execute on function public.pair_device(text, text)             to authenticated;
grant execute on function public.ingest_positions(uuid, text, jsonb) to anon, authenticated;

-- 7) Retencion: 7 dias (mantiene plano el uso del free tier) -----------------
-- Tambien limpia el historial de comandos ya resueltos (30 dias).
create or replace function public.prune_positions()
returns void
language plpgsql
security definer
set search_path = public, extensions
as $$
begin
  delete from public.positions where recorded_at < now() - interval '7 days';
  delete from public.device_commands
   where created_at < now() - interval '30 days'
     and status <> 'pending';
  -- Avisos (geovallas, SOS, llegue bien): 90 dias de historial
  delete from public.geofence_events where at < now() - interval '90 days';
  delete from public.device_events   where at < now() - interval '90 days';
end;
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

-- 8) CONTROL REMOTO: comandos del panel admin hacia el agente --------------
-- El panel encola una accion; el agente la recoge en su proximo sondeo (pull,
-- cada 60 s) y reporta el resultado. La lista de comandos es CERRADA y el
-- agente ignora cualquier otro valor: no hay canal para ejecutar codigo
-- arbitrario, instalar APKs, leer mensajes ni borrar el telefono.
create table if not exists public.device_commands (
  id           bigint generated always as identity primary key,
  device_id    uuid not null references public.devices(id) on delete cascade,
  command      text not null,
  args         jsonb not null default '{}'::jsonb,
  status       text not null default 'pending'
               check (status in ('pending','delivered','done','failed','expired')),
  result       text,
  created_at   timestamptz not null default now(),
  delivered_at timestamptz,
  finished_at  timestamptz
);

create index if not exists device_commands_queue_idx
  on public.device_commands (device_id, status, created_at);

alter table public.device_commands enable row level security;

-- El panel (authenticated) ve la cola para mostrar el resultado real.
drop policy if exists device_commands_admin_read on public.device_commands;
create policy device_commands_admin_read on public.device_commands
  for select to authenticated using (true);

-- 8.1) Verificacion del token del agente, reutilizable por las RPC de abajo.
-- No se concede EXECUTE a nadie: solo la llaman funciones del mismo duenno
-- (si fuera publica seria un oraculo para adivinar tokens).
create or replace function public.agent_token_ok(p_device_id uuid, p_token text)
returns boolean
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  v_hash text;
begin
  select secret_hash into v_hash from public.devices where id = p_device_id;
  if v_hash is null then
    return false;
  end if;
  return encode(digest(coalesce(p_token, ''), 'sha256'), 'hex') = v_hash;
end;
$$;

revoke all on function public.agent_token_ok(uuid, text) from public, anon, authenticated;

-- 8.2) Encolar un comando (solo con sesion de administrador).
create or replace function public.enqueue_command(
  p_device_id uuid,
  p_command   text,
  p_args      jsonb default '{}'::jsonb
)
returns bigint
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  v_id bigint;
begin
  if coalesce(auth.role(), '') <> 'authenticated' then
    raise exception 'Requiere sesion de administrador';
  end if;
  if p_command not in ('locate_now','flush','lock','alarm','stop_alarm','stop_tracking',
                       'burst','stop_burst') then
    raise exception 'Comando no permitido: %', p_command;
  end if;
  -- Anti-repeticion: no apilar el mismo comando si ya hay uno en curso.
  if exists (select 1 from public.device_commands
              where device_id = p_device_id
                and command = p_command
                and status in ('pending','delivered')
                and created_at > now() - interval '2 minutes') then
    raise exception 'Ya hay un "%" en curso para este dispositivo', p_command;
  end if;

  insert into public.device_commands (device_id, command, args)
  values (p_device_id, p_command, coalesce(p_args, '{}'::jsonb))
  returning id into v_id;
  return v_id;
end;
$$;

-- 8.3) Pull del agente: devuelve sus comandos pendientes y los marca como
-- entregados. Los que lleven mas de 10 minutos sin recogerse caducan, para que
-- un telefono apagado no ejecute ordenes viejas al volver.
create or replace function public.pull_commands(p_device_id uuid, p_token text)
returns jsonb
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  v_out jsonb;
begin
  if not public.agent_token_ok(p_device_id, p_token) then
    perform pg_sleep(0.4);
    raise exception 'Credenciales invalidas';
  end if;

  update public.device_commands
     set status = 'expired'
   where device_id = p_device_id
     and status in ('pending','delivered')
     and created_at < now() - interval '10 minutes';

  with picked as (
    select id from public.device_commands
     where device_id = p_device_id and status = 'pending'
     order by created_at
     limit 5
     for update skip locked
  ), taken as (
    update public.device_commands c
       set status = 'delivered', delivered_at = now()
      from picked
     where c.id = picked.id
    returning c.id, c.command, c.args
  )
  select coalesce(
           jsonb_agg(jsonb_build_object('id', id, 'command', command, 'args', args) order by id),
           '[]'::jsonb)
    into v_out
    from taken;

  update public.devices set last_seen = now() where id = p_device_id;
  return v_out;
end;
$$;

-- 8.4) Ack del agente: que hizo el comando, o por que fallo.
create or replace function public.ack_command(
  p_device_id  uuid,
  p_token      text,
  p_command_id bigint,
  p_status     text,
  p_result     text default null
)
returns void
language plpgsql
security definer
set search_path = public, extensions
as $$
begin
  if not public.agent_token_ok(p_device_id, p_token) then
    perform pg_sleep(0.4);
    raise exception 'Credenciales invalidas';
  end if;
  if p_status not in ('done','failed') then
    raise exception 'Estado no valido: %', p_status;
  end if;

  update public.device_commands
     set status = p_status,
         result = left(coalesce(p_result, ''), 300),
         finished_at = now()
   where id = p_command_id
     and device_id = p_device_id
     and status = 'delivered';
end;
$$;

-- 8.5) Desvincular este dispositivo desde la propia app: el servidor rota el
-- token a un valor aleatorio que nadie conoce, asi que el token guardado en el
-- telefono deja de servir para enviar. Con p_purge = true borra su historial.
create or replace function public.revoke_self(
  p_device_id uuid,
  p_token     text,
  p_purge     boolean default false
)
returns integer
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  v_deleted integer := 0;
begin
  if not public.agent_token_ok(p_device_id, p_token) then
    perform pg_sleep(0.4);
    raise exception 'Credenciales invalidas';
  end if;

  if p_purge then
    delete from public.positions where device_id = p_device_id;
    get diagnostics v_deleted = row_count;
  end if;

  update public.devices
     set secret_hash = encode(
           digest(gen_random_uuid()::text || clock_timestamp()::text, 'sha256'), 'hex')
   where id = p_device_id;

  update public.device_commands
     set status = 'expired'
   where device_id = p_device_id and status in ('pending','delivered');

  return v_deleted;
end;
$$;

-- 8.6) Revocar desde el panel (telefono perdido o robado): mismo efecto que
-- 8.5 pero disparado por el admin autenticado. Para volver a rastrear hay que
-- emparejar de nuevo (pair_device) y reconfigurar el telefono.
create or replace function public.revoke_device(
  p_device_id uuid,
  p_purge     boolean default false
)
returns integer
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  v_deleted integer := 0;
begin
  if coalesce(auth.role(), '') <> 'authenticated' then
    raise exception 'Requiere sesion de administrador';
  end if;

  if p_purge then
    delete from public.positions where device_id = p_device_id;
    get diagnostics v_deleted = row_count;
  end if;

  update public.devices
     set secret_hash = encode(
           digest(gen_random_uuid()::text || clock_timestamp()::text, 'sha256'), 'hex')
   where id = p_device_id;

  update public.device_commands
     set status = 'expired'
   where device_id = p_device_id and status in ('pending','delivered');

  return v_deleted;
end;
$$;

-- 8.7) Salud de cada dispositivo para la columna "Dispositivos" del panel.
create or replace view public.device_health as
  select          d.id,
         d.label,
         d.created_at,
         d.last_seen,
         (select count(*) from public.positions p where p.device_id = d.id) as positions_count,
         (select count(*) from public.device_commands c
           where c.device_id = d.id and c.status = 'pending') as pending_commands,
         (select c.command from public.device_commands c
           where c.device_id = d.id
           order by c.created_at desc
           limit 1) as last_command,
         -- Columnas nuevas SIEMPRE al final: "create or replace view" solo puede
         -- anadir columnas al final, nunca reordenar las existentes.
         d.color,
         d.show_on_public
    from public.devices d;

revoke all on public.device_health from anon;
grant select on public.device_health to authenticated;

-- 9) PERMISOS de las funciones nuevas + endurecimiento -----------------------
grant execute on function public.pull_commands(uuid, text)                   to anon, authenticated;
grant execute on function public.ack_command(uuid, text, bigint, text, text) to anon, authenticated;
grant execute on function public.revoke_self(uuid, text, boolean)            to anon, authenticated;
grant execute on function public.enqueue_command(uuid, text, jsonb)          to authenticated;
grant execute on function public.revoke_device(uuid, boolean)                to authenticated;

-- FIX de seguridad: en Postgres toda funcion nace con EXECUTE para PUBLIC y
-- Supabase expone como RPC cualquiera ejecutable por `anon`. Con la anon key
-- (que es publica por diseno) cualquiera podia llamar a pair_device() y
-- sobrescribir el token de un dispositivo conocido, o a prune_positions() y
-- forzar el borrado de la retencion. Se corta esa via.
revoke execute on function public.pair_device(text, text) from public, anon;
revoke execute on function public.prune_positions()       from public, anon;
grant  execute on function public.prune_positions()       to authenticated;

-- 10) ESTADO Y MANIPULACION: una fila por dispositivo ----------------------
-- El agente sube como esta (permisos, antirrobo, notificaciones, servicio,
-- bateria, y la lista de problemas). El panel ve de un vistazo si alguien lo ha
-- desactivado, con hora y motivo.
--
-- Ojo con la promesa: esto NO impide que apaguen el rastreo ni lo resucita. Es
-- la respuesta honesta a "¿y si me lo desactivan?": no se puede impedir que
-- alguien con el telefono desbloqueado toque lo que quiera, pero SI se puede
-- lograr que no lo haga sin que te enteres.
create table if not exists public.device_checks (
  device_id        uuid primary key references public.devices(id) on delete cascade,
  checked_at       timestamptz not null default now(),
  app_alive        boolean,
  tracking_on      boolean,
  location_ok      boolean,
  background_ok    boolean,
  notifications_ok boolean,
  admin_active     boolean,
  anti_theft_on    boolean,
  admin_removed    boolean,
  battery_pct      integer,
  charging         boolean,
  -- Postura del dispositivo: como esta configurado y que acceso peligroso tiene.
  developer_options      boolean,
  usb_debugging          boolean,
  play_protect           boolean,
  device_secure          boolean,
  rooted                 boolean,
  install_source         text,
  android_version        text,
  security_patch         text,
  accessibility_apps     jsonb not null default '[]'::jsonb,
  notification_listeners jsonb not null default '[]'::jsonb,
  device_admins          jsonb not null default '[]'::jsonb,
  -- Control parental: SOLO nombres de apps (para saber si hay que hablar con
  -- el menor), y solo si el telefono lo comparte (app_list_shared).
  -- NUNCA uso, horarios ni patrones temporales (ver README 6).
  app_list_shared  boolean not null default false,
  installed_count  integer,
  installed_apps   jsonb not null default '[]'::jsonb,
  alerts           jsonb not null default '[]'::jsonb
);

-- Instalaciones que ya tenian device_checks (v1.2): se anaden las columnas.
alter table public.device_checks add column if not exists developer_options      boolean;
alter table public.device_checks add column if not exists usb_debugging          boolean;
alter table public.device_checks add column if not exists play_protect           boolean;
alter table public.device_checks add column if not exists device_secure          boolean;
alter table public.device_checks add column if not exists rooted                 boolean;
alter table public.device_checks add column if not exists install_source         text;
alter table public.device_checks add column if not exists android_version        text;
alter table public.device_checks add column if not exists security_patch         text;
alter table public.device_checks add column if not exists accessibility_apps     jsonb not null default '[]'::jsonb;
alter table public.device_checks add column if not exists notification_listeners jsonb not null default '[]'::jsonb;
alter table public.device_checks add column if not exists device_admins          jsonb not null default '[]'::jsonb;
alter table public.device_checks add column if not exists app_list_shared       boolean not null default false;
alter table public.device_checks add column if not exists installed_count       integer;
alter table public.device_checks add column if not exists installed_apps        jsonb not null default '[]'::jsonb;

alter table public.device_checks enable row level security;

-- El admin autenticado lo lee; el agente solo escribe por RPC con su token.
drop policy if exists device_checks_admin_read on public.device_checks;
create policy device_checks_admin_read on public.device_checks
  for select to authenticated using (true);

-- 10.1) Subida del estado (agente con token valido). Upsert: una fila por
-- dispositivo, siempre la ultima foto.
create or replace function public.report_health(
  p_device_id uuid,
  p_token     text,
  p_report    jsonb
)
returns void
language plpgsql
security definer
set search_path = public, extensions
as $$
begin
  if not public.agent_token_ok(p_device_id, p_token) then
    perform pg_sleep(0.4);
    raise exception 'Credenciales invalidas';
  end if;

  insert into public.device_checks (
    device_id, checked_at, app_alive, tracking_on, location_ok, background_ok,
    notifications_ok, admin_active, anti_theft_on, admin_removed,
    battery_pct, charging, alerts,
    developer_options, usb_debugging, play_protect, device_secure, rooted,
    install_source, android_version, security_patch,
    accessibility_apps, notification_listeners, device_admins,
    app_list_shared, installed_count, installed_apps)
  values (
    p_device_id, now(),
    (p_report->>'app_alive')::boolean,
    (p_report->>'tracking_on')::boolean,
    (p_report->>'location_ok')::boolean,
    (p_report->>'background_ok')::boolean,
    (p_report->>'notifications_ok')::boolean,
    (p_report->>'admin_active')::boolean,
    (p_report->>'anti_theft_on')::boolean,
    (p_report->>'admin_removed')::boolean,
    nullif(p_report->>'battery_pct', '')::integer,
    (p_report->>'charging')::boolean,
    coalesce(p_report->'alerts', '[]'::jsonb),
    (p_report->>'developer_options')::boolean,
    (p_report->>'usb_debugging')::boolean,
    (p_report->>'play_protect')::boolean,
    (p_report->>'device_secure')::boolean,
    (p_report->>'rooted')::boolean,
    nullif(left(coalesce(p_report->>'install_source', ''), 80), ''),
    nullif(left(coalesce(p_report->>'android_version', ''), 40), ''),
    nullif(left(coalesce(p_report->>'security_patch', ''), 40), ''),
    coalesce(p_report->'accessibility_apps', '[]'::jsonb),
    coalesce(p_report->'notification_listeners', '[]'::jsonb),
    coalesce(p_report->'device_admins', '[]'::jsonb),
    coalesce((p_report->>'app_list_shared')::boolean, false),
    nullif(p_report->>'installed_count', '')::integer,
    coalesce(p_report->'installed_apps', '[]'::jsonb))
  on conflict (device_id) do update set
    checked_at             = excluded.checked_at,
    app_alive              = excluded.app_alive,
    tracking_on            = excluded.tracking_on,
    location_ok            = excluded.location_ok,
    background_ok          = excluded.background_ok,
    notifications_ok       = excluded.notifications_ok,
    admin_active           = excluded.admin_active,
    anti_theft_on          = excluded.anti_theft_on,
    admin_removed          = excluded.admin_removed,
    battery_pct            = excluded.battery_pct,
    charging               = excluded.charging,
    alerts                 = excluded.alerts,
    developer_options      = excluded.developer_options,
    usb_debugging          = excluded.usb_debugging,
    play_protect           = excluded.play_protect,
    device_secure          = excluded.device_secure,
    rooted                 = excluded.rooted,
    install_source         = excluded.install_source,
    android_version        = excluded.android_version,
    security_patch         = excluded.security_patch,
    accessibility_apps     = excluded.accessibility_apps,
    notification_listeners = excluded.notification_listeners,
    device_admins          = excluded.device_admins,
    app_list_shared        = excluded.app_list_shared,
    installed_count        = excluded.installed_count,
    installed_apps         = excluded.installed_apps;

  update public.devices set last_seen = now() where id = p_device_id;
end;
$$;

grant execute on function public.report_health(uuid, text, jsonb) to anon, authenticated;

-- 11) CONTROL PARENTAL: zonas seguras (geovallas) y avisos -------------------
-- La zona se define por persona (device_id) con un radio. La evaluacion ocurre
-- en el SERVIDOR dentro de ingest_positions: cero bateria extra en el telefono,
-- funciona igual con el simulador y no depende de que la app este abierta.
--
-- Nota honesta: una geovalla avisa de entradas y salidas, no vigila lo que
-- alguien hace dentro. Eso es supervision; lo otro seria otra cosa.
create table if not exists public.geofences (
  id            uuid primary key default gen_random_uuid(),
  device_id     uuid not null references public.devices(id) on delete cascade,
  label         text not null,
  lat           double precision not null check (lat between  -90 and  90),
  lon           double precision not null check (lon between -180 and 180),
  radius_m      integer not null default 200 check (radius_m between 50 and 20000),
  notify_on     text not null default 'both' check (notify_on in ('enter','exit','both')),
  inside        boolean,          -- ultimo estado conocido (null = aun sin dato)
  last_event_at timestamptz,
  created_at    timestamptz not null default now()
);

create index if not exists geofences_device_idx on public.geofences (device_id);

create table if not exists public.geofence_events (
  id          bigint generated always as identity primary key,
  device_id   uuid not null references public.devices(id) on delete cascade,
  geofence_id uuid references public.geofences(id) on delete set null,
  label       text,
  kind        text not null check (kind in ('enter','exit')),
  lat         double precision,
  lon         double precision,
  at          timestamptz not null default now()
);

create index if not exists geofence_events_idx
  on public.geofence_events (device_id, at desc);

-- 11.1) Evaluacion de geovallas: se llama en cada ingesta con la ultima
-- posicion del lote. El primer dato solo fija el estado (no dispara un
-- "entro" falso cuando acabas de crear la zona).
create or replace function public.geofences_eval(
  p_device_id uuid,
  p_lat       double precision,
  p_lon       double precision,
  p_at        timestamptz
)
returns void
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  g        record;
  v_dist   double precision;
  v_inside boolean;
  v_kind   text;
begin
  if p_lat is null or p_lon is null then
    return;
  end if;

  for g in select * from public.geofences where device_id = p_device_id loop
    -- Distancia haversine en metros
    v_dist := 6371000 * 2 * asin(sqrt(
      power(sin(radians(p_lat - g.lat) / 2), 2) +
      cos(radians(g.lat)) * cos(radians(p_lat)) *
      power(sin(radians(p_lon - g.lon) / 2), 2)));

    v_inside := v_dist <= g.radius_m;

    if g.inside is null then
      update public.geofences set inside = v_inside where id = g.id;
      continue;
    end if;

    if v_inside = g.inside then
      continue;
    end if;

    v_kind := case when v_inside then 'enter' else 'exit' end;

    if g.notify_on = 'both' or g.notify_on = v_kind then
      insert into public.geofence_events (device_id, geofence_id, label, kind, lat, lon, at)
      values (p_device_id, g.id, g.label, v_kind, p_lat, p_lon, coalesce(p_at, now()));
    end if;

    update public.geofences
       set inside = v_inside,
           last_event_at = coalesce(p_at, now())
     where id = g.id;
  end loop;
end;
$$;

-- No se expone: solo la llaman ingest_positions (mismo duenno).
revoke all on function public.geofences_eval(uuid, double precision, double precision, timestamptz)
  from public, anon, authenticated;

-- 11.2) Avisos que manda el propio telefono: SOS y "llegue bien".
create table if not exists public.device_events (
  id        bigint generated always as identity primary key,
  device_id uuid not null references public.devices(id) on delete cascade,
  kind      text not null check (kind in ('sos','checkin')),
  note      text,
  lat       double precision,
  lon       double precision,
  at        timestamptz not null default now()
);

create index if not exists device_events_idx on public.device_events (device_id, at desc);

create or replace function public.report_event(
  p_device_id uuid,
  p_token     text,
  p_kind      text,
  p_note      text default null,
  p_lat       double precision default null,
  p_lon       double precision default null
)
returns bigint
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  v_id bigint;
begin
  if not public.agent_token_ok(p_device_id, p_token) then
    perform pg_sleep(0.4);
    raise exception 'Credenciales invalidas';
  end if;
  if p_kind not in ('sos','checkin') then
    raise exception 'Tipo de aviso no permitido: %', p_kind;
  end if;

  insert into public.device_events (device_id, kind, note, lat, lon)
  values (p_device_id, p_kind, left(coalesce(p_note, ''), 200), p_lat, p_lon)
  returning id into v_id;

  update public.devices set last_seen = now() where id = p_device_id;
  return v_id;
end;
$$;

-- 11.3) RLS: el admin autenticado gestiona zonas y lee avisos; el agente solo
-- escribe por RPC con su token.
alter table public.geofences       enable row level security;
alter table public.geofence_events enable row level security;
alter table public.device_events   enable row level security;

drop policy if exists geofences_admin_all on public.geofences;
create policy geofences_admin_all on public.geofences
  for all to authenticated using (true) with check (true);

drop policy if exists geofence_events_admin_read on public.geofence_events;
create policy geofence_events_admin_read on public.geofence_events
  for select to authenticated using (true);

drop policy if exists device_events_admin_read on public.device_events;
create policy device_events_admin_read on public.device_events
  for select to authenticated using (true);

grant execute on function public.report_event(uuid, text, text, text, double precision, double precision)
  to anon, authenticated;

-- ============================================================================
--  EMPAREJAR TU DISPOSITIVO (paso final):
--  1. Ejecuta este archivo COMPLETO con Run (reemplaza las funciones antiguas).
--  2. Cambia el token de ejemplo por uno largo y aleatorio (>=16 chars),
--     ejecuta la linea de abajo y COPIA el uuid devuelto.
--  3. Pega uuid + token en la app Android (Ajustes > Emparejamiento).
--
--  select public.pair_device('Mi Telefono', 'CAMBIA-ESTE-TOKEN-LARGO-123456');
-- ============================================================================
