package com.locator.agent.sync

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import com.locator.agent.data.SettingsRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Postura de seguridad del dispositivo: COMO esta configurado el telefono y que
 * acceso peligroso hay concedido dentro.
 *
 * Que responde: ¿tiene bloqueo de pantalla?, ¿esta el parche de seguridad al
 * dia?, ¿hay root?, ¿estan activadas las opciones de desarrollador o la
 * depuracion USB?, ¿esta Play Protect encendido?, ¿que apps pueden leer las
 * notificaciones, usar accesibilidad o administrar el dispositivo?
 *
 * Que sube ademas, para control parental: los NOMBRES de las aplicaciones
 * instaladas con icono en el lanzador (juegos, redes, mensajeria) y su numero,
 * para que el adulto responsable decida si tiene que hablar con el menor.
 *
 * Que NO sube, a proposito: tiempos de uso, horas de apertura, numero de veces
 * que se abre cada app ni ningun patron temporal. Tampoco mensajes, contactos,
 * fotos ni capturas. El nombre dice "que tiene"; el uso diria "como vive", y
 * solo lo primero es control parental. Se puede desactivar en el propio
 * telefono con el interruptor "Compartir la lista de apps".
 *
 * Lectura pasiva: nada de esto requiere permisos especiales; se lee lo que
 * Android deja leer a cualquier app.
 */
data class Posture(
    val developerOptions: Boolean,
    val usbDebugging: Boolean,
    val playProtect: Boolean,
    val deviceSecure: Boolean,
    val rooted: Boolean,
    val installSource: String?,
    val accessibilityApps: List<String>,
    val notificationListeners: List<String>,
    val deviceAdmins: List<String>,
    val alerts: List<String>,
    /** Control parental: apps instaladas con icono propio (solo nombres). */
    val installedTotal: Int,
    val installedApps: List<AppEntry>,
    /** false = el dueño del telefono apago el envio de la lista. */
    val appListShared: Boolean
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("developer_options", developerOptions)
        put("usb_debugging", usbDebugging)
        put("play_protect", playProtect)
        put("device_secure", deviceSecure)
        put("rooted", rooted)
        installSource?.let { put("install_source", it) }
        put("android_version", Build.VERSION.RELEASE ?: "")
        put("security_patch", Build.VERSION.SECURITY_PATCH ?: "")
        put("accessibility_apps", JSONArray(accessibilityApps))
        put("notification_listeners", JSONArray(notificationListeners))
        put("device_admins", JSONArray(deviceAdmins))
        put("app_list_shared", appListShared)
        put("installed_count", installedTotal)
        put("installed_apps", InstalledApps.toJson(installedApps))
    }
}

object SecurityPosture {

    /** Un parche mas viejo que esto se marca como pendiente. */
    private const val PATCH_MAX_AGE_DAYS = 400L

    /**
     * @param includeApps false para la pantalla del telefono (resumen rapido):
     *   listar apps recorre el PackageManager y no aporta nada al resumen, asi
     *   que no se paga ese coste en el hilo de la interfaz.
     */
    fun inspect(context: Context, includeApps: Boolean = true): Posture {
        val app = context.applicationContext
        val resolver = app.contentResolver

        val devOptions = globalFlag(resolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED)
        val usbDebug = globalFlag(resolver, Settings.Global.ADB_ENABLED)
        // Play Protect: el verificador de paquetes (por defecto activado)
        val playProtect = globalFlag(resolver, "package_verifier_enable", default = true)
        val deviceSecure = runCatching {
            (app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure
        }.getOrDefault(false)

        val rooted = detectRoot()
        val installSource = installSource(app)
        val accessibility = accessibilityApps(app)
        val listeners = notificationListeners(app)
        val admins = deviceAdmins(app)
        // La lista de apps solo se sube si el ajuste del propio telefono lo
        // permite (viene activado, y el interruptor esta a la vista).
        val shareApps = includeApps && SettingsRepository.get(app).shareAppList
        val apps = if (shareApps) {
            InstalledApps.snapshot(app)
        } else {
            InstalledApps.AppList(0, emptyList())
        }

        val alerts = ArrayList<String>(5)
        if (!deviceSecure) alerts.add("sin bloqueo de pantalla (cualquiera puede parar el rastreo)")
        if (rooted) alerts.add("dispositivo con root")
        if (usbDebug) alerts.add("depuración USB activada")
        if (devOptions && !usbDebug) alerts.add("opciones de desarrollador activadas")
        if (!playProtect) alerts.add("Play Protect desactivado")
        if (accessibility.isNotEmpty()) {
            alerts.add(accessibility.size.toString() + " app(s) con accesibilidad: " +
                    accessibility.joinToString(", "))
        }
        if (listeners.isNotEmpty()) {
            alerts.add(listeners.size.toString() + " app(s) que leen las notificaciones: " +
                    listeners.joinToString(", "))
        }
        patchAlert()?.let { alerts.add(it) }

        return Posture(
            developerOptions = devOptions,
            usbDebugging = usbDebug,
            playProtect = playProtect,
            deviceSecure = deviceSecure,
            rooted = rooted,
            installSource = installSource,
            accessibilityApps = accessibility,
            notificationListeners = listeners,
            deviceAdmins = admins,
            alerts = alerts,
            installedTotal = apps.total,
            installedApps = apps.apps,
            appListShared = shareApps
        )
    }

    // ------------------------------------------------------------------ lectura

    private fun globalFlag(
        resolver: android.content.ContentResolver,
        key: String,
        default: Boolean = false
    ): Boolean = runCatching {
        Settings.Global.getInt(resolver, key, if (default) 1 else 0) == 1
    }.getOrDefault(default)

    /** Root: binarios su/rw en rutas tipicas o kernel de test. */
    private fun detectRoot(): Boolean {
        if (Build.TAGS?.contains("test-keys") == true) return true
        val paths = arrayOf(
            "/system/app/Superuser.apk", "/sbin/su", "/system/bin/su", "/system/xbin/su",
            "/data/local/xbin/su", "/data/local/bin/su", "/system/sd/xbin/su",
            "/system/bin/failsafe/su", "/data/local/su", "/su/bin/su"
        )
        return paths.any { runCatching { File(it).exists() }.getOrDefault(false) }
    }

    private fun installSource(context: Context): String? = runCatching {
        val pm = context.packageManager
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(context.packageName)
        }
        when {
            installer == null -> "desconocido"
            installer.contains("vending") || installer.contains("play") -> "play"
            else -> installer
        }
    }.getOrNull()

    /** Apps con servicio de accesibilidad activo: el vector clasico de keyloggers. */
    private fun accessibilityApps(context: Context): List<String> = runCatching {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .mapNotNull { it.resolveInfo?.serviceInfo?.packageName }
            .filter { it != context.packageName && !isOwnOrSystem(it, context) }
            .distinct()
    }.getOrDefault(emptyList())

    /** Apps que pueden leer todas las notificaciones. */
    private fun notificationListeners(context: Context): List<String> = runCatching {
        // getEnabledListenerPackages vive en NotificationManagerCompat (androidx),
        // no en NotificationManager: la API de la plataforma no lo expone.
        NotificationManagerCompat.getEnabledListenerPackages(context)
            .filter { it != context.packageName && !isOwnOrSystem(it, context) }
            .distinct()
    }.getOrDefault(emptyList())

    /** Administradores de dispositivo activos (pueden bloquear o borrar). */
    private fun deviceAdmins(context: Context): List<String> = runCatching {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        dpm.activeAdmins?.map { it.packageName }?.distinct().orEmpty()
    }.getOrDefault(emptyList())

    /** Se descartan los paquetes del propio sistema para no llenar la lista de ruido. */
    private fun isOwnOrSystem(pkg: String, context: Context): Boolean {
        if (pkg.startsWith("com.android.") || pkg.startsWith("android")) return true
        return runCatching {
            val info = context.packageManager.getApplicationInfo(pkg, 0)
            (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
        }.getOrDefault(false)
    }

    /** Parche de seguridad del sistema: si esta caducado, se avisa. */
    private fun patchAlert(): String? {
        val patch = Build.VERSION.SECURITY_PATCH ?: return null
        if (patch.isBlank()) return null
        val date = runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(patch)
        }.getOrNull() ?: return null
        val ageDays = TimeUnit.DAYS.convert(
            System.currentTimeMillis() - date.time,
            TimeUnit.MILLISECONDS
        )
        return if (ageDays > PATCH_MAX_AGE_DAYS) {
            "parche de seguridad antiguo ($patch)"
        } else null
    }
}
