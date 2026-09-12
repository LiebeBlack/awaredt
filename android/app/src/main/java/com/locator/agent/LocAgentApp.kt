package com.locator.agent

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.work.Configuration

class LocAgentApp : Application(), Configuration.Provider {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        val tracking = NotificationChannel(
            CHANNEL_TRACKING,
            getString(R.string.notif_channel_tracking),
            NotificationManager.IMPORTANCE_LOW // silencioso: sin sonido ni vibracion
        ).apply {
            setShowBadge(false)
        }

        // Canal aparte para el "modo discreto". En Android 8+ la importancia la
        // fija el CANAL: con un solo canal, el setPriority(PRIORITY_MIN) del
        // agente se ignoraba y el modo discreto no hacia absolutamente nada.
        // IMPORTANCE_MIN = sin sonido, sin vibracion, sin asomar en la barra
        // (plegada), pero SIEMPRE visible y con su boton Detener.
        val trackingMin = NotificationChannel(
            CHANNEL_TRACKING_MIN,
            getString(R.string.notif_channel_tracking_min),
            NotificationManager.IMPORTANCE_MIN
        ).apply { setShowBadge(false) }

        val sync = NotificationChannel(
            CHANNEL_SYNC,
            getString(R.string.notif_channel_sync),
            NotificationManager.IMPORTANCE_MIN
        ).apply { setShowBadge(false) }

        nm.createNotificationChannels(listOf(tracking, trackingMin, sync))
    }

    companion object {
        const val CHANNEL_TRACKING = "tracking"
        const val CHANNEL_TRACKING_MIN = "tracking_min"
        const val CHANNEL_SYNC = "sync"
    }
}
