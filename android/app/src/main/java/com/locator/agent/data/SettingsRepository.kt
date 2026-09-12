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

        @Volatile private var instance: SettingsRepository? = null

        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
    }
}
