package com.locator.agent.sync

import com.locator.agent.data.SettingsRepository
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Cliente HTTP hacia Supabase. Envia lotes de posiciones al RPC
 * [ingest_positions] firmando con el token del dispositivo.
 */
class SupabaseClient(private val settings: SettingsRepository) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)   // reusa conexiones HTTP/2 vivas
        .connectionPool(okhttp3.ConnectionPool(4, 5, TimeUnit.MINUTES))
        .build()

    private val json = "application/json; charset=utf-8".toMediaType()

    /**
     * Envía un lote. Devuelve el numero de posiciones aceptadas o lanza
     * [SyncException] con el motivo (para decidir reintento o descarte).
     */
    @Throws(SyncException::class, IOException::class)
    fun sendBatch(fixes: List<PreciseFix>): Int {
        if (!settings.pairingComplete) throw SyncException("Agente sin emparejar")
        if (fixes.isEmpty()) return 0

        val arr = JSONArray()
        fixes.forEach { arr.put(it.toJson()) }

        val payload = JSONObject().apply {
            put("p_device_id", settings.deviceId)
            put("p_token", settings.deviceToken)
            put("p_positions", arr)
        }

        val request = Request.Builder()
            .url(settings.supabaseUrl.trimEnd('/') + "/rest/v1/rpc/ingest_positions")
            .header("apikey", settings.supabaseKey)
            .header("Authorization", "Bearer " + settings.supabaseKey)
            .header("Prefer", "return=representation")
            .post(payload.toString().toRequestBody(json))
            .build()

        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.isSuccessful) {
                // La funcion devuelve integer: [["n"]] en representacion JSON
                return try {
                    val parsed = org.json.JSONTokener(body).nextValue()
                    when (parsed) {
                        is Int -> parsed
                        is org.json.JSONArray -> parsed.getJSONArray(0).getInt(0)
                        else -> fixes.size
                    }
                } catch (_: Exception) {
                    fixes.size
                }
            }
            throw SyncException("HTTP " + resp.code + ": " + body.take(200))
        }
    }
}

class SyncException(message: String) : Exception(message)
