package com.locator.agent.sync

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.locator.agent.data.SettingsRepository

/**
 * Valla de seguridad cada 15 minutos: si el usuario activo el rastreo y
 * el servicio no esta vivo, lo relanza. Compatible con Doze usando
 * setAndAllowWhileIdle. No omite ninguna restriccion del sistema: usa
 * los mecanismos oficiales.
 */
class WatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val settings = SettingsRepository.get(context)
        // El watchdog tambien reasegura la comprobacion de manipulacion
        TamperWorker.schedule(context)
        if (!settings.trackingEnabled || !settings.pairingComplete) return
        if (LocationService.isRunning) {
            schedule(context) // reprograma y sale
            return
        }
        Log.i(TAG, "Watchdog: servicio caido, relanzando")
        try {
            context.startForegroundService(Intent(context, LocationService::class.java))
        } catch (t: Throwable) {
            Log.w(TAG, "Relanzamiento fallido", t)
        }
        schedule(context)
    }

    companion object {
        private const val TAG = "WatchdogReceiver"
        private const val REQUEST_CODE = 4242
        private const val INTERVAL_MS = 15 * 60 * 1000L

        fun schedule(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                context, REQUEST_CODE,
                Intent(context, WatchdogReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val at = System.currentTimeMillis() + INTERVAL_MS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        }
    }
}
