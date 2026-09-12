package com.locator.agent.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.locator.agent.data.SettingsRepository
import java.util.concurrent.TimeUnit

/**
 * Revisa cada 6 horas si el agente sigue en condiciones de rastrear (permisos,
 * modo antirrobo, notificaciones, servicio) y lo sube al panel.
 *
 * Es la pata que responde a "¿y si me lo desactivan?": aunque el servicio se
 * caiga, WorkManager sigue despertando al proceso y el panel se entera del
 * motivo en horas, no en semanas. Funciona incluso sin la app abierta.
 */
class TamperWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val settings = SettingsRepository.get(applicationContext)
        if (!settings.pairingComplete) return Result.success()

        return try {
            TamperCheck.report(applicationContext)
            Result.success()
        } catch (t: Throwable) {
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }

    companion object {
        private const val NAME = "tamper_check"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<TamperWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
