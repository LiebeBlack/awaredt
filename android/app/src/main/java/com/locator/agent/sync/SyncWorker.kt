package com.locator.agent.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Red de seguridad: cada 15 minutos intenta vaciar el buffer aunque el
 * servicio este detenido. El sistema lo reprograma tras Doze sin cheats.
 */
class SyncWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            // Red de seguridad del canal de control: si el servicio esta caido,
            // los comandos (bloquear, alarma, ubicar) siguen llegando aqui.
            runCatching { CommandChannel.get(applicationContext).pollOnce() }
            // flushBlocking espera al resultado de verdad: sin esto, el worker
            // decia "success" siempre y su reintento no servia para nada.
            val sent = SyncManager.get(applicationContext).flushBlocking(force = true)
            Log.i(TAG, "Worker de sincronización: $sent posiciones enviadas")
            Result.success()
        } catch (t: Throwable) {
            Log.w(TAG, "Worker de sincronización falló: ${t.message}")
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG = "SyncWorker"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "sync_positions",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
