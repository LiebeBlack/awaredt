package com.locator.agent.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.locator.agent.data.AppDatabase
import com.locator.agent.data.LocationEntity
import com.locator.agent.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Orquesta el envio: guarda en buffer cifrado, agrupa en lotes y envia
 * por HTTP persistente. Ante fallo de red aplica backoff exponencial y
 * reintenta automaticamente al volver la conectividad.
 */
class SyncManager private constructor(context: Context) {

    private val app = context.applicationContext
    private val settings = SettingsRepository.get(app)
    private val db = AppDatabase.get(app)
    private val api = SupabaseClient(settings)
    private val battery = BatteryMonitor(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flushMutex = Mutex()

    @Volatile private var failCount = 0
    @Volatile private var lastAttemptAt = 0L

    init {
        registerNetworkWatcher()
    }

    /**
     * Guarda un fix en el buffer cifrado y dispara envio inmediato.
     * [urgent] = true ignora el gate de ahorro de bateria: se usa en los fixes
     * puntuales ("ubicar ahora"), que no pueden quedarse esperando al worker.
     */
    fun enqueue(entity: LocationEntity, urgent: Boolean = false) {
        scope.launch {
            runCatching { db.locationDao().insert(entity) }
                .onFailure { Log.e(TAG, "No se pudo persistir el fix", it) }
            updatePendingCount()
            // Coalescing: en persecucion llegan fixes cada 2-3 s, mas rapido de
            // lo que conviene abrir una conexion. Si el ultimo intento de envio
            // es muy reciente, el siguiente fix (o el watcher de red) vacia el
            // buffer igualmente: nada se pierde, se agrupa.
            if (urgent || System.currentTimeMillis() - lastAttemptAt >= COALESCE_MS) {
                flushNow(force = urgent)
            }
        }
    }

    /** Intenta vaciar el buffer ahora mismo (con gate de bateria). */
    fun flushNow(force: Boolean = false) {
        scope.launch { flushLoop(force) }
    }

    /**
     * Vacia el buffer y ESPERA al resultado. Devuelve cuantas posiciones se
     * enviaron realmente (0 si no habia nada o si fallo la red).
     *
     * Existe porque `flushNow` es "dispara y olvida": el worker y el comando
     * remoto `flush` necesitan saber que ha pasado de verdad para poder
     * reintentar (uno) y reportar (el otro) en vez de decir "vaciado" sin mirar.
     */
    suspend fun flushBlocking(force: Boolean = false): Int =
        flushMutex.withLock { flushLocked(force) }

    /** Vaciado en segundo plano: nunca propaga la excepcion (nadie la espera). */
    private suspend fun flushLoop(force: Boolean) {
        runCatching { flushMutex.withLock { flushLocked(force) } }
            .onFailure { Log.w(TAG, "Vaciado fallido: ${it.message}") }
    }

    private suspend fun flushLocked(force: Boolean): Int {
        if (!settings.pairingComplete) return 0
        if (!force && battery.isLowPower()) return 0   // en ahorro, deja los lotes para WorkManager/carga

        // Backoff exponencial tras fallos consecutivos: 10s, 20s, 40s... max 10 min.
        // Solo para los vaciados AUTOMATICOS: uno pedido a proposito (worker de
        // 15 min, comando remoto flush, "ubicar ahora") no debe quedar mudo por
        // el throttling ni responder "0 enviadas" sin motivo.
        if (!force) {
            val backoffMs = if (failCount == 0) 0L else minOf(10_000L shl (failCount - 1).coerceAtMost(6), 600_000L)
            val since = System.currentTimeMillis() - lastAttemptAt
            if (since in 0 until backoffMs) return 0
        }

        var sentTotal = 0
        var batches = 0
        try {
            while (true) {
                val pending = db.locationDao().pending(BATCH)
                if (pending.isEmpty()) break
                val fixes = pending.map { it.toFix() }
                api.sendBatch(fixes)
                db.locationDao().markSent(pending.map { it.id })
                sentTotal += fixes.size
                // Tope por vaciado: tras un dia sin red no se sostiene el mutex
                // (y la conexion) hasta vaciar miles de fixes; el resto sale en el
                // siguiente ciclo del servicio o del worker.
                if (++batches >= MAX_BATCHES_PER_FLUSH) break
                if (pending.size < BATCH) break
            }
            if (sentTotal > 0) {
                Log.i(TAG, "Lote enviado: $sentTotal posiciones")
                failCount = 0
                // Retencion: limpia lo ya enviado con mas de 24 h
                db.locationDao().pruneSent(System.currentTimeMillis() - RETENTION_MS)
            }
        } catch (e: Exception) {
            failCount = (failCount + 1).coerceAtMost(10)
            Log.w(TAG, "Envio fallido (intento #$failCount): ${e.message}")
            throw e   // quien espera (worker/comando) decide si reintentar
        } finally {
            lastAttemptAt = System.currentTimeMillis()
            updatePendingCount()
        }
        return sentTotal
    }

    private fun updatePendingCount() {
        scope.launch {
            val n = runCatching { db.locationDao().pendingCount() }.getOrDefault(0)
            ServiceStateHolder.update { it.copy(pendingSends = n) }
        }
    }

    /** Al volver la red, vacia el buffer al instante. */
    private fun registerNetworkWatcher() {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.i(TAG, "Conectividad recuperada: vaciando buffer")
                    flushNow()
                }
            })
        } catch (t: Throwable) {
            Log.w(TAG, "No se pudo registrar el network callback", t)
        }
    }

    companion object {
        private const val TAG = "SyncManager"
        private const val BATCH = 20
        private const val RETENTION_MS = 24 * 60 * 60 * 1000L
        private const val MAX_BATCHES_PER_FLUSH = 25   // 25 x 20 = 500 fixes por vaciado
        private const val COALESCE_MS = 2500L

        @Volatile private var instance: SyncManager? = null

        fun get(context: Context): SyncManager =
            instance ?: synchronized(this) {
                instance ?: SyncManager(context.applicationContext).also { instance = it }
            }
    }
}
