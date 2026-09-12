# Mapa de recursos

Inventario completo del **Sistema de Localización Dual**: cada archivo, cada objeto del backend, cada
comando, cada ajuste y cada permiso, con dónde vive y qué hace.

> **Cómo está hecho este documento.** No es un resumen escrito de memoria: el inventario se extrajo del
> código y `scripts/check-docs.ps1` comprueba que siga coincidiendo. Si alguien añade una tabla, una
> función, un comando o un ajuste y no lo documenta aquí, la comprobación falla y apunta el nombre que
> falta. Los detalles de comportamiento están en [`ARQUITECTURA.md`](ARQUITECTURA.md), el contrato del
> backend en [`API-BACKEND.md`](API-BACKEND.md) y el uso diario en [`OPERACION.md`](OPERACION.md).

## 1. Vista de capas

```
┌──────────────────────────────┐   HTTPS (anon key + token del agente)
│  TELÉFONO (Android 8+)       │────────────────────────────────────┐
│                              │                                    │
│  MainActivity ── PIN ──┐     │   POST /rest/v1/rpc/<funcion>      │
│  LocationService       │     │   ◄── pull_commands (cada 60 s)    │
│   └─ PrecisionCollector│     │                                    ▼
│  TamperCheck/Worker    │     │                     ┌─────────────────────────────┐
│  CommandChannel ───────┘     │                     │  SUPABASE (Postgres + Auth) │
│  SyncManager ── Room cifrado │                     │                             │
└──────────────────────────────┘                     │  7 tablas · 3 vistas        │
                                                     │  12 funciones · 8 políticas │
             ▲                                       └──────────────┬──────────────┘
             │ comandos (pull)                                      │ consultas con
             │                                                      │ sesión de admin
┌────────────┴──────────────────┐   ┌──────────────────────┐        │
│  admin.html (privado)         │   │  index.html (familiar)│◄───────┘
│  login + control + postura    │   │  mapa de todos        │
└───────────────────────────────┘   └───────────────────────┘
```

Tres identidades distintas, nunca mezcladas:

| Identidad | Quién | Qué puede hacer | Cómo se prueba |
|---|---|---|---|
| `anon` | Cualquiera con la clave pública del panel | Solo lo que se le concede explícitamente (insertar posiciones con token válido, hacer pull/ack con token válido, reportar estado, revocar su propio token) y leer las dos vistas públicas | La propia clave anon |
| `authenticated` | El administrador con su cuenta de Supabase Auth | Leer y gestionar dispositivos, comandos, geovallas, avisos y postura; encolar comandos y revocar tokens | Sesión (email + contraseña) |
| Agente | El teléfono emparejado | Escribe y recoge **solo para su propio `device_id`**, y solo con su token | `device_id` + token (se guarda como SHA-256) |

## 2. Inventario: aplicación Android

### 2.1 Código (26 archivos Kotlin)

Ruta base: `android/app/src/main/java/com/locator/agent/`

| Archivo | Rol | Red |
|---|---|---|
| `LocAgentApp.kt` | `Application`: crea los **3 canales de notificación** | — |
| `MainActivity.kt` | Pantalla única: ajustes, PIN, antirrobo, SOS/check-in, divulgación, estado | sí |
| `admin/DeviceAdmin.kt` | Modo antirrobo (`DeviceAdminReceiver`, solo `force-lock`) | — |
| `data/AppDatabase.kt` | Room cifrado (SQLCipher) + clave de BD en prefs cifradas | — |
| `data/LocationEntity.kt` | Entidad `positions` del buffer local + DAO (pending/markSent/prune/clear) | — |
| `data/SettingsRepository.kt` | Preferencias cifradas: 21 claves (token, intervalos, interruptores) | — |
| `security/PinStore.kt` | PIN: PBKDF2-HMAC-SHA256 (210 k), salt 128 bits, bloqueo incremental | — |
| `security/PinPrompt.kt` | Diálogos de PIN para las acciones locales | — |
| `sync/AlarmPlayer.kt` | Alarma remota con tono del sistema a volumen de alarma | — |
| `sync/BatteryMonitor.kt` | Batería y si está cargando | — |
| `sync/BootReceiver.kt` | Relanza tras reinicio **y** tras actualizar el APK | — |
| `sync/CommandChannel.kt` | Sondea comandos (pull cada 60 s), ejecuta, responde (`ack`) | sí |
| `sync/EventReporter.kt` | SOS y “llegué bien” desde el teléfono | sí |
| `sync/InstalledApps.kt` | Lista de apps con icono (solo nombres) vía `<queries>` | — |
| `sync/LocationService.kt` | Servicio en primer plano: captura, gate de movimiento, persecución, notificación | sí (vía SyncManager) |
| `sync/PreciseFix.kt` | Modelo del fix listo para enviar | — |
| `sync/PrecisionCollector.kt` | Precision+: lectura de celdas y WiFi (bajo demanda, en hilo de trabajo) | — |
| `sync/RemoteCommand.kt` | Lista **cerrada** de comandos permitidos | — |
| `sync/SecurityPosture.kt` | Postura del dispositivo (bloqueo, parche, root, USB, Play Protect, listas de riesgo, apps) | — |
| `sync/ServiceState.kt` | Estado observable para la pantalla (`ServiceStateHolder`) | — |
| `sync/SupabaseClient.kt` | Las 6 llamadas RPC del agente | sí |
| `sync/SyncManager.kt` | Vaciado del buffer en lotes de 20 + purga local de enviados | sí |
| `sync/SyncWorker.kt` | Red de seguridad cada 15 min (red disponible) | sí |
| `sync/TamperCheck.kt` | Detección de manipulación + informe de estado | sí |
| `sync/TamperWorker.kt` | Revisión de estado cada 6 h (despierta el proceso aunque el servicio esté muerto) | sí |
| `sync/WatchdogReceiver.kt` | Valla cada 15 min: relanza el servicio si el rastreo está activo | — |

### 2.2 Recursos Android (7)

| Archivo | Contenido |
|---|---|
| `res/layout/activity_main.xml` | Pantalla única (ajustes, botones, interruptores) |
| `res/values/strings.xml` | 23 textos y nombres de canal |
| `res/values/colors.xml` | Paleta del agente |
| `res/values/styles.xml` | Estilos base de la pantalla |
| `res/values/themes.xml` | Tema de la aplicación |
| `res/drawable/ic_launcher_foreground.xml` | Icono **visible** en el lanzador |
| `res/xml/device_admin.xml` | Política de DeviceAdmin: solo `force-lock` |

### 2.3 Permisos (13) y consultas de visibilidad

| Permiso | Para qué |
|---|---|
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | Ubicación en uso |
| `ACCESS_BACKGROUND_LOCATION` | Seguir reportando con la pantalla apagada |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `FOREGROUND_SERVICE_DATA_SYNC` | Servicio en primer plano (Android 8–14) |
| `RECEIVE_BOOT_COMPLETED` | Relanzar tras reinicio |
| `POST_NOTIFICATIONS` | Notificación permanente |
| `WAKE_LOCK` | Evitar que el sistema duerma el ciclo de captura |
| `INTERNET` | Enviar al backend |
| `ACCESS_NETWORK_STATE` | Detectar si hay red antes de intentar el envío |
| `ACCESS_WIFI_STATE` | Precision+ (celdas/WiFi) |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Pedir la exención de batería |

### 2.4 Compilación (5 archivos)

| Archivo | Contenido |
|---|---|
| `android/app/build.gradle.kts` | Módulo del agente: dependencias fijadas (Room + SQLCipher, Work, OkHttp, Play Services), versiones y `minSdk 26 / targetSdk 34` |
| `android/build.gradle.kts` | Buildscript raíz |
| `android/settings.gradle.kts` | Repositorios y módulos |
| `android/gradle.properties` | Memoria y flags de Gradle |
| `android/app/proguard-rules.pro` | Reglas de R8/ProGuard (no romper Room ni los modelos JSON) |

**No declarados a propósito:** `QUERY_ALL_PACKAGES`, `PACKAGE_USAGE_STATS` (acceso de uso),
accesibilidad, instalación de paquetes, SMS, contactos, cámara y micrófono. La visibilidad de apps se
consigue con un bloque `<queries>` del intent `LAUNCHER`, que es la vía compatible con Google Play.

## 3. Inventario: backend (Supabase)

### 3.1 Tablas (7)

| Tabla | Qué guarda | Claves |
|---|---|---|
| `devices` | Dispositivos: `id`, `label`, `secret_hash` (SHA-256 del token), `created_at`, `last_seen`, `color`, `show_on_public` | `label` único |
| `positions` | Historial de posiciones: `lat`, `lon`, `accuracy`, `speed`, `altitude`, `bearing`, `battery_pct`, `charging`, `source`, `provider`, `cell_wifi`, `recorded_at` | FK a `devices` con borrado en cascada |
| `device_commands` | Cola de comandos: `command`, `args`, `status`, `result`, `created_at`, `delivered_at`, `finished_at` | `status` en `pending/delivered/done/failed/expired` |
| `device_checks` | Última foto del estado del teléfono: permisos, antirrobo, notificaciones, batería, `alerts` (jsonb) y toda la postura (bloqueo, parche, root, USB, Play Protect, accesibilidad, notificaciones, admins, `app_list_shared`, `installed_count`, `installed_apps`) | Una fila por dispositivo |
| `geofences` | Zonas seguras: `label`, `lat`, `lon`, `radius_m` (50 m–20 km), `notify_on` (`enter`/`exit`/`both`), `inside`, `last_event_at` | FK a `devices` |
| `geofence_events` | Entradas y salidas: `kind` (`enter`/`exit`), `lat`, `lon`, `at` | FK a `devices` y a la zona |
| `device_events` | Avisos del teléfono: `kind` (`sos`/`checkin`), `note`, `lat`, `lon`, `at` | FK a `devices` |

### 3.2 Vistas (3)

| Vista | Para qué |
|---|---|
| `latest_positions` | Última posición de cada dispositivo **visible** (`show_on_public`), con color y etiqueta. Es lo que lee el panel familiar |
| `public_devices` | **Todos** los dispositivos visibles, con o sin posición: un móvil apagado no desaparece del mapa, se marca como *sin posición* |
| `device_health` | Contadores por dispositivo: `positions_count`, `pending_commands`, `last_command`, `color`, `show_on_public`. Solo `authenticated` |

### 3.3 Funciones (12)

| Función | Quién la ejecuta | Qué hace |
|---|---|---|
| `pair_device(label, token)` | Solo desde el SQL Editor (revocada para `anon`) | Da de alta o rota el token de un dispositivo |
| `ingest_positions(device_id, token, positions)` | Agente (token) | Inserta un lote y evalúa geovallas con el último punto |
| `prune_positions()` | Admin autenticado (y `pg_cron`) | Retención: posiciones 7 d, comandos 30 d, avisos 90 d |
| `agent_token_ok(device_id, token)` | Nadie (solo uso interno) | Verifica el token contra su hash |
| `enqueue_command(device_id, command, args)` | Admin autenticado | Encola un comando con lista blanca y anti-repetición (2 min) |
| `pull_commands(device_id, token)` | Agente (token) | Devuelve hasta 5 pendientes, los marca entregados y caduca los de más de 10 min |
| `ack_command(device_id, token, id, status, result)` | Agente (token) | Cierra el comando con `done`/`failed` y el motivo |
| `revoke_self(device_id, token, purge)` | Agente (token) | Rota el token en el servidor (y opcionalmente borra el historial) |
| `revoke_device(device_id, purge)` | Admin autenticado | Igual, pero desde el panel |
| `report_health(device_id, token, report)` | Agente (token) | Sube el estado y la postura (upsert: una fila por dispositivo) |
| `geofences_eval(device_id, lat, lon, at)` | Interna (revocada para fuera) | Evalúa entradas/salidas; el primer dato solo fija el estado |
| `report_event(device_id, token, kind, note, lat, lon)` | Agente (token) | Registra SOS y check-in |

### 3.4 Seguridad de datos

- **RLS activada** en las 7 tablas. El `anon` no tiene ninguna política sobre `devices` ni `positions`:
  no puede leerlas ni escribirlas directamente, solo a través de las RPC con token.
- **8 políticas** nombradas: `devices_admin_read`, `devices_admin_update` y `positions_admin_read`
  (lectura y gestión del admin) más `device_commands_admin_read`, `device_checks_admin_read`,
  `geofences_admin_all`, `geofence_events_admin_read` y `device_events_admin_read` (lectura del admin,
  escritura solo por RPC).
- **6 índices** para las consultas del panel y de la cola.
- Todas las funciones declaran `security definer` con `search_path` fijo y **`EXECUTE` revocado a
  `public, anon`** salvo las que necesitan el agente. Detalle en [`API-BACKEND.md`](API-BACKEND.md).

## 4. Inventario: panel web (7 archivos)

| Archivo | Líneas | Rol |
|---|---|---|
| `web/index.html` | 144 | Panel familiar: mapa de todos, leyenda con estado por persona |
| `web/app.js` | 442 | Mapa, marcadores, rutas del día, demo, seguimiento |
| `web/admin.html` | 287 | Panel privado: dispositivos, control, zonas, avisos, postura, apps, historial, gráficas |
| `web/admin.js` | 1178 | Login, consultas, limpieza del rastro, comandos, geovallas, eventos, postura, apps, replay |
| `web/config.js` | 36 | URL y clave anon, mapa, y los **umbrales ajustables** |
| `web/supabase-setup.sql` | 835 | Todo el backend (idempotente) |
| `web/404.html` | 20 | Página de error para Pages |

## 5. Catálogo de comandos remotos (8)

Lista **cerrada**: el agente ignora cualquier otro valor aunque llegue en la respuesta.

| Comando | Desde `admin.html` | Qué hace en el teléfono | Límite |
|---|---|---|---|
| `locate_now` | 📍 | Fix inmediato de alta precisión, sin esperar al ciclo | Envía **un solo** punto y no reactiva el rastreo, así que también funciona con el rastreo detenido |
| `flush` | (interno) | Vacía el buffer cifrado ya | — |
| `lock` | 🔒 | Bloquea la pantalla | Requiere modo antirrobo activo |
| `alarm` | 🔔 | Suena la alarma a volumen de alarma | Auto-parada a los 2 min |
| `stop_alarm` | 🔕 | Detiene la alarma | — |
| `stop_tracking` | ⏹ | Detiene el rastreo **y no lo relanza** | El dueño lo reinicia a mano |
| `burst` | ⚡ | Persecución: cada 3 s, alta precisión, sin gate de movimiento | **Tope duro 30 min** y caducidad por reloj |
| `stop_burst` | 🔋 | Corta la persecución antes de que caduque | — |

Además, el panel puede **renombrar**, **ocultar del panel público** (👁/🙈) y **revocar el token** (🚫) de
cada dispositivo, y borrar el historial de un dispositivo (`purge`) desde los comandos del panel.

## 6. Catálogo de eventos (4 tipos)

| Tipo | Origen | Dónde se ve |
|---|---|---|
| `sos` | Botón 🆘 del teléfono | *Avisos recientes*, en rojo |
| `checkin` | Botón ✅ “llegué bien” | *Avisos recientes* |
| `enter` / `exit` | Servidor, al evaluar una zona | *Avisos recientes* + círculo verde/ámbar en el mapa |

## 7. Ajustes

### 7.1 Agente (21 claves cifradas, `SettingsRepository`)

`tracking_enabled`, `device_id`, `device_token`, `supabase_url`, `supabase_anon_key`, `interval_sec`,
`interval_slow_sec`, `precision_plus`, `adaptive_battery`, `discreet_notif`, `anti_theft_enabled`,
`share_app_list`, `remote_control`, `command_poll_sec`, `smart_tracking`, `stationary_radius_m`,
`stationary_keepalive_sec`, `consent_accepted_at`, `consent_version`, `burst_until_ms`,
`burst_interval_sec`.

Límites duros: intervalo normal 5–300 s, intervalo lento 15–900 s, radio de quieto 5–500 m, keep-alive
60–3600 s, sondeo de comandos 15–900 s, persecución 2 s–15 s de muestreo y **30 min** de tope.

### 7.2 Panel (`web/config.js`) — 14 claves

Credenciales y mapa: `supabaseUrl`, `supabaseAnonKey`, `pollMs` (5 s), `mapCenter`, `mapZoom` (14),
`staleAfterSec` (120), `demo`.

Umbrales de análisis y avisos: `maxKmh` (250), `maxAccuracyM` (150), `stopRadiusM` (75), `stopMin` (5),
`gapMin` (10), `alertBatteryPct` (15), `alertSilentMin` (360).

## 8. Canales de notificación y acciones

| Canal | Importancia | Uso |
|---|---|---|
| `tracking` | `LOW` (silencioso) | Notificación permanente de rastreo |
| `tracking_min` | `MIN` (plegada, sin sonido) | Modo discreto: **sigue visible** y con su botón Detener |
| `sync` | `MIN` | Progreso de sincronización |

Acciones/intents declarados: `ACTION_STOP_REQUEST` (botón *Detener* de la notificación → abre la app y
pide PIN) y `ACTION_STOP` (parada efectiva). El watchdog usa un `PendingIntent` **explícito**, sin
broadcasts implícitos abiertos.

## 9. Dónde vive cada capacidad

| Capacidad | Archivos / objetos |
|---|---|
| Captura y envío de ubicación | `LocationService`, `PrecisionCollector`, `SyncManager`, `SupabaseClient`, `ingest_positions`, `positions` |
| Rastreo inteligente y persecución | `LocationService`, `SettingsRepository` (`smart_tracking`, `burst_*`), `enqueue_command`, `CommandChannel` |
| PIN del propietario | `PinStore`, `PinPrompt`, `MainActivity` |
| Modo antirrobo | `admin/DeviceAdmin`, `res/xml/device_admin.xml`, manifiesto |
| Detección de manipulación | `TamperCheck`, `TamperWorker`, `WatchdogReceiver`, `BootReceiver`, `device_checks`, `report_health` |
| Postura del dispositivo | `SecurityPosture`, `device_checks`, `renderPosture` |
| Control parental (apps) | `InstalledApps`, `<queries>`, `SecurityPosture`, `installed_apps`, `renderApps` |
| Zonas seguras | `geofences`, `geofence_events`, `geofences_eval`, `loadFences`/`renderFences` |
| SOS y check-in | `EventReporter`, `device_events`, `report_event`, `renderEvents` |
| Rastro analizado | `cleanTrail`, `analyzeTrail`, `renderTrailReport`, `smoothLine`, `speedColor` |
| Control remoto | `device_commands`, `enqueue/pull/ack`, `CommandChannel`, `RemoteCommand`, `sendCommand` |
| Revocación y borrado | `revoke_self`, `revoke_device`, `doUnpair`, botón 🚫 |
| Mapa familiar | `index.html`, `app.js`, `latest_positions`, `public_devices` |

## 10. Integración continua y scripts

| Recurso | Qué comprueba |
|---|---|
| `.github/workflows/android.yml` | Compila el APK (Gradle) |
| `.github/workflows/web.yml` | `node --check` de los JS, estructura de páginas y SQL, ausencia de credenciales de ejemplo |
| `.github/workflows/deploy-pages.yml` | Publica `web/` en GitHub Pages |
| `.github/workflows/quality.yml` | XML, cableado (ids/strings/paquetes/JS) y documentación al día |
| `.github/workflows/release.yml` | APK firmado y publicación de la release |
| `scripts/check-xml.ps1` | Los 8 XML de `android/` |
| `scripts/serve.ps1` | Servidor local para probar el panel |
| `scripts/check-docs.ps1` | **Este mapa**: que cada recurso del código siga documentado aquí |
| `scripts/check-wiring.ps1` | Cableado: cada `R.id`/`R.string`/`@string`/`@color` existe, el tipo del `findViewById` coincide con el XML, cada clase del manifiesto existe en su paquete, cada archivo está en la carpeta de su `package`, y cada id/handler que usa el JavaScript existe en su HTML |
| `tools/simulate-agent.mjs` | Simulador del agente (Node 18+): llena los paneles con un recorrido real por el mismo RPC, sin necesidad de un teléfono |

## 11. Cómo se mantiene

```bash
pwsh -File scripts/check-docs.ps1      # esto documento <-> codigo
pwsh -File scripts/check-wiring.ps1    # ids, strings, paquetes, JS <-> HTML
pwsh -File scripts/check-xml.ps1       # los 8 XML de android/
```

El primero compara el código con este documento: tablas, vistas, funciones, políticas, comandos remotos
y claves de ajuste, incluidos **los conteos declarados** (si aparece una tabla y el mapa sigue diciendo
«7 tablas», falla). El segundo vigila el cableado entre piezas. Los tres corren en el workflow
*Comprobaciones* (`quality.yml`), así que ni la documentación ni las referencias se quedan atrás en
silencio.
