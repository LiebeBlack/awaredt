package com.locator.agent.sync

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.locator.agent.LocAgentApp
import com.locator.agent.MainActivity
import com.locator.agent.R
import com.locator.agent.data.LocationEntity
import com.locator.agent.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Servicio en primer plano de ubicacion (Android 8 a 14).
 *
 * - FusedLocationProviderClient con PRIORITY_HIGH_ACCURACY: fusiona GPS,
 *   celdas y WiFi en un solo flujo de fixes.
 * - Notificacion persistente con accion "Detener" (requisito del sistema;
 *   modo discreto = prioridad minima, nunca oculta). Si hay PIN definido, esa
 *   accion lo pide antes de parar.
 * - START_STICKY + watchdog (AlarmManager) + reinicio en onDestroy.
 * - Rastreo inteligente: si el movil no se mueve (menos que
 *   `stationaryRadiusM`, ajustado por la precision del fix), se deja de gastar
 *   radio y se envia un "sigo vivo" cada `stationaryKeepAliveSec`. Ademas baja
 *   a PRIORITY_BALANCED_POWER_ACCURACY y agrupa los fixes.
 * - Canal de control remoto (pull de comandos desde el panel admin).
 */
class LocationService : LifecycleService() {

    private lateinit var settings: SettingsRepository
    private lateinit var fused: FusedLocationProviderClient
    private lateinit var precision: PrecisionCollector
    private lateinit var battery: BatteryMonitor
    private val pollScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val workScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var currentIntervalSec = 0
    private var currentPriority = -1

    /**
     * true = el servicio ya esta en primer plano CON el tipo `location`.
     * Desde segundo plano no se puede (Android 14): se arranca con `dataSync` y
     * se promociona al abrir la app.
     */
    private var foregroundHasLocation = false

    // --- estado del gate de movimiento (solo se envia cuando aporta) ---
    private var lastKeptLat = Double.NaN
    private var lastKeptLon = Double.NaN
    private var lastKeptAt = 0L
    private var lowMotionStreak = 0
    private var stationary = false
    private var lastNotifText: String? = null

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            handleFix(loc)
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository.get(this)
        precision = PrecisionCollector(this)
        battery = BatteryMonitor(this)
        fused = LocationServices.getFusedLocationProviderClient(this)

        startInForeground(withLocation = AppVisibility.visible)
        isRunning = true
        ServiceStateHolder.update { it.copy(running = true) }

        SyncWorker.schedule(this)
        WatchdogReceiver.schedule(this)
        TamperWorker.schedule(this)
        applyLocationRequest(force = true)
        startCommandPolling()
        reportHealthNow()
        Log.i(TAG, "Servicio de rastreo iniciado")
    }

    /**
     * Al salir de "Recientes", Android puede matar el servicio: se reprograma
     * el watchdog y se deja constancia del estado. Todo con mecanismos
     * oficiales, sin trucos de persistencia.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "Tarea eliminada por el usuario: reprogramando watchdog")
        WatchdogReceiver.schedule(this)
        TamperWorker.schedule(this)
    }

    private fun reportHealthNow() {
        pollScope.launch { TamperCheck.report(this@LocationService) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopTracking()
            return START_NOT_STICKY
        }
        // Si llega con la app ya visible, se recupera el tipo location (Android 14
        // lo prohibe en segundo plano: ver startInForeground).
        promoteForeground()
        // Reaplica el request solo si cambio el intervalo/prioridad efectivos
        applyLocationRequest()
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { fused.removeLocationUpdates(callback) }
        pollScope.cancel()
        workScope.cancel()
        isRunning = false
        ServiceStateHolder.update { it.copy(running = false) }
        val shouldRestart = settings.trackingEnabled && settings.pairingComplete
        super.onDestroy()
        if (shouldRestart) {
            // Reinicio automatico tras muerte del proceso (el watchdog cubre el resto)
            try {
                startForegroundService(Intent(this, LocationService::class.java))
            } catch (t: Throwable) {
                Log.w(TAG, "Reinicio automatico no permitido ahora", t)
            }
        }
    }

    // ------------------------------------------------------------------ notif

    /**
     * Arranca (o promociona) el servicio en primer plano.
     *
     * Android 14+: crear un servicio en primer plano de tipo `location` desde
     * segundo plano lanza SecurityException (la ubicacion es un permiso de tipo
     * "mientras se usa"), asi que en ese caso se arranca solo con `dataSync` y se
     * AVISA: el panel ve que falta acceso a ubicacion y la notificacion lo dice.
     * Cuando el usuario abre la app, [promoteForeground] vuelve a llamar a
     * startForeground anadiendo el tipo `location`, que es la via documentada
     * para anadir un tipo despues de lanzar el servicio.
     */
    private fun startInForeground(withLocation: Boolean) {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification)
            foregroundHasLocation = hasLocationPermission(this)
            setLocationAccess(foregroundHasLocation)
            return
        }

        val wantsLocation = withLocation && hasLocationPermission(this)
        val dataSyncOnly = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        if (wantsLocation) {
            try {
                ServiceCompat.startForeground(
                    this, NOTIF_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or dataSyncOnly
                )
                foregroundHasLocation = true
                setLocationAccess(true)
                return
            } catch (t: Throwable) {
                // Tipico en Android 14 al arrancar desde BOOT_COMPLETED/watchdog:
                // se degrada a dataSync y se sigue vivo (nunca se cae el proceso).
                Log.w(TAG, "Tipo location rechazado en segundo plano; se sigue con dataSync", t)
                foregroundHasLocation = false
            }
        }
        ServiceCompat.startForeground(this, NOTIF_ID, notification, dataSyncOnly)
        foregroundHasLocation = false
        setLocationAccess(false)
    }

    /**
     * Promociona el servicio a tipo `location` cuando ya hay una pantalla
     * visible. Sin esto, tras un reinicio en Android 14 el rastreo quedaria sin
     * acceso a ubicacion hasta abrir la app a mano.
     */
    private fun promoteForeground() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (foregroundHasLocation) return
        if (!AppVisibility.visible) return
        if (!hasLocationPermission(this)) return
        runCatching { startInForeground(withLocation = true) }
            .onFailure { Log.w(TAG, "No se pudo promocionar a tipo location", it) }
        applyLocationRequest(force = true)
    }

    /**
     * La accion "Detener" abre MainActivity con [ACTION_STOP_REQUEST] en vez de
     * ser un broadcast: asi, si hay PIN, la app puede pedirlo antes de parar
     * (un ladron no apaga el rastreo de un toque) y nunca depende de un
     * receptor que podria no estar declarado.
     */
    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PI_FLAGS
        )
        val stop = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java)
                .setAction(ACTION_STOP_REQUEST)
                .putExtra(EXTRA_STOP_REQUEST, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PI_FLAGS
        )
        // En Android 8+ la importancia se fija en el CANAL, no en la notificacion
        val channel = if (settings.discreetNotif && !settings.burstActive()) {
            LocAgentApp.CHANNEL_TRACKING_MIN
        } else {
            LocAgentApp.CHANNEL_TRACKING
        }

        return NotificationCompat.Builder(this, channel)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.notif_tracking_title))
            .setContentText(notificationText())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setPriority(
                if (settings.discreetNotif && !settings.burstActive()) NotificationCompat.PRIORITY_MIN
                else NotificationCompat.PRIORITY_LOW
            )
            .addAction(0, getString(R.string.notif_action_stop), stop)
            .build()
    }

    /** Texto de la notificacion: la persecucion se anuncia, nunca se disimula. */
    private fun notificationText(): String = when {
        !hasLocationPermission(this) -> "Sin permiso de ubicación: abre la app para concederlo"
        !foregroundHasLocation ->
            "Sin acceso a ubicación en segundo plano: abre la app una vez"
        settings.burstActive() ->
            "Persecución activa · ${settings.burstIntervalSec} s (temporal)"
        settings.discreetNotif -> "Modo discreto activo"
        else -> getString(R.string.notif_tracking_text)
    }

    /** Republica la notificacion del servicio solo si el texto cambio. */
    private fun refreshForegroundNotification() {
        val text = notificationText()
        if (text == lastNotifText) return
        lastNotifText = text
        runCatching { startInForeground(withLocation = foregroundHasLocation) }
    }

    // ------------------------------------------------------------------ fixes

    /**
     * Registra (o re-registra) los updates solo si cambiaron el intervalo o la
     * prioridad. Evita el baile de remove/request que gasta bateria.
     */
    private fun applyLocationRequest(force: Boolean = false) {
        if (!hasLocationPermission(this)) {
            Log.w(TAG, "Sin permiso de ubicacion; a la espera de que el usuario lo conceda")
            return
        }
        val intervalSec = effectiveIntervalSec()
        val priority = effectivePriority()
        if (!force && intervalSec == currentIntervalSec && priority == currentPriority) return

        currentIntervalSec = intervalSec
        currentPriority = priority
        runCatching { fused.removeLocationUpdates(callback) }

        val request = LocationRequest.Builder(priority, intervalSec * 1000L)
            .setWaitForAccurateLocation(false)
            .setMinUpdateDistanceMeters(0f)
            // Agrupa callbacks: menos despertares del proceso con la pantalla apagada
            .setMaxUpdateDelayMillis(intervalSec * 1000L)
            .build()

        // Desde Android 14, pedir ubicacion en segundo plano sin acceso puede
        // lanzar SecurityException. No debe tumbar el servicio: se informa y se
        // reintenta cuando la app pase a primer plano (promoteForeground).
        runCatching {
            fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
        }.onFailure { t ->
            Log.w(TAG, "Sin acceso a ubicación ahora mismo (¿segundo plano?): ${t.message}")
        }
        Log.i(TAG, "Updates cada ${intervalSec}s (prioridad=$priority, quieto=$stationary)")
    }

    private fun effectivePriority(): Int = when {
        settings.burstActive() -> Priority.PRIORITY_HIGH_ACCURACY
        settings.adaptiveBattery && battery.isLowPower() -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
        settings.smartTracking && stationary -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
        else -> Priority.PRIORITY_HIGH_ACCURACY
    }

    private fun effectiveIntervalSec(): Int = when {
        // Persecucion: muestreo agresivo, pero SIEMPRE con caducidad por reloj
        settings.burstActive() -> settings.burstIntervalSec
        settings.adaptiveBattery && battery.isLowPower() -> settings.intervalSlowSec
        settings.smartTracking && stationary -> settings.intervalSlowSec
        else -> settings.intervalSec
    }

    private fun handleFix(loc: Location) {
        val b = battery.read()
        val source = sourceOf(loc)
        val burst = settings.burstActive()

        ServiceStateHolder.update {
            it.copy(
                lastLat = loc.latitude,
                lastLon = loc.longitude,
                lastAccuracy = loc.accuracy,
                lastSource = source,
                lastFixAt = System.currentTimeMillis(),
                burst = burst
            )
        }

        evaluateMotion(loc)

        // En persecucion no hay gate: se guarda y se envia todo lo que llegue
        if (!burst && settings.smartTracking && !shouldKeep(loc)) {
            // Estacionario: el fix no aporta, no se guarda ni se gasta radio.
            ServiceStateHolder.update { it.copy(stationary = true) }
            return
        }

        lastKeptLat = loc.latitude
        lastKeptLon = loc.longitude
        lastKeptAt = System.currentTimeMillis()
        // El flag "quieto" lo gobierna evaluateMotion (transiciones), no cada envio
        enqueueAsync(loc, b)
    }

    /**
     * Precision+ (telefonia/WiFi) y persistencia FUERA del hilo de la UI: el
     * callback de ubicacion corre en el hilo principal y recolectar celdas/WiFi
     * ahi producia tirones y riesgo de ANR.
     */
    private fun enqueueAsync(loc: Location, b: BatteryInfo) {
        val withPrecision = settings.precisionPlus
        val snapshot = Location(loc) // copia defensiva: Android puede reutilizar el objeto
        workScope.launch {
            val cellWifi = if (withPrecision) precision.collect() else null
            SyncManager.get(applicationContext).enqueue(entityFrom(snapshot, b, cellWifi))
        }
    }

    /**
     * Decide si el fix merece guardarse y enviarse. Umbral = max(radio
     * configurado, precision del fix): un fix con ±300 m no puede pretender
     * demostrar que el movil se movio 30 m.
     */
    private fun shouldKeep(loc: Location): Boolean {
        if (lastKeptAt == 0L) return true
        val threshold = maxOf(settings.stationaryRadiusM, loc.accuracy)
        val moved = distanceMeters(lastKeptLat, lastKeptLon, loc.latitude, loc.longitude)
        if (moved >= threshold) return true
        val elapsedMs = System.currentTimeMillis() - lastKeptAt
        return elapsedMs >= settings.stationaryKeepAliveSec * 1000L
    }

    /** Detecta la transicion quieto <-> en movimiento y reajusta el request. */
    private fun evaluateMotion(loc: Location) {
        if (lastKeptAt == 0L) return
        val threshold = maxOf(settings.stationaryRadiusM, loc.accuracy)
        val moved = distanceMeters(lastKeptLat, lastKeptLon, loc.latitude, loc.longitude)

        if (moved >= threshold) {
            lowMotionStreak = 0
            if (stationary) {
                stationary = false
                applyLocationRequest()
            }
            return
        }

        lowMotionStreak++
        if (!stationary && lowMotionStreak >= STATIONARY_STREAK) {
            stationary = true
            applyLocationRequest()
        }
    }

    // ------------------------------------------------------------------ comandos remotos

    /**
     * Consulta comandos pendientes cada `commandPollSec` mientras el servicio
     * vive. Silencioso ante fallos (sin red, token revocado): se reintenta solo.
     */
    private fun startCommandPolling() {
        if (!settings.remoteControl) return
        pollScope.launch {
            while (isActive) {
                // Si el rastreo se desactivo (ajustes o mando remoto), el servicio
                // se cierra solo: asi el "parar" siempre llega, aunque el
                // startService de fuera se hubiera bloqueado por limites de fondo.
                if (!settings.trackingEnabled) {
                    Log.i(TAG, "trackingEnabled=false: cerrando el servicio desde el propio bucle")
                    withContext(Dispatchers.Main) { stopTracking() }
                    return@launch
                }
                CommandChannel.get(this@LocationService).pollOnce()
                // Reajusta el request (una persecucion pudo empezar o caducar) y
                // refresca la notificacion si cambio el texto.
                applyLocationRequest()
                refreshForegroundNotification()
                // Vigila que sigan en pie permisos/antirrobo y lo reporta
                TamperCheck.report(this@LocationService)
                delay(commandPollDelaySec() * 1000L)
            }
        }
    }

    private fun commandPollDelaySec(): Long {
        val base = settings.commandPollSec.coerceIn(15, 900).toLong()
        // Durante la persecucion se sondea mas a menudo: interesa recibir el
        // "stop_burst" rapido y reflejar el fin en <=15 s, no en un minuto.
        return if (settings.burstActive()) minOf(base, BURST_POLL_SEC) else base
    }

    // ------------------------------------------------------------------ stop

    private fun stopTracking() {
        runCatching { fused.removeLocationUpdates(callback) }
        settings.trackingEnabled = false
        settings.stopBurst() // parar es parar: sin persecucion latente
        AlarmPlayer.get(this).stop()
        isRunning = false
        setLocationAccess(false)
        ServiceStateHolder.reset()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "Rastreo detenido (acción del usuario o comando remoto)")
    }

    companion object {
        private const val TAG = "LocationService"
        private const val NOTIF_ID = 1001
        private const val STATIONARY_STREAK = 3
        private const val BURST_POLL_SEC = 15L
        private const val PI_FLAGS =
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        /** Detencion interna del servicio (uso de la propia app). */
        const val ACTION_STOP = "com.locator.agent.action.STOP_TRACKING"

        /** Peticion de parada con PIN: la lanza la accion "Detener" de la notificacion. */
        const val ACTION_STOP_REQUEST = "com.locator.agent.action.STOP_REQUEST"
        const val EXTRA_STOP_REQUEST = "stop_request"

        @Volatile
        var isRunning: Boolean = false
            private set

        /**
         * true = el servicio esta en primer plano CON acceso a ubicacion.
         * En Android 14, arrancarlo desde segundo plano (reinicio, watchdog)
         * deja el servicio vivo pero sin GPS hasta que se abre la app; el panel
         * lo denuncia con un aviso en vez de quedarse en silencio.
         */
        @Volatile
        var hasLocationAccess: Boolean = false
            private set

        fun hasLocationPermission(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED ||
                    context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED

        fun sourceOf(loc: Location): String = when (loc.provider) {
            "gps" -> "GPS"
            "network" -> "NETWORK"
            else -> "FUSED"
        }

        /** Mapea un fix al modelo persistible (lo comparten el flujo normal y el one-shot). */
        fun entityFrom(loc: Location, b: BatteryInfo, cellWifi: String?): LocationEntity =
            LocationEntity(
                lat = loc.latitude,
                lon = loc.longitude,
                accuracy = loc.accuracy.toDouble(),
                speed = if (loc.hasSpeed()) loc.speed.toDouble() else null,
                altitude = if (loc.hasAltitude()) loc.altitude else null,
                bearing = if (loc.hasBearing()) loc.bearing.toDouble() else null,
                batteryPct = b.pct,
                charging = b.charging,
                source = sourceOf(loc),
                provider = loc.provider ?: "fused",
                cellWifi = cellWifi,
                recordedAt = loc.time
            )

        /** Distancia en metros (Haversine, sin dependencias extra). */
        fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0
            val toRad = Math.PI / 180.0
            val dLat = (lat2 - lat1) * toRad
            val dLon = (lon2 - lon1) * toRad
            val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                    Math.cos(lat1 * toRad) * Math.cos(lat2 * toRad) *
                    Math.sin(dLon / 2) * Math.sin(dLon / 2)
            return 2 * r * Math.asin(kotlin.math.sqrt(a))
        }

        internal fun setLocationAccess(value: Boolean) {
            hasLocationAccess = value
        }

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, LocationService::class.java))
            } catch (t: Throwable) {
                Log.e(TAG, "No se pudo iniciar el servicio", t)
            }
        }

        /** Parada limpia desde la propia app o desde un comando remoto. */
        fun stop(context: Context) {
            try {
                context.startService(
                    Intent(context, LocationService::class.java).setAction(ACTION_STOP)
                )
            } catch (t: Throwable) {
                Log.w(TAG, "No se pudo detener el servicio", t)
            }
        }

        /**
         * Fix inmediato de alta precision (comando `locate_now` y boton "Ubicar
         * ahora"): no espera al ciclo del servicio ni pasa por el gate de
         * movimiento.
         */
        fun requestOneShot(context: Context, onDone: ((Location?) -> Unit)? = null) {
            val app = context.applicationContext
            if (!hasLocationPermission(app)) {
                Log.w(TAG, "One-shot sin permiso de ubicacion")
                onDone?.invoke(null)
                return
            }
            val client = LocationServices.getFusedLocationProviderClient(app)
            try {
                client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener { loc ->
                        if (loc != null) {
                            val entity = entityFrom(loc, BatteryMonitor(app).read(), null)
                            // urgente: un "ubicar ahora" no puede quedarse esperando
                            // al worker por estar la bateria baja
                            SyncManager.get(app).enqueue(entity, urgent = true)
                        }
                        onDone?.invoke(loc)
                    }
                    .addOnFailureListener { t ->
                        Log.w(TAG, "One-shot fallido: ${t.message}")
                        onDone?.invoke(null)
                    }
            } catch (t: Throwable) {
                Log.w(TAG, "One-shot no permitido", t)
                onDone?.invoke(null)
            }
        }
    }
}
