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
 * Cliente HTTP hacia Supabase. Todo pasa por RPC de Postgres:
 *  - [sendBatch]     -> ingest_positions (lotes de posiciones firmados con el token)
 *  - [pullCommands]  -> pull_commands    (comandos pendientes del panel admin)
 *  - [ackCommand]    -> ack_command      (resultado de un comando)
 *  - [revokeSelf]    -> revoke_self      (desvincular este dispositivo)
 *
 * El token nunca viaja como cabecera de autorizacion: va en el cuerpo y el
 * servidor solo guarda su SHA-256, asi que una copia de la base no sirve para
 * suplantar al agente.
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

    // ------------------------------------------------------------------ RPC base

    /** POST a /rest/v1/rpc/<name> con la anon key; devuelve el cuerpo crudo. */
    @Throws(SyncException::class, IOException::class)
    private fun rpc(name: String, payload: JSONObject): String {
        if (!settings.pairingComplete) throw SyncException("Agente sin emparejar")

        val request = Request.Builder()
            .url(settings.supabaseUrl.trimEnd('/') + "/rest/v1/rpc/" + name)
            .header("apikey", settings.supabaseKey)
            .header("Authorization", "Bearer " + settings.supabaseKey)
            .header("Prefer", "return=representation")
            .post(payload.toString().toRequestBody(json))
            .build()

        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.isSuccessful) return body
            throw SyncException("HTTP " + resp.code + ": " + body.take(200))
        }
    }

    // ------------------------------------------------------------------ posiciones

    /**
     * Envia un lote. Devuelve el numero de posiciones aceptadas o lanza
     * [SyncException] con el motivo (para decidir reintento o descarte).
     */
    @Throws(SyncException::class, IOException::class)
    fun sendBatch(fixes: List<PreciseFix>): Int {
        if (fixes.isEmpty()) return 0

        val arr = JSONArray()
        fixes.forEach { arr.put(it.toJson()) }

        val body = rpc("ingest_positions", JSONObject().apply {
            put("p_device_id", settings.deviceId)
            put("p_token", settings.deviceToken)
            put("p_positions", arr)
        })

        // La funcion devuelve integer: [[\"n\"]] en representacion JSON
        return try {
            when (val parsed = org.json.JSONTokener(body).nextValue()) {
                is Int -> parsed
                is JSONArray -> parsed.getJSONArray(0).getInt(0)
                else -> fixes.size
            }
        } catch (_: Exception) {
            fixes.size
        }
    }

    // ------------------------------------------------------------------ comandos

    /** Comandos pendientes; el servidor los marca como entregados al devolverlos. */
    @Throws(SyncException::class, IOException::class)
    fun pullCommands(): List<RemoteCommand> {
        val body = rpc("pull_commands", JSONObject().apply {
            put("p_device_id", settings.deviceId)
            put("p_token", settings.deviceToken)
        })

        val arr = when (val parsed = org.json.JSONTokener(body).nextValue()) {
            is JSONArray -> parsed
            is JSONObject -> parsed.optJSONArray("commands") ?: JSONArray()
            else -> JSONArray()
        }

        val out = ArrayList<RemoteCommand>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("command", "").trim()
            if (name.isEmpty()) continue
            out.add(
                RemoteCommand(
                    id = o.optLong("id", -1L),
                    name = name,
                    args = o.optJSONObject("args") ?: JSONObject()
                )
            )
        }
        return out
    }

    /** Reporta el resultado de un comando para que el panel lo muestre. */
    @Throws(SyncException::class, IOException::class)
    fun ackCommand(commandId: Long, status: String, result: String) {
        if (commandId <= 0) return
        rpc("ack_command", JSONObject().apply {
            put("p_device_id", settings.deviceId)
            put("p_token", settings.deviceToken)
            put("p_command_id", commandId)
            put("p_status", status)
            put("p_result", result.take(300))
        })
    }

    // ------------------------------------------------------------------ estado

    /**
     * Sube el estado del agente (permisos, antirrobo, servicio, batería).
     * Una fila por dispositivo: el panel ve de un vistazo si alguien lo ha
     * desactivado, y con qué motivo.
     */
    @Throws(SyncException::class, IOException::class)
    fun reportHealth(report: TamperReport) {
        rpc("report_health", JSONObject().apply {
            put("p_device_id", settings.deviceId)
            put("p_token", settings.deviceToken)
            put("p_report", report.toJson())
        })
    }

    /**
     * Aviso del propio dispositivo: `sos` o `checkin`, con la posición si se
     * tiene. El panel los muestra con hora; el agente no decide nada más.
     */
    @Throws(SyncException::class, IOException::class)
    fun reportEvent(kind: String, note: String?, lat: Double?, lon: Double?) {
        rpc("report_event", JSONObject().apply {
            put("p_device_id", settings.deviceId)
            put("p_token", settings.deviceToken)
            put("p_kind", kind)
            note?.let { put("p_note", it.take(200)) }
            lat?.let { put("p_lat", it) }
            lon?.let { put("p_lon", it) }
        })
    }

    // ------------------------------------------------------------------ revocacion

    /**
     * Desvincula este dispositivo: el servidor invalida el token (lo rota a un
     * valor aleatorio que nadie conoce) y, si [purge], borra su historial.
     * Despues de esto el agente deja de poder enviar hasta emparejarse de nuevo.
     */
    @Throws(SyncException::class, IOException::class)
    fun revokeSelf(purge: Boolean): Boolean {
        val body = rpc("revoke_self", JSONObject().apply {
            put("p_device_id", settings.deviceId)
            put("p_token", settings.deviceToken)
            put("p_purge", purge)
        })
        return try {
            when (val parsed = org.json.JSONTokener(body).nextValue()) {
                is Int -> true
                is JSONArray -> true
                else -> true
            }
        } catch (_: Exception) {
            true
        }
    }
}

class SyncException(message: String) : Exception(message)
