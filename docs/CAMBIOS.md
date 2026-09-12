# Registro de cambios

Qué añadió y qué corrigió cada incremento, y qué hay que hacer para actualizarse. Las versiones siguen
el encabezado de `web/supabase-setup.sql`, que es la pieza que marca el ritmo: cuando cambia el backend,
hay que reejecutarlo.

**Cómo actualizarse**, siempre en este orden:

1. Reejecutar `web/supabase-setup.sql` **completo** (es idempotente y no borra datos).
2. Recompilar e instalar el APK (Actions → *Android CI*).
3. Recargar los paneles (`Ctrl+F5`).

Si el panel dice *«Reejecuta supabase-setup.sql: falta …»* es exactamente eso: falta el paso 1.

---

## v1.3 — Control parental y lista de aplicaciones

- **`InstalledApps.kt`**: lista de apps instaladas **solo por nombre y paquete** (apps con icono de
  lanzador, incluidas las preinstaladas como YouTube, Maps o Chrome), con tope de 300, etiqueta recortada
  a 60 caracteres y exclusión del propio agente.
- **Sin permisos restringidos**: se obtiene con un bloque `<queries>` del intent `LAUNCHER`, así que
  **no** se declara `QUERY_ALL_PACKAGES` ni `PACKAGE_USAGE_STATS`.
- **`device_checks`**: columnas `app_list_shared`, `installed_count`, `installed_apps`, y `report_health`
  actualizado (27 columnas = 27 valores).
- **Panel**: tarjeta *Aplicaciones instaladas* con contador, buscador por nombre o paquete y mensajes
  honestos para `no compartida` y para APK antiguo.
- **Interruptor en el teléfono** *«Compartir la lista de apps instaladas»* (activado por defecto) y
  aviso de uso responsable actualizado.
- Docs: [`MAPA-RECURSOS.md`](MAPA-RECURSOS.md), [`ARQUITECTURA.md`](ARQUITECTURA.md),
  [`API-BACKEND.md`](API-BACKEND.md), [`OPERACION.md`](OPERACION.md) y el comprobador
  `scripts/check-docs.ps1`, que corre en la CI web.

### Correcciones
- **`shareApps` sin definir** en `SecurityPosture.inspect`: el módulo no habría compilado.
- **Las apps del sistema se excluían por `FLAG_SYSTEM`**, lo que ocultaba YouTube, Maps, Chrome y Gmail
  antes de actualizarse. El filtro correcto es tener icono de lanzador.
- **Listar apps en el hilo de la interfaz**: `TamperCheck.summary()` ejecutaba la consulta al
  `PackageManager` en la UI (tirones). Ahora usa `inspect(includeApps = false)`.

## v1.2 — Estado, manipulación y control parental con zonas

- **`device_checks` + `report_health`**: una fila por dispositivo con permisos, antirrobo,
  notificaciones, servicio, batería y la lista de problemas (`alerts`).
- **`TamperCheck` / `TamperWorker`**: detección de manipulación al abrir la app, al arrancar el servicio,
  al reiniciar y cada 6 h (WorkManager despierta el proceso aunque el servicio esté muerto).
- **`SecurityPosture`**: bloqueo de pantalla, parche de seguridad, root, depuración USB, Play Protect,
  origen de instalación y las apps con accesibilidad, lectura de notificaciones o administración.
- **`geofences` / `geofence_events` / `geofences_eval`**: zonas seguras evaluadas **en el servidor** con
  cada envío; el primer dato tras crear la zona solo fija el estado (sin “entró” falso).
- **`device_events` / `report_event`**: **🆘 SOS** (con 10 min de seguimiento detallado) y
  **✅ llegué bien** desde el propio teléfono.
- **Panel**: rangos Hoy/Ayer/7 días, resumen del tramo ampliado, línea de avisos unificada y tarjeta
  *Estado del dispositivo*.

### Correcciones
- **El “modo discreto” no hacía nada**: en Android 8+ la importancia la manda el canal y el agente usaba
  siempre `IMPORTANCE_LOW`. Ahora hay un canal `tracking_min` con `IMPORTANCE_MIN` (plegada, sin sonido),
  **siempre visible y con su botón Detener**.
- **Precision+ bloqueaba el hilo de la interfaz**: la lectura de celdas/WiFi se movió a un hilo de trabajo
  con copia defensiva del `Location`.
- **“Ubicar ahora” podía quedarse en el buffer** con batería baja: ese fix puntual ahora es urgente.
- **Un móvil apagado desaparecía del mapa**: nueva vista `public_devices`, que lista a todos los visibles
  aunque no tengan posición.
- **Parar ahora siempre llega**: si el ajuste o un mando remoto desactivan el rastreo, el propio bucle del
  servicio detecta el cambio y se cierra.
- **Los avisos no inventan datos**: el silencio se mide con `last_seen` y la batería solo si hay última
  posición publicada.

## v1.1 — Control remoto, PIN y antirrobo

- **`device_commands` + `enqueue_command` / `pull_commands` / `ack_command`**: canal *pull* con lista
  blanca de 8 comandos, anti-repetición de 2 minutos y caducidad de 10 minutos para los no recogidos.
- **`revoke_self` / `revoke_device`**: rotación de token (y borrado opcional del historial).
- **`agent_token_ok`**: verificación del token contra su hash SHA-256, sin `EXECUTE` público.
- **PIN del propietario** (`PinStore`): PBKDF2-HMAC-SHA256 con 210 000 iteraciones, salt de 128 bits,
  comparación en tiempo constante y bloqueo incremental (5 fallos ⇒ 30 s, duplicando hasta 30 min).
- **Modo antirrobo** (`DeviceAdmin`): una sola política, `force-lock`. Android exige desactivarlo antes de
  desinstalar. Sin `wipe-data` ni `reset-password`.
- **`CommandChannel`, `AlarmPlayer`, `RemoteCommand`**, botón **Detener y desvincular**, aviso de uso
  responsable con registro de consentimiento y rastreo inteligente (gate de movimiento y modo quieto).
- **Panel**: botones 📍 🔒 🔔 🔕 ⚡ 🔋 ⏹ 🚫 por dispositivo y caja de *Últimos comandos* con el resultado
  real.

### Correcciones
- **El botón “Detener” de la notificación no hacía nada**: apuntaba a un broadcast implícito sin receptor
  declarado. Ahora abre la app con una petición de parada (y pide PIN si existe).
- **Agujero de permisos en el backend**: en Postgres toda función nace con `EXECUTE` para `PUBLIC` y
  Supabase expone como RPC cualquiera ejecutable por `anon`. Con la clave anon (pública por diseño) se
  podía llamar a `pair_device()` para sobrescribir el token de un dispositivo conocido, o a
  `prune_positions()`. Revocado en la §9 del SQL.

## v1.0 — Base

- Agente Android (Kotlin, API 26–34) con servicio en primer plano, buffer cifrado con SQLCipher, lotes de
  20 y reintentos; `LocationService`, `SyncManager`, `SupabaseClient`, `BootReceiver`,
  `WatchdogReceiver`, `SyncWorker`.
- Backend: `devices`, `positions`, `latest_positions`, RLS, `ingest_positions`, `pair_device`,
  `prune_positions` (retención de 7 días) y cron diario.
- Paneles: `index.html` (mapa público) y `admin.html` (login, historial, replay, gráficas, CSV/GPX).
- Simulador `tools/simulate-agent.mjs` para probar sin teléfono.

---

## Criterios que no han cambiado en ninguna versión

- La app es **visible**: icono en el lanzador y notificación permanente con botón *Detener*. El modo
  discreto baja la importancia, **nunca** la elimina.
- **Parar es parar**: ninguna versión resucita el rastreo después de una parada deliberada.
- **Nada irreversible**: sin borrado remoto, sin instalación silenciosa, sin auto-reinstalación.
- **Sin permisos de vigilancia íntima**: nada de accesibilidad, lectura de mensajes, contactos, cámara ni
  micrófono, y de las apps solo el nombre.
- **Todo lo que se comparte está enumerado** en la pantalla del teléfono, en el aviso de uso responsable
  y en [README §6](../README.md).
