# API del backend

Contrato de Supabase tal como lo usan el agente y el panel. Todo vive en `web/supabase-setup.sql`
(835 líneas, **idempotente**: reejecutarlo entero es la vía de actualización). El inventario está en
[`MAPA-RECURSOS.md`](MAPA-RECURSOS.md).

## 0. Cómo se llama

- **RPC**: `POST {url}/rest/v1/rpc/<funcion>` con la clave `anon` y el cuerpo JSON con los argumentos
  con prefijo `p_`. Es lo que hace el agente (`SupabaseClient.kt`).
- **Tablas y vistas** (solo el panel): `GET /rest/v1/<tabla>?select=...` con la sesión del admin. La
  seguridad la impone RLS, no el cliente.
- Convención de nombres: parámetros `p_*`, tablas en plural, `jsonb` para lo que puede crecer.

## 1. Tablas

### `devices`
| Columna | Tipo | Notas |
|---|---|---|
| `id` | `uuid` | PK, `gen_random_uuid()` |
| `label` | `text` | Único; es la etiqueta que se ve en el panel |
| `secret_hash` | `text` | SHA-256 del token del agente. **El token en claro no se guarda nunca** |
| `created_at`, `last_seen` | `timestamptz` | `last_seen` se actualiza en cada ingesta y pull |
| `color` | `text` | Color del marcador en los paneles |
| `show_on_public` | `boolean` | `true` por defecto; `false` = oculto del panel familiar |

### `positions`
`id` (`bigint` identidad) · `device_id` (FK, borrado en cascada) · `lat`, `lon`, `accuracy`, `speed`,
`altitude`, `bearing` (`double precision`) · `battery_pct` (`integer`) · `charging` (`boolean`) ·
`source` (`FUSED`/`GPS`/`NETWORK`) · `provider` (`fused`/`gps`/`network`) · `cell_wifi` (`jsonb`,
Precision+) · `recorded_at`, `created_at`.

### `device_commands`
`id` · `device_id` (FK) · `command` (`text`) · `args` (`jsonb`, `{}` por defecto) · `status`
(`pending`/`delivered`/`done`/`failed`/`expired`) · `result` · `created_at` · `delivered_at` ·
`finished_at`.

### `device_checks` (una fila por dispositivo)
Estado: `app_alive`, `tracking_on`, `location_ok`, `background_ok`, `notifications_ok`, `admin_active`,
`anti_theft_on`, `admin_removed`, `battery_pct`, `charging`, `alerts` (`jsonb`).

Postura: `developer_options`, `usb_debugging`, `play_protect`, `device_secure`, `rooted`,
`install_source`, `android_version`, `security_patch`, `accessibility_apps`,
`notification_listeners`, `device_admins` (`jsonb`).

Control parental: `app_list_shared` (`boolean`), `installed_count` (`integer`), `installed_apps`
(`jsonb`).

> Las tres últimas columnas y las once de postura se añadieron con `alter table ... add column if not
> exists`, así que reejecutar el archivo actualiza una instalación existente sin perder datos.

### `geofences`
`id` · `device_id` · `label` · `lat` · `lon` · `radius_m` (`integer`, **50–20 000**, por defecto 200) ·
`notify_on` (`enter`/`exit`/`both`) · `inside` (`boolean`, `null` = sin dato) · `last_event_at` ·
`created_at`.

### `geofence_events`
`id` · `device_id` · `geofence_id` (FK `on delete set null`) · `label` · `kind` (`enter`/`exit`) ·
`lat`, `lon` · `at`.

### `device_events`
`id` · `device_id` · `kind` (`sos`/`checkin`) · `note` · `lat`, `lon` · `at`.

## 2. Vistas

| Vista | Columnas | Acceso |
|---|---|---|
| `latest_positions` | `device_id`, `label`, `lat`, `lon`, `accuracy`, `speed`, `battery_pct`, `charging`, `source`, `provider`, `recorded_at`, `color` | `anon` y `authenticated`; solo dispositivos con `show_on_public` |
| `public_devices` | `id`, `label`, `color`, `last_seen` | `anon` y `authenticated`; todos los visibles, **con o sin posición** |
| `device_health` | `id`, `label`, `created_at`, `last_seen`, `positions_count`, `pending_commands`, `last_command`, `color`, `show_on_public` | Solo `authenticated` |

`device_health` se amplía **añadiendo columnas al final**: `create or replace view` no permite reordenar
las existentes, y el panel tolera que falten (si la vista no existe, sigue con la tabla `devices`).

## 3. Funciones

### 3.1 Emparejamiento

```sql
public.pair_device(p_label text, p_token text) returns uuid
```
- **Quién**: solo desde el SQL Editor. `EXECUTE` revocado a `public, anon` y concedido a `authenticated`.
- **Valida**: token de 16 caracteres mínimo. Guarda `sha256(token)`.
- **Devuelve**: el `uuid`. Si la etiqueta ya existe, **rota** el token (`on conflict (label) do update`).

### 3.2 Ingesta

```sql
public.ingest_positions(p_device_id uuid, p_token text, p_positions jsonb) returns integer
```
Objeto de cada posición (lo que manda el agente):

```json
{ "lat": 40.4168, "lon": -3.7038, "accuracy": 8.5, "speed": 1.2, "altitude": 650.0,
  "bearing": 180.0, "battery_pct": 72, "charging": false,
  "source": "FUSED", "provider": "fused", "cell": { "…": "Precision+" },
  "recorded_at": "2026-09-12T10:00:00.000Z" }
```

- **Quién**: `anon` + token válido del dispositivo.
- **Valida**: token contra el hash (con `pg_sleep(0.4)` si falla) y que `p_positions` sea un array.
- **Efectos**: inserta cada punto, actualiza `devices.last_seen` y llama a `geofences_eval` **una vez**
  con el último punto del lote.
- **Devuelve**: número de posiciones aceptadas.
- **Errores**: `Credenciales invalidas`, `p_positions debe ser un array JSON`.

### 3.3 Comandos

```sql
public.enqueue_command(p_device_id uuid, p_command text, p_args jsonb default '{}') returns bigint
```

Lista blanca (cualquier otro valor se rechaza en el servidor **y** se ignora en el teléfono):
`locate_now`, `flush`, `lock`, `alarm`, `stop_alarm`, `stop_tracking`, `burst`, `stop_burst`.
- **Quién**: solo `authenticated` (comprueba `auth.role()`).
- **Valida**: el comando está en la lista blanca de 8; y **anti-repetición**: si ya hay uno igual en
  `pending`/`delivered` de los últimos 2 minutos, lanza error en lugar de apilar.
- **Devuelve**: el `id` del comando.

```sql
public.pull_commands(p_device_id uuid, p_token text) returns jsonb
```
- **Quién**: `anon` + token válido.
- **Caduca** primero lo que lleve más de **10 minutos** sin recogerse (`status = 'expired'`).
- **Devuelve** hasta **5** comandos en `[{ id, command, args }, …]`, marcándolos `delivered`
  (`for update skip locked`: dos sondeos simultáneos no se pisan).
- Actualiza `devices.last_seen`.

```sql
public.ack_command(p_device_id uuid, p_token text, p_command_id bigint,
                   p_status text, p_result text default null) returns void
```
- **Quién**: `anon` + token válido del **mismo** dispositivo dueño del comando.
- **Cierra** el comando con `done`/`failed` y el motivo (el agente recorta a 300 caracteres).

### 3.4 Revocación

```sql
public.revoke_self(p_device_id uuid, p_token text, p_purge boolean default false) returns integer
public.revoke_device(p_device_id uuid, p_purge boolean default false)           returns integer
```
- `revoke_self`: desde el teléfono (token válido). `revoke_device`: desde el panel (`authenticated`).
- Rota el `secret_hash` a un valor aleatorio que nadie conoce ⇒ el token deja de servir.
- Expira los comandos que quedaran en la cola de ese dispositivo.
- Con `p_purge = true` borra además el historial de posiciones; **devuelve cuántas borró** (0 si no se
  pidió purga).

### 3.5 Estado y avisos

```sql
public.report_health(p_device_id uuid, p_token text, p_report jsonb) returns void
```
Informe del agente (claves; `TamperReport.toJson`):

```json
{ "app_alive": true, "tracking_on": true, "location_ok": true, "background_ok": true,
  "notifications_ok": true, "admin_active": true, "anti_theft_on": true, "admin_removed": false,
  "battery_pct": 72, "charging": false, "alerts": ["…"],
  "developer_options": false, "usb_debugging": false, "play_protect": true, "device_secure": true,
  "rooted": false, "install_source": "play", "android_version": "14", "security_patch": "2026-08-01",
  "accessibility_apps": [], "notification_listeners": [], "device_admins": ["com.mi.agente"],
  "app_list_shared": true, "installed_count": 84,
  "installed_apps": [ { "n": "WhatsApp", "p": "com.whatsapp" }, … ] }
```

- **Upsert** en `device_checks` (una fila por dispositivo, siempre la última foto) y `last_seen`.
- Textos largos recortados en el servidor (`install_source` 80, versión/parche 40) para que un cliente
  defectuoso no meta un campo gigante.

```sql
public.report_event(p_device_id uuid, p_token text, p_kind text,
                    p_note text default null, p_lat double precision default null,
                    p_lon double precision default null) returns bigint
```
- `p_kind` en `sos` / `checkin` (cualquier otro valor se rechaza). Inserta en `device_events` y
devuelve el `id` del aviso. Nota recortada a 200 caracteres.

### 3.6 Internas y de mantenimiento

```sql
public.agent_token_ok(p_device_id uuid, p_token text) returns boolean   -- sin EXECUTE para nadie
public.geofences_eval(p_device_id uuid, p_lat double precision, p_lon double precision,
                      p_at timestamptz) returns void                    -- EXECUTE revocado a anon
public.prune_positions() returns void                                   -- admin + pg_cron
```

`geofences_eval` calcula la distancia a cada zona del dispositivo y:
- si `inside` es `NULL` (primer dato tras crear la zona) **solo fija el estado**, sin avisar;
- si cambia de estado, inserta el evento (`enter`/`exit`) según `notify_on` y actualiza
  `inside` + `last_event_at`.

`prune_positions` aplica la retención: **posiciones 7 días**, comandos 30 días, avisos (geovallas y
dispositivo) 90 días. El bloque final intenta programarlo con `pg_cron` a las 03:00 y, si la extensión no
está disponible, lo dice por `raise notice` para programarlo a mano.

## 4. Permisos (grants) — resumen

| Función | `anon` | `authenticated` |
|---|---|---|
| `ingest_positions` | ✅ (con token) | ✅ |
| `pull_commands` | ✅ (con token) | ✅ |
| `ack_command` | ✅ (con token) | ✅ |
| `report_health` | ✅ (con token) | ✅ |
| `report_event` | ✅ (con token) | ✅ |
| `revoke_self` | ✅ (con token) | ✅ |
| `enqueue_command` | ❌ | ✅ |
| `revoke_device` | ❌ | ✅ |
| `prune_positions` | ❌ (revocado) | ✅ |
| `pair_device` | ❌ (revocado) | ✅ |
| `agent_token_ok` | ❌ | ❌ (solo uso interno) |
| `geofences_eval` | ❌ (revocado) | ❌ (la llama `ingest_positions`) |

En Postgres **toda función nace con `EXECUTE` para `PUBLIC`**, y Supabase expone como RPC cualquiera
ejecutable por `anon`. Por eso el archivo revoca explícitamente lo que no debe ser público: sin ese
`revoke`, con la clave anon (que es pública por diseño) se podría llamar a `pair_device()` para
sobrescribir el token de un dispositivo conocido, o a `prune_positions()` para borrar historial.

## 5. Ejemplos

Emparejar un teléfono (SQL Editor):

```sql
select public.pair_device('Móvil de Ana', 'un-token-largo-y-aleatorio-de-32-chars');
```

Encolar y leer el resultado de un comando (panel autenticado):

```sql
select public.enqueue_command('<uuid>', 'burst', '{"minutes":10,"interval_sec":3}'::jsonb);

select command, status, result,
       to_char(finished_at, 'HH24:MI:SS') as terminado
  from public.device_commands
 where device_id = '<uuid>'
 order by created_at desc
 limit 10;
```

Estado de todos los dispositivos:

```sql
select id, label, to_char(last_seen, 'DD/MM HH24:MI') as visto,
       positions_count, pending_commands, last_command
  from public.device_health
 order by label;
```

## 6. Errores frecuentes

| Mensaje | Causa | Arreglo |
|---|---|---|
| `Credenciales invalidas` | Token rotado o `device_id` equivocado | Reemparejar (`pair_device`) y pegar de nuevo en la app |
| `Comando no permitido: X` | El panel intentó algo fuera de la lista blanca | Usar los botones del panel |
| `Ya hay un "X" en curso para este dispositivo` | Anti-repetición de 2 min | Esperar y repetir |
| `Requiere sesion de administrador` | RPC llamada sin sesión | Iniciar sesión en `admin.html` |
| `function digest(text, unknown) does not exist` | Falta `pgcrypto` en `extensions` | Ejecutar el archivo completo (lo maneja la §1) |
| `column ... does not exist` | Backend antiguo | Reejecutar `supabase-setup.sql` completo |
