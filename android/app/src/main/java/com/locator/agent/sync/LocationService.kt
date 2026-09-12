package com.locator.agent.sync

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
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

/**
 * Servicio en primer plano de ubicacion (Android 8 a 14).
 *
 * - FusedLocationProviderClient con PRIORITY_HIGH_ACCURACY: fusiona GPS,
 *   celdas y WiFi en un solo flujo de fixes.
 * - Notificacion persistente con accion "Detener" (requisito del sistema;
 *   modo discreto = prioridad minima, nunca oculta).
 * - START_STICKY + watchdog (AlarmManager) + reinicio en onDestroy.
 * - Cada fix se persiste en el buffer cifrado y dispara el envio en tiempo
 *   real; si no hay red, SyncManager reintenta con backoff.
 */
class LocationService : LifecycleService() {

    private lateinit var settings: SettingsRepository
    private lateinit var fused: FusedLocationProviderClient
    private lateinit var precision: PrecisionCollector
    private lateinit var battery: BatteryMonitor
    private var currentIntervalSec: Int = 0

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

        startInForeground()
        isRunning = true
        ServiceStateHolder.update { it.copy(running = true) }

        SyncWorker.schedule(this)
        WatchdogReceiver.schedule(this)
        requestLocationUpdates()
        Log.i(TAG, "Servicio de rastreo iniciado")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopTracking()
            return START_NOT_STICKY
        }
        // Si el usuario cambio el intervalo en Ajustes, se reaplica
        val wanted = effectiveIntervalSec()
        if (currentIntervalSec != wanted) restartUpdates()
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { fused.removeLocationUpdates(callback) }
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

    private fun startInForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PI_FLAGS
        )
        val stop = PendingIntent.getBroadcast(
            this, 1,
            Intent(ACTION_STOP).setPackage(packageName),
            PI_FLAGS
        )
        val discreet = settings.discreetNotif

        return NotificationCompat.Builder(this, LocAgentApp.CHANNEL_TRACKING)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.notif_tracking_title))
            .setContentText(
                if (discreet) "Modo discreto activo"
                else getString(R.string.notif_tracking_text)
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setPriority(if (discreet) NotificationCompat.PRIORITY_MIN else NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.notif_action_stop), stop)
            .build()
    }

    // ------------------------------------------------------------------ fixes

    private fun requestLocationUpdates() {
        if (!hasLocationPermission()) {
            Log.w(TAG, "Sin permiso de ubicacion; a la espera de que el usuario lo conceda")
            return
        }
        val intervalSec = effectiveIntervalSec()
        currentIntervalSec = intervalSec

        val priority =
            if (settings.adaptiveBattery && battery.isLowPower()) Priority.PRIORITY_BALANCED_POWER_ACCURACY
            else Priority.PRIORITY_HIGH_ACCURACY

        val request = LocationRequest.Builder(priority, intervalSec * 1000L)
            .setWaitForAccurateLocation(false)
            .setMinUpdateDistanceMeters(1f)
            .build()

        fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
        Log.i(TAG, "Updates cada ${intervalSec}s (prioridad=$priority)")
    }

    private fun restartUpdates() {
        runCatching { fused.removeLocationUpdates(callback) }
        requestLocationUpdates()
    }

    private fun effectiveIntervalSec(): Int =
        if (settings.adaptiveBattery && battery.isLowPower()) settings.intervalSlowSec
        else settings.intervalSec

    private fun handleFix(loc: android.location.Location) {
        val b = battery.read()
        val source = when (loc.provider) {
            "gps" -> "GPS"
            "network" -> "NETWORK"
            else -> "FUSED"
        }
        val entity = LocationEntity(
            lat = loc.latitude,
            lon = loc.longitude,
            accuracy = loc.accuracy.toDouble(),
            speed = if (loc.hasSpeed()) loc.speed.toDouble() else null,
            altitude = if (loc.hasAltitude()) loc.altitude else null,
            bearing = if (loc.hasBearing()) loc.bearing.toDouble() else null,
            batteryPct = b.pct,
            charging = b.charging,
            source = source,
            provider = loc.provider,
            cellWifi = if (settings.precisionPlus) precision.collect() else null,
            recordedAt = loc.time
        )
        ServiceStateHolder.update {
            it.copy(
                lastLat = loc.latitude, lastLon = loc.longitude,
                lastAccuracy = loc.accuracy, lastSource = source,
                lastFixAt = System.currentTimeMillis()
            )
        }
        SyncManager.get(applicationContext).enqueue(entity)
    }

    // ------------------------------------------------------------------ stop

    private fun stopTracking() {
        runCatching { fused.removeLocationUpdates(callback) }
        settings.trackingEnabled = false
        isRunning = false
        ServiceStateHolder.reset()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "Rastreo detenido por el usuario")
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "LocationService"
        private const val NOTIF_ID = 1001
        private const val PI_FLAGS =
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        const val ACTION_STOP = "com.locator.agent.action.STOP_TRACKING"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, LocationService::class.java))
            } catch (t: Throwable) {
                Log.e(TAG, "No se pudo iniciar el servicio", t)
            }
        }
    }
}
