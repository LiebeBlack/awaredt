package com.locator.agent.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PIN del propietario del dispositivo.
 *
 * Que protege realmente: las acciones LOCALES del agente (detener el rastreo,
 * cambiar la configuracion, desactivar el modo antirrobo, desvincular el
 * dispositivo). No sustituye al login del panel: el historial lo protege
 * Supabase Auth + RLS.
 *
 * Como se guarda:
 *  - Solo un hash PBKDF2-HMAC-SHA256 (210.000 iteraciones) con salt aleatorio
 *    de 128 bits. El PIN en claro no se escribe nunca, jamas se guarda el
 *    original y no es recuperable: si se olvida, se reinstala el agente.
 *  - La comparacion es en tiempo constante (MessageDigest.isEqual).
 *  - Bloqueo incremental: 5 fallos -> 30 s; cada fallo extra duplica la espera
 *    hasta un maximo de 30 minutos. El contador vive en disco: cerrar la app
 *    no lo reinicia.
 *
 * LIMITE HONESTO: un PIN local de 4-8 digitos sobre un dispositivo que el
 * atacante controla fisicamente no es criptografia fuerte. Es una barrera
 * practica (que un ladron no apague el rastreo de un toque), no una garantia.
 */
class PinStore(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (_: Exception) {
        // Degradacion: sin cifrado la app sigue funcionando (mismo criterio que SettingsRepository)
        context.getSharedPreferences(PREFS_NAME + "_fallback", Context.MODE_PRIVATE)
    }

    val isSet: Boolean
        get() = !prefs.getString(KEY_HASH, null).isNullOrEmpty()

    /** Milisegundos que faltan para poder volver a intentar (0 = libre). */
    fun lockoutRemainingMs(): Long {
        val until = prefs.getLong(KEY_LOCK_UNTIL, 0L)
        val remaining = until - System.currentTimeMillis()
        return if (remaining > 0) remaining else 0L
    }

    val failedAttempts: Int
        get() = prefs.getInt(KEY_FAILED, 0)

    /** Define (o reemplaza) el PIN. Exige que ya se haya verificado el anterior si existe. */
    fun setPin(pin: String) {
        require(isValidFormat(pin)) { "El PIN debe tener entre 4 y 8 digitos" }
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val hash = derive(pin, salt, ITERATIONS)
        prefs.edit()
            .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .putInt(KEY_ITERATIONS, ITERATIONS)
            .putInt(KEY_FAILED, 0)
            .putLong(KEY_LOCK_UNTIL, 0L)
            .apply()
    }

    /**
     * Verifica el PIN. Devuelve false si es incorrecto O si hay bloqueo activo
     * (comprueba [lockoutRemainingMs] antes de llamar para distinguirlos).
     */
    fun verify(pin: String): Boolean {
        if (lockoutRemainingMs() > 0) return false
        val saltB64 = prefs.getString(KEY_SALT, null) ?: return false
        val hashB64 = prefs.getString(KEY_HASH, null) ?: return false
        val iterations = prefs.getInt(KEY_ITERATIONS, ITERATIONS)

        val salt = Base64.decode(saltB64, Base64.NO_WRAP)
        val expected = Base64.decode(hashB64, Base64.NO_WRAP)
        val candidate = derive(pin, salt, iterations.coerceAtLeast(1))

        val ok = MessageDigest.isEqual(expected, candidate)
        if (ok) {
            prefs.edit().putInt(KEY_FAILED, 0).putLong(KEY_LOCK_UNTIL, 0L).apply()
        } else {
            registerFailure()
        }
        return ok
    }

    /** Borra el PIN (solo tras verificarlo). Deja el agente sin proteccion local. */
    fun clear() {
        prefs.edit()
            .remove(KEY_HASH).remove(KEY_SALT).remove(KEY_ITERATIONS)
            .putInt(KEY_FAILED, 0).putLong(KEY_LOCK_UNTIL, 0L)
            .apply()
    }

    private fun registerFailure() {
        val failed = failedAttempts + 1
        val waitMs = lockoutFor(failed)
        prefs.edit()
            .putInt(KEY_FAILED, failed.coerceAtMost(50))
            .putLong(KEY_LOCK_UNTIL, if (waitMs > 0) System.currentTimeMillis() + waitMs else 0L)
            .apply()
    }

    /** 5 fallos -> 30 s, 6 -> 60 s, 7 -> 120 s ... tope 30 min. */
    private fun lockoutFor(failed: Int): Long {
        if (failed < FREE_ATTEMPTS) return 0L
        val steps = (failed - FREE_ATTEMPTS).coerceAtMost(6)
        return (BASE_LOCKOUT_MS shl steps).coerceAtMost(MAX_LOCKOUT_MS)
    }

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    companion object {
        private const val PREFS_NAME = "agent_secure"
        private const val KEY_HASH = "pin_hash"
        private const val KEY_SALT = "pin_salt"
        private const val KEY_ITERATIONS = "pin_iterations"
        private const val KEY_FAILED = "pin_failed"
        private const val KEY_LOCK_UNTIL = "pin_lock_until"

        private const val ITERATIONS = 210_000
        private const val SALT_BYTES = 16
        private const val KEY_BITS = 256
        private const val FREE_ATTEMPTS = 5
        private const val BASE_LOCKOUT_MS = 30_000L
        private const val MAX_LOCKOUT_MS = 30 * 60 * 1000L

        const val MIN_LEN = 4
        const val MAX_LEN = 8

        /** Formato aceptado: solo digitos, entre 4 y 8. */
        fun isValidFormat(pin: String): Boolean =
            pin.length in MIN_LEN..MAX_LEN && pin.all { it.isDigit() }

        @Volatile private var instance: PinStore? = null

        fun get(context: Context): PinStore =
            instance ?: synchronized(this) {
                instance ?: PinStore(context.applicationContext).also { instance = it }
            }
    }
}
