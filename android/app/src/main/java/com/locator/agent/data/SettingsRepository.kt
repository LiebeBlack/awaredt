package com.locator.agent.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Preferencias cifradas del agente (incluye el token de emparejamiento).
 * Todas las claves se exponen como constantes para usarlas en las pantallas.
 */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "agent_settings",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (_: Exception) {
        // Fallback degradation: sin cifrado nunca se rompe la app
        context.getSharedPreferences("agent_settings_fallback", Context.MODE_PRIVATE)
    }

    var trackingEnabled: Boolean
        get() = prefs.getBoolean(KEY_TRACKING_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_TRACKING_ENABLED, v).apply()

    var deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, "") ?: ""
        set(v) = prefs.edit().putString(KEY_DEVICE_ID, v.trim()).apply()

    var deviceToken: String
        get() = prefs.getString(KEY_DEVICE_TOKEN, "") ?: ""
        set(v) = prefs.edit().putString(KEY_DEVICE_TOKEN, v.trim()).apply()

    var supabaseUrl: String
        get() = prefs.getString(KEY_SUPABASE_URL, "") ?: ""
        set(v) = prefs.edit().putString(KEY_SUPABASE_URL, v.trim().trimEnd('/')).apply()

    var supabaseKey: String
        get() = prefs.getString(KEY_SUPABASE_KEY, "") ?: ""
        set(v) = prefs.edit().putString(KEY_SUPABASE_KEY, v.trim()).apply()

    var intervalSec: Int
        get() = prefs.getInt(KEY_INTERVAL_SEC, 5)
        set(v) = prefs.edit().putInt(KEY_INTERVAL_SEC, v.coerceIn(5, 300)).apply()

    var intervalSlowSec: Int
        get() = prefs.getInt(KEY_INTERVAL_SLOW_SEC, 60)
        set(v) = prefs.edit().putInt(KEY_INTERVAL_SLOW_SEC, v.coerceIn(15, 900)).apply()

    var precisionPlus: Boolean
        get() = prefs.getBoolean(KEY_PRECISION_PLUS, false)
        set(v) = prefs.edit().putBoolean(KEY_PRECISION_PLUS, v).apply()

    var adaptiveBattery: Boolean
        get() = prefs.getBoolean(KEY_ADAPTIVE_BATTERY, true)
        set(v) = prefs.edit().putBoolean(KEY_ADAPTIVE_BATTERY, v).apply()

    /** Notificacion discreta: minima pero SIEMPRE visible (requisito del sistema). */
    var discreetNotif: Boolean
        get() = prefs.getBoolean(KEY_DISCREET_NOTIF, false)
        set(v) = prefs.edit().putBoolean(KEY_DISCREET_NOTIF, v).apply()

    /**
     * Tema de la interfaz: 0 = sistema (DayNight), 1 = claro, 2 = oscuro.
     * Se aplica en onCreate ANTES de setContentView y sobrevive al reinicio.
     */
    var themeMode: Int
        get() = prefs.getInt(KEY_THEME_MODE, 0)
        set(v) = prefs.edit().putInt(KEY_THEME_MODE, v.coerceIn(0, 2)).apply()
    /**
     * El dueño activo el modo antirrobo (DeviceAdmin). Sirve para DETECTAR que
     * alguien lo ha desactivado despues: el hecho de que pase de true a false
     * es la senal de manipulacion mas fiable que existe en Android.
     */
    var antiTheftEnabled: Boolean
        get() = prefs.getBoolean(KEY_ANTI_THEFT, false)
        set(v) = prefs.edit().putBoolean(KEY_ANTI_THEFT, v).apply()

    /**
     * Compartir con la familia la LISTA DE APPS instaladas (solo nombres).
     * No incluye tiempos de uso ni horarios, y el telefono muestra el contador
     * en su propia pantalla: el control parental aqui es visible, no encubierto.
     */
    var shareAppList: Boolean
        get() = prefs.getBoolean(KEY_SHARE_APP_LIST, true)
        set(v) = prefs.edit().putBoolean(KEY_SHARE_APP_LIST, v).apply()

    /** Control remoto desde admin.html (ubicar, bloquear, alarma, detener). */
    var remoteControl: Boolean
        get() = prefs.getBoolean(KEY_REMOTE_CONTROL, true)
        set(v) = prefs.edit().putBoolean(KEY_REMOTE_CONTROL, v).apply()

    /** Cada cuanto consulta el agente si hay comandos pendientes (segundos). */
    var commandPollSec: Int
        get() = prefs.getInt(KEY_COMMAND_POLL_SEC, 60)
        set(v) = prefs.edit().putInt(KEY_COMMAND_POLL_SEC, v.coerceIn(15, 900)).apply()

    /**
     * Rastreo inteligente: si el dispositivo no se mueve, se deja de gastar
     * radio y GPS y solo se envia un "sigo vivo" cada [stationaryKeepAliveSec].
     */
    var smartTracking: Boolean
        get() = prefs.getBoolean(KEY_SMART_TRACKING, true)
        set(v) = prefs.edit().putBoolean(KEY_SMART_TRACKING, v).apply()

    /** Radio (metros) por debajo del cual se considera que el movil no se movio. */
    var stationaryRadiusM: Float
        get() = prefs.getFloat(KEY_STATIONARY_RADIUS_M, 30f)
        set(v) = prefs.edit().putFloat(KEY_STATIONARY_RADIUS_M, v.coerceIn(5f, 500f)).apply()

    /** Cada cuanto se envia una posicion estando quieto (segundos). */
    var stationaryKeepAliveSec: Int
        get() = prefs.getInt(KEY_STATIONARY_KEEPALIVE_SEC, 300)
        set(v) = prefs.edit().putInt(KEY_STATIONARY_KEEPALIVE_SEC, v.coerceIn(60, 3600)).apply()

    // ---------------------------------------------------------- persecucion

    /**
     * Modo persecucion: hasta este instante (epoch millis) se muestrea de forma
     * agresiva (cada [burstIntervalSec], alta precision, sin gate de movimiento).
     * Es SIEMPRE temporal: si el proceso muere, expira solo por reloj. Nunca
     * cambia la visibilidad (la notificacion sigue ahi) ni reinicia un rastreo
     * que el dueño haya detenido.
     */
    var burstUntilMs: Long
        get() = prefs.getLong(KEY_BURST_UNTIL, 0L)
        set(v) = prefs.edit().putLong(KEY_BURST_UNTIL, v).apply()

    var burstIntervalSec: Int
        get() = prefs.getInt(KEY_BURST_INTERVAL_SEC, 3)
        set(v) = prefs.edit().putInt(KEY_BURST_INTERVAL_SEC, v.coerceIn(BURST_MIN_INTERVAL, 15)).apply()

    fun burstActive(now: Long = System.currentTimeMillis()): Boolean = burstUntilMs > now

    fun burstRemainingSec(now: Long = System.currentTimeMillis()): Long =
        ((burstUntilMs - now) / 1000).coerceAtLeast(0L)

    /** Activa la persecucion [minutes] minutos. Devuelve la marca de fin. */
    fun startBurst(minutes: Int, intervalSec: Int): Long {
        burstIntervalSec = intervalSec
        burstUntilMs = System.currentTimeMillis() + minutes.coerceIn(1, BURST_MAX_MINUTES) * 60_000L
        return burstUntilMs
    }

    fun stopBurst() {
        burstUntilMs = 0L
    }

    /**
     * Registro de la divulgacion aceptada (epoch millis, 0 = no aceptada).
     * No es una barrera tecnica: es la prueba de que el usuario del telefono
     * vio el aviso antes de que el agente empezara a reportar.
     */
    var consentAcceptedAt: Long
        get() = prefs.getLong(KEY_CONSENT_AT, 0L)
        set(v) = prefs.edit().putLong(KEY_CONSENT_AT, v).apply()

    var consentVersion: Int
        get() = prefs.getInt(KEY_CONSENT_VERSION, 0)
        set(v) = prefs.edit().putInt(KEY_CONSENT_VERSION, v).apply()

    /** Borra el emparejamiento local (el token y el uuid). No toca el servidor. */
    fun clearPairing() {
        prefs.edit().remove(KEY_DEVICE_ID).remove(KEY_DEVICE_TOKEN).apply()
    }

    val pairingComplete: Boolean
        get() = deviceId.isNotBlank() && deviceToken.isNotBlank() &&
                supabaseUrl.startsWith("https://") && supabaseKey.isNotBlank()

    companion object {
        const val KEY_TRACKING_ENABLED = "tracking_enabled"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_DEVICE_TOKEN = "device_token"
        const val KEY_SUPABASE_URL = "supabase_url"
        const val KEY_SUPABASE_KEY = "supabase_anon_key"
        const val KEY_INTERVAL_SEC = "interval_sec"
        const val KEY_INTERVAL_SLOW_SEC = "interval_slow_sec"
        const val KEY_PRECISION_PLUS = "precision_plus"
        const val KEY_ADAPTIVE_BATTERY = "adaptive_battery"
        const val KEY_DISCREET_NOTIF = "discreet_notif"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_ANTI_THEFT = "anti_theft_enabled"
        const val KEY_SHARE_APP_LIST = "share_app_list"
        const val KEY_REMOTE_CONTROL = "remote_control"
        const val KEY_COMMAND_POLL_SEC = "command_poll_sec"
        const val KEY_SMART_TRACKING = "smart_tracking"
        const val KEY_STATIONARY_RADIUS_M = "stationary_radius_m"
        const val KEY_STATIONARY_KEEPALIVE_SEC = "stationary_keepalive_sec"
        const val KEY_CONSENT_AT = "consent_accepted_at"
        const val KEY_CONSENT_VERSION = "consent_version"
        const val KEY_BURST_UNTIL = "burst_until_ms"
        const val KEY_BURST_INTERVAL_SEC = "burst_interval_sec"

        /** Tope duro de persecucion: 30 minutos. No hay modo "para siempre". */
        const val BURST_MAX_MINUTES = 30
        const val BURST_MIN_INTERVAL = 2

        /** Version actual del aviso de divulgacion que ve el usuario del telefono. */
        const val CONSENT_VERSION = 1

        @Volatile private var instance: SettingsRepository? = null

        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
    }
}
