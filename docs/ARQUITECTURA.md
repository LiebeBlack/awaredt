# Arquitectura

Cómo funciona el sistema por dentro: quién habla con quién, qué pasa cuando algo falla, cuánto gasta y
qué decisiones están tomadas a propósito. Los inventarios están en
[`MAPA-RECURSOS.md`](MAPA-RECURSOS.md), el contrato del backend en [`API-BACKEND.md`](API-BACKEND.md) y
el uso diario en [`OPERACION.md`](OPERACION.md).

## 1. Piezas y responsabilidades

```
        TELÉFONO                                    NUBE                        NAVEGADOR
┌─────────────────────────┐   ┌──────────────────────────────────┐   ┌───────────────────────────┐
│ LocationService         │   │ Supabase (Postgres + Auth + RLS) │   │ index.html + app.js       │
│  · captura (fused)      │   │  7 tablas · 3 vistas             │   │  · mapa familiar (lectura)│
│  · gate de movimiento   │   │  12 funciones (RPC)              │   │                           │
│  · persecución temporal │   │  8 políticas RLS                 │   │ admin.html + admin.js     │
│  · notificación visible │   │  6 índices · retención por cron  │   │  · login (Supabase Auth)  │
│  ↓ PreciseFix           │   │                                  │   │  · control, zonas, avisos │
│ Room CIFRADO (buffer)   │   │  anon  → solo RPC con token      │   │  · postura, apps, rastro  │
│  ↓ SyncManager (lotes 20)│  │  auth  → leer/gestionar          │   │                           │
│ CommandChannel (pull 60s)│◄──┤  agente→ su propio device_id     │◄──┤                           │
└─────────────────────────┘   └──────────────────────────────────┘   └───────────────────────────┘
```

Ninguna pieza hace el trabajo de otra: el servicio captura y encola; el sincronizador envía; el canal de
comandos recoge órdenes; el panel solo lee y encola. Esto es lo que permite que un fallo de red no
pierda puntos y que un comando no dependa de que la app esté abierta.

## 2. Flujo de una posición

```
LocationService (cada 5 s, o 60 s en modo ahorro)
   │  fix con precisión aceptable
   ├─► ¿se movió?  distancia > max(30 m, precisión)   ── no ──► modo quieto
   │                                                              │  sin GPS ni radio
   │                                                              └─ "sigo vivo" cada 5 min
   ▼
PreciseFix ──► Room cifrado (SQLCipher)  ── sent = 0
                     │
                     ▼  SyncManager: lotes de 20, purga local a las 24 h
             SupabaseClient ──POST──► ingest_positions(device_id, token, [ ... ])
                     │
                     ├─ valida token contra SHA-256           → pg_sleep(0.4) si falla
                     ├─ inserta las posiciones
                     ├─ actualiza devices.last_seen
                     └─ geofences_eval(último punto del lote) → device/geofence events
```

**Modo quieto con racha de 3.** No basta un fix dentro del radio: hacen falta **3 seguidos**
(`STATIONARY_STREAK`) para declarar quietud y bajar a `PRIORITY_BALANCED_POWER_ACCURACY` con agrupación
de fixes, sin GPS y sin radio hasta el keep-alive. Así el ruido normal del GPS no apaga el rastreo fino
cuando la persona está parada en un semáforo.

**El buffer es la garantía sin red.** Los puntos se guardan cifrados y se envían cuando vuelve la
conexión; el worker de 15 min y el vaciado al recuperar red son las dos patas que lo vacían. La purga
local de enviados es de 24 h y la del servidor de **7 días** (posiciones).

## 3. Flujo de un comando remoto

```
admin.html ──► enqueue_command(device, 'burst', {minutes:10, interval_sec:3})
                 │  · exige sesión authenticated
                 │  · solo 8 comandos en la lista blanca
                 │  · si ya hay uno igual en curso (< 2 min) → error
                 ▼
             device_commands (status = pending)

CommandChannel (pull cada 60 s) ──► pull_commands(device_id, token)
                 │  · caduca lo pendiente de más de 10 min  (teléfono apagado ⇒ no ejecuta órdenes viejas)
                 │  · devuelve hasta 5 y los marca 'delivered'
                 ▼
     RemoteCommand.allowed? ── no ──► se ignora sin ejecutar nada
                 │ sí
                 ▼
     LocationService / DeviceAdmin / AlarmPlayer  ──►  ack_command(status, result)
                 ▼
             admin.html muestra done/failed + motivo real
```

**Todo es *pull*.** El teléfono nunca abre puertos ni recibe conexiones: pregunta. Por eso no hay
necesidad de push, de un servidor intermedio ni de permisos extra, y por eso un móvil apagado no ejecuta
nada al volver.

**Persecución acotada.** `burst` sube a ~3 s con alta precisión y sin gate de movimiento, con **tope
duro de 30 minutos** y caducidad por reloj (`burst_until_ms`): si el proceso muere, el modo expira
igual. Si el rastreo estaba detenido a mano, `burst` responde `failed` — parar es parar.

## 4. Estado, manipulación y postura

```
                       ┌─────────────────────────────────────────────┐
  Al abrir la app ─────┤ TamperCheck.inspect()                       │
  Al arrancar el svc ──┤  · permisos (ubicación, segundo plano,      │
  Cada 6 h (Worker) ───┤    notificaciones)                          │
  Al reiniciar ────────┤  · antirrobo: intención vs realidad         │
                       │  · servicio caído con rastreo activo        │
                       │  · SecurityPosture.inspect()                │
                       │      bloqueo · parche · root · USB ·        │
                       │      Play Protect · accesibilidad ·         │
                       │      notificaciones · admins · apps         │
                       └───────────────┬─────────────────────────────┘
                                       ▼ report_health  →  device_checks (1 fila/dispositivo)
                                       ▼ panel: "⚠️ N problema(s)" + motivo con "hace X"
```

**La señal más fiable es la intención.** Cuando el dueño activa el modo antirrobo, la app anota su
voluntad (`anti_theft_enabled = true`). Si más tarde aparece desactivado, eso ya no es un despiste: es
manipulación, y llega con hora y motivo. Es lo máximo que Android permite sin convertir el agente en algo
que ignore a quien tiene el teléfono.

**Los avisos no se inventan datos.** El silencio se calcula con `last_seen` y la batería solo si hay
última posición publicada; un dispositivo oculto del panel público no genera un falso “sin posición”.

## 5. Control parental: qué se comparte y qué no

| Sí | No |
|---|---|
| Nombres de apps con icono de lanzador y su número | Tiempos de uso, horas de apertura, frecuencia |
| Entradas y salidas de zonas con hora | Contenido de mensajes, contactos, fotos, cámara, micrófono |
| Estado del teléfono y accesos peligrosos (accesibilidad, notificaciones, admins) | Inventario interno del sistema, capturas de pantalla |
| Ubicación y rastro analizado | Historial de llamadas, SMS, teclado |

La lista de apps se obtiene con un bloque `<queries>` del intent `LAUNCHER` (visibilidad de paquetes de
Android 11+), **sin** `QUERY_ALL_PACKAGES` ni `PACKAGE_USAGE_STATS`. El interruptor *«Compartir la lista
de apps»* está en la pantalla del teléfono; si se apaga, se envía `app_list_shared: false` y el panel lo
dice tal cual en lugar de mostrar un cero falso. Para **limitar** apps y tiempos, la herramienta correcta
es Google Family Link.

## 6. Supervivencia: qué aguanta y qué no

| Situación | Qué pasa | Mecanismo |
|---|---|---|
| Reinicio del teléfono (Android 8–13) | El rastreo vuelve solo | `BootReceiver` + `BOOT_COMPLETED` |
| Reinicio del teléfono (Android 14+) | Arranca en modo `dataSync` **sin GPS** y avisa; recupera la ubicación al abrir la app una vez | Promoción del tipo de servicio en primer plano + alerta en `device_checks` |
| Actualización del APK | Vuelve solo (el sistema mata el servicio al reemplazar el paquete) | `MY_PACKAGE_REPLACED` |
| Muerte del proceso | Se relanza | `START_STICKY` + `onDestroy` + watchdog de 15 min |
| Deslizar la app fuera de *Recientes* | El watchdog reprograma | `onTaskRemoved` |
| Doze / fabricante agresivo | Despierta igualmente | `setExactAndAllowWhileIdle` (o `setAndAllowWhileIdle` sin permiso de alarmas exactas), WorkManager, exención de batería |
| Sin red | Buffer cifrado; se vacía al volver | Room + SyncManager + SyncWorker |
| Service caído con el worker vivo | El panel lo denuncia | `TamperWorker` cada 6 h |
| El dueño pulsa Detener (con PIN) | **No vuelve**: parar es parar | Watchdog y workers se apagan |
| Alguien desactiva el antirrobo o quita permisos | **Llega el aviso con hora y motivo** | `TamperCheck` + `device_checks` |

Lo que **no** hace, por decisión de diseño: ocultarse del usuario, esconder la notificación o el icono,
suplantar otra aplicación, impedir el force-stop, reinstalarse solo, borrar el teléfono en remoto o
seguir rastreando después de una parada deliberada. Un sistema que ignora la orden de parar deja de ser
seguridad y pasa a ser exactamente el problema que dice resolver.

Y lo que **no puede** hacer, porque lo prohíbe Android: desde Android 14, crear un servicio en primer
plano de tipo `location` con la app en segundo plano lanza `SecurityException` (la ubicación es un
permiso «mientras se usa»). El agente no lo intenta a ciegas: arranca con `dataSync`, se **promociona**
a `location` en cuanto hay una pantalla visible y, mientras tanto, lo cuenta en la notificación y en
`device_checks` en lugar de fingir que todo va bien.

## 7. Consumo y recursos

| Recurso | Comportamiento |
|---|---|
| Batería (movimiento) | 5 s, alta precisión, gate de movimiento activo |
| Batería (quieto) | Sin GPS ni radio, “sigo vivo” cada 5 min, prioridad balanceada y fixes agrupados |
| Batería (persecución) | 3 s y máxima precisión, **máximo 30 min** |
| Batería baja | Con batería < 20 % el envío se agrupa más (el fix puntual de 📍 sigue siendo urgente) |
| Red | Lotes de 20 posiciones; una RPC por lote |
| Sondeo de comandos | 1 petición cada 60 s (15–900 s configurable) |
| Estado del teléfono | 1 envío por apertura de la app, arranque del servicio, reinicio y cada 6 h |
| Lista de apps | Solo al informar el estado, y nunca en el hilo de la interfaz |
| Almacenamiento local | Buffer cifrado, enviados purgados a las 24 h |
| Almacenamiento servidor | Posiciones 7 d · comandos 30 d · avisos 90 d (cron diario 03:00, o a mano) |

## 8. Modelo de confianza

1. **La clave anon es pública** (va en el JS del panel): no da acceso a nada por sí sola. Todo lo
   sensible está detrás de RLS o de una RPC con token.
2. **El token del agente es el único secreto operativo**: se guarda cifrado en el teléfono y como hash
   SHA-256 en el servidor. Falla con retardo (`pg_sleep(0.4)`) para que no sirva de oráculo.
3. **El PIN es local**: protege las acciones del teléfono (detener, guardar, desactivar antirrobo,
   desvincular). No sustituye al login del panel, que es lo que protege el historial.
4. **El canal es de ida y vuelta separado**: el agente escribe solo lo suyo y recoge solo lo suyo. Un
   fallo de permisos del panel no puede convertirse en una orden.
5. **La lista blanca es la frontera**: sin canal de comandos arbitrarios, lo peor que puede llegar por
   un token robado es ubicar, bloquear o sonar la alarma — no ejecutar código ni leer datos personales.

## 9. Ciclo de vida del dato

```
captura ─► buffer cifrado (24 h de enviados) ─► positions (7 días) ─► latest_positions / public_devices
                                                     │
                                                     ├─► geofence_events (90 días) ─► avisos del panel
                                                     └─► device_events  (90 días) ─► SOS / check-in

device_commands (30 días: la cola se purga por antigüedad)
device_checks  (una fila: se sobrescribe en cada informe, no hay histórico de postura)
```

Al **desvincular** desde el teléfono, la app detiene el rastreo, borra el buffer local, invalida el token
en el servidor y —si el dueño lo pide— borra el historial de ese dispositivo.

## 10. Decisiones y por qué

| Decisión | Motivo |
|---|---|
| Notificación imposible de ocultar, con botón Detener | Es la frontera entre supervisión y vigilancia; el modo discreto baja importancia y canal, nunca visibilidad |
| Solo `force-lock` en DeviceAdmin, sin `wipe-data` ni `reset-password` | Un borrado remoto es irreversible y no debe poder dispararse por un fallo de credenciales |
| Canal de comandos *pull* con lista cerrada | No abre puertos, no hay ejecución arbitraria y funciona tras horas sin conexión |
| Caducidad de 10 min en los comandos | Un teléfono apagado no debe ejecutar órdenes viejas al volver |
| Persecución con tope de 30 min y caducidad por reloj | Evita el “siempre agresivo” por accidente u olvido |
| Geovallas evaluadas en el servidor | Cero batería extra, funciona con la app cerrada y con datos importados |
| El primer fix tras crear una zona solo fija el estado | Evita el “entró” falso inmediato |
| Suavizado solo en la línea dibujada | Los datos, el replay y las exportaciones mantienen los puntos originales |
| Paginación desde el servidor (`range`) en el historial | El plan gratuito y los móviles modestos no aguantan traer todo |
| Todo idempotente en `supabase-setup.sql` | Reejecutarlo es la vía de actualización: no borra datos ni exige migraciones a mano |
