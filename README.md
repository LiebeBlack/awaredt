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

**Modo antirrobo (opcional, tu propio teléfono):** con DeviceAdmin y una sola política
(`force-lock`) el dueño puede **bloquear la pantalla en remoto** desde el panel, y Android
exige desactivar esa protección antes de poder desinstalar la app. No hay borrado remoto
(no se declara `wipe-data`). El icono y la notificación **siguen visibles**: no es un modo
encubierto, es una protección antirrobo sobre un teléfono propio.

**Limitación de Android 14 (y posteriores), que conviene saber de antemano:** tras un reinicio el
sistema no deja crear un servicio de ubicación desde segundo plano (la ubicación es un permiso
«mientras se usa»; solo se libran apps *device owner*, widgets o una pulsación en una notificación). El
agente arranca igualmente —sigue reportando estado y recibiendo comandos—, lo dice en su notificación y
en el panel (*rastreo sin acceso a ubicación: abre la app una vez*), y recupera el GPS en cuanto se abre
la app una vez. No es un fallo del agente: es una regla del sistema que afecta a cualquier app con
`targetSdk 34`. En Android 8–13 el reinicio se recupera solo.

> 📋 **Guía operativa paso a paso (checklist con casillas): ver [`SETUP.md`](SETUP.md).**
> Este README es la referencia técnica; SETUP.md es la lista de "qué hacer en qué orden".

## Documentación

| Documento | Para qué sirve |
|---|---|
| [`docs/MAPA-RECURSOS.md`](docs/MAPA-RECURSOS.md) | **Mapa de recursos**: cada archivo, tabla, vista, función, política, comando, evento, ajuste y permiso, con dónde vive |
| [`docs/ARQUITECTURA.md`](docs/ARQUITECTURA.md) | Cómo funciona por dentro: flujos, modelo de confianza, supervivencia, consumo y decisiones |
| [`docs/API-BACKEND.md`](docs/API-BACKEND.md) | Contrato del backend: firmas, payloads, permisos y errores |
| [`docs/OPERACION.md`](docs/OPERACION.md) | Manual de uso diario: rutinas, control remoto, zonas, umbrales y qué mirar cuando algo falla |
| [`docs/CAMBIOS.md`](docs/CAMBIOS.md) | Registro de cambios por versión, correcciones notables y cómo actualizarse |
| [`SETUP.md`](SETUP.md) | Instalación paso a paso, desde cero |

`scripts/check-docs.ps1` comprueba que el mapa siga coincidiendo con el código (tablas, funciones,
comandos, ajustes, archivos y los conteos declarados) y falla si algo se añade sin documentar. Corre
en la CI web, así que la documentación no puede quedarse atrás en silencio:

```bash
pwsh -File scripts/check-docs.ps1
```

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

El repositorio incluye **GitHub Actions** (`.github/workflows/android.yml`): **cada push a
`main` compila, firma y PUBLICA el APK** en el release **«Última compilación»** del repositorio
(Releases → *latest*), con nombre de versión automático (`2026.9.12-<n>`). No hay que tocar
tags ni acciones manuales: push → APK instalable. En pull requests solo compila y sube el
artifact. La firma usa un keystore persistente (ver [RELEASE.md](RELEASE.md)); para que las
actualizaciones se instalen sin desinstalar, configura una vez el secreto
`ANDROID_KEYSTORE_PASSWORD` o sube el keystore cifrado siguiendo esa guía.

Además, `assembleDebug` + `lintDebug` siguen corriendo con JDK 17 y Gradle 8.9, y el APK
queda como *artifact* en la pestaña **Actions**. La web también se valida en cada push
(`web.yml`: sintaxis JS + estructura de páginas y SQL).

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
| Control remoto | on | Acepta comandos del panel (ubicar, bloquear, alarma, detener) |
| Rastreo inteligente | on | Si no te mueves no gasta GPS: envía un "sigo vivo" cada 5 min |
| Persecución remota | apagada | ⚡ desde el panel: 3 s durante 10 min (tope 30), visible y temporizada |
| PIN del propietario | sin definir | Protege detener, guardar ajustes y antirrobo (no es recuperable) |

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
| Control remoto | Cola `device_commands` + RPC `enqueue_command` (admin) / `pull_commands` + `ack_command` (agente, pull cada 60 s; 15 s durante persecución) |
| Panel familiar | Vista `latest_positions` con `color` y filtro `show_on_public`; marcadores por persona |
| Rastro | Limpieza y análisis en el cliente (sin coste de servidor): saltos, paradas, huecos, color por velocidad |
| Revocación | `revoke_self` (desde la app) y `revoke_device` (desde el panel): rotan el hash del token a un valor aleatorio |
| PIN local | PBKDF2-HMAC-SHA256 (210 k iteraciones + salt) en almacenamiento cifrado; bloqueo incremental por intentos |

### Estructura

```
web/
  index.html  app.js            → panel público (mapa en vivo, 5 s)
  admin.html admin.js           → historial, replay, gráficas, dispositivos
  404.html                      → redirección amable en Pages
  config.js  supabase-setup.sql → configuración y esquema
android/app/src/main/java/com/locator/agent/
  MainActivity.kt               → UI: emparejamiento, permisos, estado, PIN, antirrobo
  security/PinStore.kt          → PIN (PBKDF2) y bloqueo por intentos
  security/PinPrompt.kt         → diálogos de PIN para acciones sensibles
  admin/DeviceAdmin.kt          → DeviceAdminReceiver + bloqueo remoto (force-lock)
  sync/CommandChannel.kt        → pull de comandos, lista blanca y ack
  sync/AlarmPlayer.kt           → alarma remota con auto-parada (2 min)
  sync/TamperCheck.kt           → detección de manipulación (permisos, antirrobo, servicio)
  sync/TamperWorker.kt          → comprobación cada 6 h vía WorkManager
  sync/EventReporter.kt         → SOS y "llegué bien" desde el propio teléfono
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
  android.yml     → firma y PUBLICA el APK en «Última compilación» con cada push
  web.yml         → valida JS/SQL + guarda de placeholders
  deploy-pages.yml → publica web/ en GitHub Pages con cada push
  release.yml     → APK release estable al crear un tag v*
```

## 5. Control remoto, PIN del propietario y revocación

### Desde el panel (`admin.html` → Dispositivos)

Cada dispositivo lleva cinco botones de acción y un registro de resultados:

| Botón | Comando | Qué hace | Requisito |
|---|---|---|---|
| 📍 | `locate_now` | Fix inmediato de alta precisión, sin esperar al ciclo | control remoto activo en el móvil |
| 🔒 | `lock` | Bloquea la pantalla del teléfono | **modo antirrobo** activo en el móvil |
| 🔔 | `alarm` | Suena la alarma del sistema (máx. 2 min) | — |
| 🔕 | `stop_alarm` | Detiene la alarma | — |
| ⚡ | `burst` | **Persecución**: cada 3 s durante 10 min (args: `minutes`, `interval_sec`) | rastreo en marcha |
| 🔋 | `stop_burst` | Corta la persecución antes de que caduque | — |
| ⏹ | `stop_tracking` | Detiene el rastreo y apaga el watchdog | — |
| 🚫 | `revoke_device` | Rota el hash del token: el teléfono deja de poder enviar | sesión de admin |

Además, en la fila de cada persona: **color** (rueda de color → `devices.color`, el mismo color en todos
los mapas) y **👁/🙈** (visibilidad en el panel público; el admin lo sigue viendo siempre).

### Modo persecución (`burst`)

Sirve para seguir un trayecto en detalle (alguien volviendo de noche, una quedada, un recado):
sube a 3 s con alta precisión y **sin gate de movimiento**, y vuelve solo a su ritmo normal al
caducar. Límites, a propósito:

- **Tope duro de 30 minutos.** No existe "persecución permanente".
- El dueño puede pararla con 🔋; el sistema la corta sola al expirar (reloj, no contador en RAM).
- La notificación del teléfono cambia a **"Persecución activa · 3 s (temporal)"**: nunca se disimula.
- Si en el teléfono el rastreo está **detenido**, `burst` **no lo resucita**: responde
  `failed` con "hay que iniciarlo a mano". Parar es parar.

La caja **Últimos comandos** muestra el resultado real que reporta el agente
(`done` / `failed` + motivo), no un botón mudo. El agente consulta la cola cada 60 s
(configurable hasta 15 min) y los comandos sin recoger **caducan a los 10 minutos**, así
que un teléfono apagado no ejecuta órdenes viejas al volver.

Límites deliberados del canal: es **pull** (el agente nunca abre puertos ni escucha
órdenes), la lista de comandos es **cerrada**, y no existe forma de ejecutar código,
instalar APKs, leer SMS o contactos, ni borrar el teléfono.

### PIN del propietario (en la app)

- Protege las acciones locales: **detener** (también desde la notificación), **guardar
  configuración**, **desactivar el modo antirrobo** y **desvincular**.
- Se guarda solo como hash **PBKDF2-HMAC-SHA256** (210 000 iteraciones, salt de 16 bytes)
  en almacenamiento cifrado; la comparación es en tiempo constante.
- **No es recuperable.** 5 fallos → 30 s de bloqueo, duplicándose hasta 30 min; el contador
  sobrevive al cierre de la app.
- Se gestiona en *Seguridad y control → Definir / cambiar PIN* (también permite quitarlo,
  pidiendo el actual).

### Panel familiar (`index.html`)

El panel público dejó de ser "un dispositivo a la vez": ahora es un **mapa familiar**.

- **Todos los miembros a la vez**, cada uno con su color (el que definiste en admin, o uno de la
  paleta), con pulso solo si la señal es reciente.
- **Leyenda familiar** en la izquierda con nombre, última señal y batería de cada persona; pulsar
  una fila centra ese miembro y muestra su detalle (velocidad, precisión, fuente) y la ruta de la
  sesión.
- La lista de dispositivos solo se reconstruye cuando cambia el conjunto: el selector no parpadea
  en cada sondeo de 5 s.
- **Privacidad por dispositivo:** el interruptor 👁/🙈 del panel admin decide si ese dispositivo
  aparece en el mapa público (`devices.show_on_public`). El historial del admin nunca se oculta.

### El rastro: qué se dibuja y qué se descarta

El historial del admin ya no dibuja puntos crudos. Antes de pintar, `admin.js` limpia y analiza:

- **Saltos imposibles fuera:** un tramo que exige más de **250 km/h** es ruido de GPS, no un viaje,
  y se descarta (el contador dice cuántos).
- **Fixes malos fuera:** precisión peor que **150 m** no aporta al rastro.
- **Paradas reales:** racimos de puntos dentro de **75 m** durante **5 min** o más, con centroide
  incremental, listados con hora de inicio/fin y duración.
- **Huecos:** silencios de más de **10 min** se marcan como `hueco` (y cortan la parada: sin datos
  no se puede afirmar que alguien "estuvo quieto").
- **Resumen del tramo:** usados/descartados, nº de paradas y huecos, tiempo en movimiento vs parado.
- **Color por velocidad** en los puntos: ⚪ quieto · 🟢 a pie · 🟡 urbano · 🔴 rápido.
- **Suavizar** (casilla en la barra del replay) aplica una media móvil **solo a la línea dibujada**.
  CSV, GPX y replay siguen usando los puntos originales: no se maquillan los datos.
- El panel admin **avisa si falta reejecutar el SQL** ("falta la vista device_health") en lugar de
  romperse: en ese caso sigue listando con la tabla `devices`, sin contadores.

#### Calibrar el rastro desde `config.js`

Los umbrales son tuyos, no del código. En `web/config.js`:

| Clave | Por defecto | Qué controla |
|---|---|---|
| `maxKmh` | 250 | Un tramo que exige más velocidad es ruido y se descarta |
| `maxAccuracyM` | 150 | Fixes con precisión peor no se dibujan |
| `stopRadiusM` | 75 | Radio para "sigue en el mismo sitio" |
| `stopMin` | 5 | Minutos mínimos dentro del radio para contar una parada |
| `gapMin` | 10 | Minutos de silencio que se marcan como hueco |

### Avisos en el panel (batería, silencio, mandos sin recoger)

En *Dispositivos* hay una línea de avisos que resume lo que requiere atención, sin correo ni push
(y sin llamadas extra a Supabase: usa datos ya cargados):

- **Sin señal** más de `alertSilentMin` minutos (por defecto 6 h).
- **Batería** por debajo de `alertBatteryPct` % **y sin cargar** — un móvil que va a morir en
  una hora es la causa nº 1 de dejar de ver a alguien.
- **Mandos sin recoger**: comandos `pending` que nadie ha cogido (app cerrada, sin red).
- Cada fila muestra además batería y si está cargando, junto a la etiqueta, el color y la visibilidad.

### Qué sobrevive y qué no (sin engaños)

**Sobrevive a todo lo que es el sistema, no a lo que es una decisión.**

| Situación | ¿Sigue rastreando? | Cómo |
|---|---|---|
| Reinicio del teléfono | Sí | `BootReceiver` (`BOOT_COMPLETED`) |
| Actualización del APK | Sí | `MY_PACKAGE_REPLACED` (el sistema mata el servicio al reemplazar el paquete) |
| Muerte del proceso | Sí | `START_STICKY` + relanzado en `onDestroy` + watchdog |
| Deslizar la app fuera de *Recientes* | Sí | `onTaskRemoved` reprograma el watchdog |
| Doze y fabricantes agresivos | Casi siempre | `AlarmManager.setAndAllowWhileIdle` + WorkManager + exención de batería (paso 4.6) |
| Sin red / sin cobertura | Sí, con retraso | Buffer cifrado en el dispositivo y vaciado al volver la conexión |
| **Que el dueño pulse Detener** | **No** | Queda parado y el watchdog no insiste: parar es parar |
| **Que alguien desinstale la app** | **No** | Con el modo antirrobo, Android exige desactivar el admin antes; después se puede |

Nada de esto oculta la app, disimula la notificación, resiste un *force-stop* ni se reinstala solo.
Esa parte no está y no la voy a escribir: es lo que convierte una herramienta en *stalkerware*.

### ¿Y si alguien lo desactiva? Te enteras, con hora y motivo

Como no puedo impedir que alguien con el teléfono desbloqueado toque lo que quiera, el agente
**vigila y avisa** (`device_checks` + RPC `report_health`, una fila por dispositivo):

- **`DESACTIVARON EL MODO ANTIRROBO`** — la señal más fuerte: el dueño lo activó y ya no está.
- **Permiso de ubicación revocado**, o revocado solo el "todo el tiempo".
- **Notificaciones desactivadas** (intento clásico de esconder el rastreo).
- **El servicio no está corriendo** con el rastreo marcado como activo.
- **Rastreo detenido a mano**, con la hora.
- Batería y si está cargando, en la misma foto.

Se revisa **al arrancar**, **al abrir la app**, **cuando arranca el servicio** y **cada 6 h** con
WorkManager (que despierta al proceso aunque el servicio esté caído). En el panel aparece en la fila
del dispositivo (`⚠️ 2 problema(s)`) y arriba en la línea de avisos, con el motivo y `hace X`. Si el
teléfono está apagado o sin red no se puede subir nada: para eso está el aviso de **sin señal**,
que lo detecta por ausencia.

### El teléfono también ve los mandos

Cuando el panel ejecuta algo, la app del teléfono lo muestra en su estado: `último mando: alarm OK`.
No hay registro oculto: quien lleva el dispositivo puede ver que le acaban de mandar una acción y si
funcionó. Si prefieres que el panel no pueda ordenar nada, apaga **Control remoto** en la app.

### Desvincular y restaurar

- **App → "Detener y desvincular dispositivo"** (pide PIN): para el rastreo, borra el buffer
  local y llama a `revoke_self`, que invalida el token en el servidor y, si eliges «sí»,
  borra también el historial.
- **Panel → 🚫**: el mismo efecto en remoto (teléfono perdido o robado).
- **Restaurar:** vuelve a emparejar (`pair_device` con la misma etiqueta devuelve el mismo
  `uuid`) y pega uuid + token nuevos en la app. Puedes validar el flujo entero sin teléfono
  con `tools/simulate-agent.mjs`.

### Actualizar una instalación existente

Vuelve a ejecutar **todo** `web/supabase-setup.sql`: crea `device_commands`, la vista
`device_health`, las RPC nuevas y cierra dos agujeros de permisos de la versión anterior
(§6). Los cambios en el agente requieren recompilar el APK (Actions → Android CI).

## 6. Control parental y supervisión

### Zonas seguras (geovallas)

Se definen por persona, con un radio de **50 m a 20 km**, en *admin.html → Zonas seguras*.
Puedes crear el centro con un clic lógico: **Usar centro del mapa** o **Usar última posición**.
Se elige si avisa al **entrar**, al **salir** o **ambos**.

La evaluación ocurre **en el servidor**, dentro de `ingest_positions`: cero batería extra en el
teléfono, funciona con la app cerrada, tras un reinicio y también con el simulador. El primer dato
tras crear la zona **solo fija el estado** (si no, recibirías un "entró" falso al crearla). Los
cambios se registran en `geofence_events` (90 días) y en el panel ves `dentro` / `fuera` por zona,
el círculo dibujado en el mapa (verde dentro, ámbar fuera) y la lista de avisos con hora.

Una geovalla avisa de **entradas y salidas**. No vigila lo que alguien hace dentro, y eso es
supervisión, no vigilancia.

### Avisos que manda el propio teléfono

La persona que lleva el dispositivo también tiene botón, que es lo que distingue cuidar de vigilar:

- **🆘 SOS** — envía un aviso con su posición, activa **10 minutos** de seguimiento detallado (2 s)
  y queda registrado con hora. Si el rastreo estaba parado, **lo inicia** (`device_events`, tipo `sos`):
  aquí sí, porque no es una orden remota sino una petición de ayuda de quien tiene el teléfono.
- **✅ Llegué bien** — un check-in tranquilizador (`checkin`), sin cambiar el ritmo de muestreo.

Ambos avisos aparecen en *Avisos recientes* (el SOS en rojo) junto a las entradas y salidas de zona.

### Herramientas informativas del panel

- **Rangos rápidos**: Hoy / Ayer / 7 días, con el rango activo indicado.
- **Resumen del tramo**: distancia, batería mín.–máx., primer y último dato, tiempo en movimiento y
  parado, número de paradas, huecos y puntos descartados.
- **Estado de manipulación**: `⚠️ N problema(s)` por dispositivo y el motivo con hora.
- **Avisos**: sin señal, batería baja sin cargar y mandos sin recoger (además de la manipulación).

### Estado y postura del dispositivo

Además del rastreo, el agente informa de **cómo está configurado el teléfono** y de **qué acceso
peligroso tiene dentro** (tarjeta *Estado del dispositivo*, en `device_checks`):

| Dato | Para qué sirve |
|---|---|
| Bloqueo de pantalla (`device_secure`) | Sin PIN, cualquiera que coja el teléfono puede parar el rastreo |
| Parche de seguridad y versión de Android | Un sistema sin parches es el agujero más común |
| Root (`su`, kernel de test) | Con root, cualquier protección es decorativa |
| Depuración USB y opciones de desarrollador | Vías técnicas para manipular el dispositivo |
| Play Protect | Verificación de apps desactivada = puerta abierta |
| Origen de instalación | Saber si el agente vino de la tienda o de un APK suelto |
| **Apps con accesibilidad** | Es donde viven los *keyloggers* (nombres, no conteo) |
| **Apps que leen notificaciones** | Pueden leer mensajes de todas las apps |
| **Administradores del dispositivo** | Quién puede bloquear o borrar el teléfono |
| **Apps instaladas** (solo nombres) | Control parental sobre un menor: saber qué hay instalado, sin uso ni horarios |

Todo se lee con APIs públicas, sin permisos especiales, y los riesgos aparecen también en la línea de
avisos (`1 app(s) con accesibilidad: …`, `dispositivo con root`, `parche de seguridad antiguo`).
Sirve tanto para saber cómo está el teléfono de un menor como para **detectar software espía
instalado en tu propio teléfono** — incluidas las tres categorías que se usan para espiar.

**Límites de esta detección, sin adornos:** la de root es de mejor esfuerzo (Magisk y similares la
ocultan); se excluyen los paquetes del propio sistema para no llenar la vista de ruido, así que un espía
disfrazado de `com.android.*` podría pasar desapercibido; en algunos fabricantes leer ajustes globales
(ADB, Play Protect) devuelve el valor por defecto; y en Android 11+ la visibilidad de paquetes puede
hacer que una lista salga incompleta — **una lista vacía no prueba que no haya nada**. Se declaran las
consultas del manifiesto para las tres categorías, pero la conclusión honesta es que esto sirve para ver
lo evidente, no como antivirus.

### Control parental: lista de aplicaciones (solo nombres)

Para el caso de un adulto responsable sobre el teléfono de un **menor**, el agente envía la **lista de
apps instaladas con icono en el lanzador** (juegos, redes, mensajería) y su número. Nada más: es
información para **decidir si hay que sentarse a hablar**, no para vigilar.

- **Solo nombres**: `label` y paquete. **No** hay tiempos de uso, horas de apertura, número de veces
  que se abre cada app ni ningún patrón temporal — no existen en ninguna tabla, en ningún RPC y en
  ninguna pantalla de este proyecto.
- **Desactivable en el teléfono**: interruptor *«Compartir la lista de apps instaladas»* en la app
  (viene activado). Si se apaga, el panel lo dice tal cual (`no compartida`) en lugar de mostrar un
  cero falso; el resto del estado sigue llegando.
- **Visible**: la pantalla del teléfono y el aviso de uso responsable enumeran exactamente esto.
- **Sin permisos restringidos**: se obtiene con un bloque `<queries>` del intent `LAUNCHER`, así que
  **no** hace falta `QUERY_ALL_PACKAGES` (el permiso que Google Play limita a antivirus y gestores de
  archivos) ni el acceso especial de uso (`PACKAGE_USAGE_STATS`). Es la vía compatible con la tienda.
- Límites honestos: se ven las apps **con icono propio** (no servicios ni paquetes de sistema sin
  lanzador), se envían como mucho las **300 primeras** y se excluye el propio agente.

Sigue **fuera** el uso por horas y la frecuencia de cada app: el nombre dice *qué tiene*, el uso
diría *cómo vive*, y solo lo primero es control parental. Para **limitar** tiempos y apps, la
herramienta correcta es **Google Family Link**, con APIs oficiales y consentimiento del menor.

Además están las **tres categorías de riesgo** (accesibilidad, notificaciones y administradores),
que son conjuntos pequeños con justificación de seguridad clara.

## 7. Seguridad y límites conocidos

- El token del agente se guarda cifrado en el dispositivo y **solo** como hash SHA-256
  en Supabase; rota con *Emparejar / rotar token* o con 🚫 desde `admin.html`.
- **FIX v1.1:** en Postgres toda función nace con `EXECUTE` para `PUBLIC`, y Supabase expone
  como RPC cualquiera ejecutable por `anon`. Con la anon key (pública por diseño) se podía
  llamar a `pair_device()` y sobrescribir el token de un dispositivo conocido, o a
  `prune_positions()`. Ahora ambas están revocadas para `anon` (`supabase-setup.sql` §9).
  Si venías de la versión anterior, reejecuta el SQL completo.
- El PIN es una barrera práctica (que un ladrón no apague el rastreo de un toque), no
  criptografía fuerte: quien controle físicamente un dispositivo desbloqueado y con root
  puede hacer lo que quiera. No hay nada aquí que lo evite, a propósito.

### Correcciones de esta versión (v1.2)

- **El "modo discreto" no hacía nada.** En Android 8+ la importancia la fija el *canal*, y el
  agente usaba siempre el de `IMPORTANCE_LOW`: `setPriority(PRIORITY_MIN)` se ignoraba. Ahora hay un
  canal `tracking_min` con `IMPORTANCE_MIN` y la notificación cambia de canal de verdad (sin sonido,
  sin vibración, plegada — pero **siempre visible** y con su botón Detener). Requiere APK nuevo.
- **Precision+ bloqueaba el hilo principal.** Leer celdas/WiFi en el callback de ubicación (hilo de
  UI) producía tirones y riesgo de ANR; ahora la recolección y la escritura en la base van a un
  hilo de trabajo, con copia defensiva del `Location`.
- **"Ubicar ahora" podía quedarse en el buffer** si la batería estaba baja de 20 %: el fix puntual
  ahora es urgente (ignora el ahorro) y se envía al momento.
- **Un móvil apagado desaparecía del mapa familiar** entero. La vista nueva `public_devices` lista a
  todo el mundo emparejado y con `show_on_public`: quien no da señal aparece como *sin posición*,
  que es justo la información que se busca.
- **Parar siempre llega:** si el ajuste o un mando remoto desactivan el rastreo, el propio bucle del
  servicio lo detecta y se cierra, incluso si un `startService` externo fue bloqueado por los
  límites de ejecución en segundo plano de Android.
- **Los avisos de batería no se inventan** en dispositivos ocultos: el silencio se mide con
  `last_seen` (visible para el admin en todos) y la batería solo si hay última posición publicada.
- La vista pública solo expone la última posición (RLS); el historial exige sesión.
- La retención es de 7 días (`prune_positions`), ejecutada por pg_cron o manualmente:
  `select public.prune_positions();`
- El número de solicitudes del plan gratuito de Supabase (≈ 500 h/mes de cómputo) y el
  tamaño de la BD holgan para 1–3 dispositivos reportando cada 5 s con retención de 7 días.
- **Legal/ético:** usa este sistema solo en dispositivos propios o con el consentimiento
  explícito e informado de la persona que lo lleva. La notificación persistente existe
  precisamente para que el rastreo sea siempre verificable por el usuario del teléfono.
