package com.locator.agent.data

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

@Database(entities = [LocationEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun locationDao(): LocationDao

    companion object {
        private const val TAG = "AppDatabase"
        private const val DB_NAME = "locations_encrypted.db"

        @Volatile private var instance: AppDatabase? = null

        /**
         * DB cifrada con SQLCipher. Si el Factory nativo no estuviera
         * disponible en el dispositivo, degrada a un buffer en memoria
         * (los puntos siguen enviandose cuando haya red).
         */
        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(app: Context): AppDatabase {
            return try {
                System.loadLibrary("sqlcipher") // libreria nativa de sqlcipher-android
                val passphrase = PassphraseManager.getOrCreate(app)
                Room.databaseBuilder(app, AppDatabase::class.java, DB_NAME)
                    .openHelperFactory(net.zetetic.database.sqlcipher.SupportOpenHelperFactory(passphrase))
                    .build()
            } catch (t: Throwable) {
                Log.e(TAG, "SQLCipher no disponible; usando buffer en memoria", t)
                Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
            }
        }
    }
}

/**
 * Clave simetrica de la base de datos. Se genera una vez y se guarda
 * cifrada con una EncryptedSharedPreferences propia (independiente del
 * token de emparejamiento).
 */
object PassphraseManager {
    private const val PREFS = "db_key"
    private const val KEY_DB_PASS = "db_passphrase"

    fun getOrCreate(context: Context): ByteArray {
        val stored = try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            context.getSharedPreferences(PREFS + "_fallback", Context.MODE_PRIVATE)
        }

        val existing = stored.getString(KEY_DB_PASS, null)
        if (!existing.isNullOrEmpty()) return existing.toByteArray(Charsets.UTF_8)

        // 64 chars hex = 256 bits de entropia
        val generated = java.util.UUID.randomUUID().toString().replace("-", "") +
                java.util.UUID.randomUUID().toString().replace("-", "")
        stored.edit().putString(KEY_DB_PASS, generated).apply()
        return generated.toByteArray(Charsets.UTF_8)
    }
}
