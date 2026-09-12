package com.locator.agent.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.locator.agent.admin.DeviceAdmin
import com.locator.agent.data.SettingsRepository
import org.json.JSONArray
import org.json.JSONObject

/**
 * Comprobacion de manipulacion (tamper).
 *
 * Que hace: mira si siguen en pie las condiciones que permiten rastrear y sube
 * el resultado al panel. Convierte "me lo han desactivado" de silencio en un
 * aviso con hora y motivo.
 *
 * Que NO hace, a proposito: no impide la desactivacion, no se resucita solo
 * cuando el dueño ha parado el rastreo y no oculta nada. No se puede impedir
 * que alguien con el telefono desbloqueado en la mano toque lo que quiera; lo
 * que SI se puede es que no lo haga sin que te enteres.
 */
data class TamperReport(
    val appAlive: Boolean,
    val trackingOn: Boolean,
    val locationOk: Boolean,
    val backgroundOk: Boolean,
    val notificationsOk: Boolean,
    val adminActive: Boolean,
    val antiTheftOn: Boolean,
    val adminRemoved: Boolean,
    val batteryPct: Int?,
    val charging: Boolean?,
    val alerts: List<String>,
    val checkedAt: Long,
    /** Como esta configurado el telefono y que accesos peligrosos tiene dentro. */
    val posture: Posture
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("app_alive", appAlive)
        put("tracking_on", trackingOn)
        put("location_ok", locationOk)
        put("background_ok", backgroundOk)
        put("notifications_ok", notificationsOk)
        put("admin_active", adminActive)
        put("anti_theft_on", antiTheftOn)
        put("admin_removed", adminRemoved)
        batteryPct?.let { put("battery_pct", it) }
        charging?.let { put("charging", it) }
        put("alerts", JSONArray(alerts))
        // Campos de postura: van planos en el mismo informe
        val p = posture.toJson()
        p.keys().forEach { key -> put(key, p.get(key)) }
    }

    val hasTamper: Boolean
        get() = alerts.isNotEmpty()
}

object TamperCheck {

    private const val TAG = "TamperCheck"

    /** Fotografia del estado del agente, sin tocar nada. */
    fun inspect(context: Context): TamperReport {
        val app = context.applicationContext
        val settings = SettingsRepository.get(app)

        val locationOk = granted(app, Manifest.permission.ACCESS_FINE_LOCATION) ||
                granted(app, Manifest.permission.ACCESS_COARSE_LOCATION)
        val backgroundOk = Build.VERSION.SDK_INT < 29 ||
                granted(app, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val notificationsOk = Build.VERSION.SDK_INT < 33 ||
                granted(app, Manifest.permission.POST_NOTIFICATIONS)

        val adminActive = DeviceAdmin.isActive(app)
        val antiTheftOn = settings.antiTheftEnabled
        val trackingOn = settings.trackingEnabled
        val serviceUp = LocationService.isRunning

        val b = BatteryMonitor(app).read()
        val posture = SecurityPosture.inspect(app)

        val alerts = ArrayList<String>(8)
        if (antiTheftOn && !adminActive) {
            // La senal mas fuerte: el dueño lo activo y ya no esta.
            alerts.add("modo antirrobo desactivado")
        }
        if (!locationOk) alerts.add("permiso de ubicación revocado")
        if (locationOk && !backgroundOk) alerts.add("ubicación en segundo plano revocada")
        if (!notificationsOk) alerts.add("notificaciones desactivadas")
        if (trackingOn && !serviceUp) alerts.add("el servicio de rastreo no está corriendo")
        // Android 14: tras un reinicio, el sistema no deja dar al servicio el tipo
        // `location` desde segundo plano hasta que hay una pantalla visible. Aquí
        // se ve el caso "vivo pero sin GPS" en vez de parecer que todo va bien.
        if (trackingOn && serviceUp && LocationService.isRunning &&
            !LocationService.hasLocationAccess
        ) {
            alerts.add("rastreo sin acceso a ubicación: abre la app una vez")
        }
        if (!trackingOn) alerts.add("rastreo detenido a mano")
        // Riesgos de configuracion del propio dispositivo (postura)
        alerts.addAll(posture.alerts)

        return TamperReport(
            appAlive = true,
            trackingOn = trackingOn,
            locationOk = locationOk,
            backgroundOk = backgroundOk,
            notificationsOk = notificationsOk,
            adminActive = adminActive,
            antiTheftOn = antiTheftOn,
            adminRemoved = antiTheftOn && !adminActive,
            batteryPct = b.pct,
            charging = b.charging,
            alerts = alerts,
            checkedAt = System.currentTimeMillis(),
            posture = posture
        )
    }

    /**
     * Comprueba y sube el estado. Silencioso ante fallos de red: la proxima
     * comprobacion (worker periodico, arranque, apertura de la app) lo reintenta.
     * Devuelve el informe para que la UI pueda mostrarlo.
     */
    suspend fun report(context: Context): TamperReport {
        val report = inspect(context)
        val settings = SettingsRepository.get(context)
        if (!settings.pairingComplete) return report

        try {
            SupabaseClient(settings).reportHealth(report)
            if (report.hasTamper) {
                Log.w(TAG, "Manipulación detectada: " + report.alerts.joinToString(", "))
            }
        } catch (e: Exception) {
            Log.d(TAG, "No se pudo subir el estado: ${e.message}")
        }
        return report
    }

    private fun granted(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Comprobacion rapida desde la UI (sin red): solo el resumen para pantalla. */
    fun summary(context: Context): String {
        // Sin listar apps: esto corre en el hilo de la interfaz y el resumen no
        // las muestra. El informe completo (que si las lleva) va por red.
        val r = inspect(context, includeApps = false)
        val parts = ArrayList<String>(6)
        parts.add(if (r.locationOk) "permisos OK" else "SIN permiso de ubicación")
        if (!r.backgroundOk) parts.add("sin segundo plano")
        parts.add(if (r.antiTheftOn && r.adminActive) "antirrobo activo"
        else if (r.adminRemoved) "ANTIRROBO DESACTIVADO" else "antirrobo inactivo")
        if (!r.notificationsOk) parts.add("sin notificaciones")

        // Postura del dispositivo, en corto
        val p = r.posture
        parts.add(if (p.deviceSecure) "con bloqueo" else "SIN bloqueo")
        if (p.accessibilityApps.isNotEmpty()) parts.add(p.accessibilityApps.size.toString() + " app(s) con accesibilidad")
        if (p.notificationListeners.isNotEmpty()) parts.add(p.notificationListeners.size.toString() + " leyendo notificaciones")
        if (p.rooted) parts.add("con root")
        return parts.joinToString(" · ")
    }
}
