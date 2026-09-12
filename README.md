# Sistema de Localización Dual

Agente Android (Kotlin, API 26–34) + panel web en GitHub Pages con backend Supabase gratuito.

```
Agente Android                    Supabase (gratis)                 GitHub Pages (gratis)
GPS+celdas+WiFi ── fix 5 s ──▶  buffer cifrado ──▶ RPC ingest  ◀─── lectura REST/anon
   (SQLCipher offline)          devices/positions + RLS          público: última pos.
                                                                 admin: historial total
```

**Nota de alcance:** el agente es visible por diseño (icono en el lanzador, notificación
persistente con botón "Detener"). Ocultar el lanzador o excluir la app de "Recientes" es
característica de *stalkerware* y no está implementada. El "modo discreto" solo reduce la
notificación a prioridad mínima — nunca la elimina.

> 📋 **Guía operativa paso a paso (checklist con casillas): ver [`SETUP.md`](SETUP.md).**
> Este README es la referencia técnica; SETUP.md es la lista de "qué hacer en qué orden".

---

## 1. Crear el backend en Supabase (~10 min)

1. Crea un proyecto gratis en <https://supabase.com> (guarda la **contraseña** de la BD).
2. Abre **SQL Editor → New query**, pega el contenido completo de
   [`web/supabase-setup.sql`](web/supabase-setup.sql) y ejecútalo (Run). Crea:
   - Tablas `devices` y `positions` con índices.
   - Vista pública `latest_positions` (solo la última posición por dispositivo).
   - RLS: `anon` no puede leer tablas; `authenticated` (admin) puede; la ingesta solo
     funciona con `device_id` + token válidos (RPC `ingest_positions`).
   - RPC `pair_device` (emparejar) y `prune_positions` (retención 7 días, con pg_cron si
     está disponible).
3. **Empareja tu dispositivo** (SQL Editor):
   ```sql
   select public.pair_device('Mi Telefono', 'UN-TOKEN-LARGO-Y-ALEATORIO-123456');
   ```
   El token tiene que tener ≥ 16 caracteres. Copia el **uuid** devuelto y el **token**.
4. Ve a **Settings → API** y copia:
   - `Project URL` → `https://xxxx.supabase.co`
   - `anon public` key
5. Crea el usuario **admin** en **Authentication → Users → Add user** (email + contraseña).

## 2. Publicar el panel en GitHub Pages

1. Sube el repositorio a GitHub y activa **Settings → Pages → Source =
   "GitHub Actions"** (una sola vez). El workflow `deploy-pages.yml` publica
   `web/` automáticamente con cada push a `main`.
2. Edita `web/config.js` con tus datos:
   ```js
   supabaseUrl: "https://xxxx.supabase.co",
   supabaseAnonKey: "eyJhbGciOi...",
   mapCenter: [40.4168, -3.7038],   // centro inicial del mapa
   ```
3. Abre `https://tuusuario.github.io/tu-repo/` → **panel en vivo** (público).
4. Abre `https://tuusuario.github.io/tu-repo/admin.html` → inicia sesión con el usuario
   admin: historial completo, reproducción de rutas, gráficas, CSV/GPX y gestión de
   dispositivos.

> La clave `anon` es pública por diseño: la seguridad la dan las políticas RLS.
> Sin sesión, nadie puede leer el historial; solo la última posición.

### Probar sin backend

- **Sin instalar nada:** abre `index.html?demo=1` — el panel simula un dispositivo
  moviéndose por Madrid.
- **Con backend real, sin APK:** usa el simulador del agente (Node 18+):
  ```bash
  node tools/simulate-agent.mjs \
    --url https://TU-PROYECTO.supabase.co \
    --key TU_CLAVE_ANON \
    --device UUID_DEL_DISPOSITIVO \
    --token TU_TOKEN \
  # [--interval 5 --lat 40.4168 --lon -3.7038 --laps 0]
  ```
  Envía lotes al mismo RPC que el agente Android cada 5 s: los paneles público y
  admin se llenan de datos reales (elimina el dispositivo después desde SQL:
  `delete from devices where id = '...';`).

## 3. Compilar e instalar el agente Android

### Opción A — compilar en GitHub (sin instalar nada)

El repositorio incluye **GitHub Actions** (`.github/workflows/android.yml`): cada push a
`android/**` compila `assembleDebug` + `lintDebug` con JDK 17 y Gradle 8.9, y publica el
APK como *artifact* descargable en la pestaña **Actions** del repositorio. La web también
se valida en cada push (`web.yml`: sintaxis JS + estructura de páginas y SQL).

### Opción B — compilar en local

1. Abre la carpeta [`android/`](android/) en **Android Studio** (Hedgehog o superior,
   JDK 17). Sincroniza Gradle.
2. `Build → Build APK(s)` e instala el APK en el teléfono.
3. En la app:
   - **Emparejamiento:** URL de Supabase, clave `anon`, **UUID del dispositivo** y
     **token** (del paso 1.3) → *Guardar configuración*.
   - Concede **ubicación** y, cuando el sistema lo pida, **"Permitir todo el tiempo"**
     (necesario para reportar con la pantalla apagada).
   - Pulsa **Iniciar rastreo**. Verás la notificación permanente "Seguimiento activo".
4. (Recomendado) **Optimizar batería → exención**: el botón abre el diálogo oficial del
   sistema (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`); el agente usa además
   `START_STICKY`, watchdog con AlarmManager y WorkManager como red de seguridad.
5. En el panel web debería aparecer el marcador en pocos segundos.

### Ajustes del agente

| Ajuste | Valor por defecto | Efecto |
|---|---|---|
| Intervalo de reporte | 5 s | Frecuencia de fixes y envío en tiempo real |
| Precisión+ | off | Adjunta celdas/WiFi visibles (auditoría) |
| Batería adaptativa | on | Baja a intervalo lento bajo 20 % sin cargador |
| Notificación discreta | off | Notificación mínima (sigue visible, requisito del sistema) |

## 4. Arquitectura

| Capa | Implementación |
|---|---|
| Fixes | `FusedLocationProviderClient`, `PRIORITY_HIGH_ACCURACY` (GPS + celdas + WiFi fusionados) |
| Persistencia offline | Room + SQLCipher (`sqlcipher-android`), fallback en memoria |
| Envío | OkHttp (pool HTTP/2), lotes ≤ 20, backoff exponencial, flush instantáneo al volver la red |
| Background | Foreground Service (`location|dataSync`) + `START_STICKY` + watchdog + WorkManager |
| Ingesta | RPC de Postgres con verificación SHA-256 del token (`pgcrypto`); RLS estricta |
| Panel | HTML + TailwindCSS (CDN) + Leaflet + Supabase JS, sin build step |
| Admin | Supabase Auth (email/contraseña) + políticas `authenticated` |

### Estructura

```
web/
  index.html  app.js            → panel público (mapa en vivo, 5 s)
  admin.html admin.js           → historial, replay, gráficas, dispositivos
  404.html                      → redirección amable en Pages
  config.js  supabase-setup.sql → configuración y esquema
android/app/src/main/java/com/locator/agent/
  MainActivity.kt               → UI: emparejamiento, permisos, estado
  sync/LocationService.kt       → Foreground Service (núcleo del rastreo)
  sync/SyncManager.kt           → cola cifrada + lotes + backoff
  sync/SupabaseClient.kt        → cliente HTTP
  sync/SyncWorker.kt            → red de seguridad WorkManager
  sync/WatchdogReceiver.kt      → re-kick con AlarmManager
  sync/BootReceiver.kt          → auto-arranque tras reboot
  data/ (Room+SQLCipher, ajustes cifrados)
tools/
  simulate-agent.mjs            → simulador del agente (prueba sin APK)
.github/workflows/
  android.yml     → APK debug en cada push (artifact)
  web.yml         → valida JS/SQL + smoke del backend + guarda de placeholders
  deploy-pages.yml → publica web/ en GitHub Pages con cada push
  release.yml     → APK release al crear un tag v*
```

## 5. Seguridad y límites conocidos

- El token del agente se guarda cifrado en el dispositivo y **solo** como hash SHA-256
  en Supabase; rota con *Emparejar / rotar token* en `admin.html`.
- La vista pública solo expone la última posición (RLS); el historial exige sesión.
- La retención es de 7 días (`prune_positions`), ejecutada por pg_cron o manualmente:
  `select public.prune_positions();`
- El número de solicitudes del plan gratuito de Supabase (≈ 500 h/mes de cómputo) y el
  tamaño de la BD holgan para 1–3 dispositivos reportando cada 5 s con retención de 7 días.
- **Legal/ético:** usa este sistema solo en dispositivos propios o con el consentimiento
  explícito e informado de la persona que lo lleva. La notificación persistente existe
  precisamente para que el rastreo sea siempre verificable por el usuario del teléfono.
