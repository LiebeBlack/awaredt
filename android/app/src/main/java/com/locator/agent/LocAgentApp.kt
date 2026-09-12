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

        val sync = NotificationChannel(
            CHANNEL_SYNC,
            getString(R.string.notif_channel_sync),
            NotificationManager.IMPORTANCE_MIN
        ).apply { setShowBadge(false) }

        nm.createNotificationChannels(listOf(tracking, sync))
    }

    companion object {
        const val CHANNEL_TRACKING = "tracking"
        const val CHANNEL_SYNC = "sync"
    }
}
