package com.locator.agent.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.locator.agent.data.SettingsRepository

/**
 * Reinicia el rastreo tras el arranque si el usuario lo tenia activado.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val settings = SettingsRepository.get(context)
        if (!settings.trackingEnabled || !settings.pairingComplete) return

        Log.i(TAG, "Boot completado: relanzando servicio de rastreo")
        val launch = Intent(context, LocationService::class.java)
        try {
            context.startForegroundService(launch)
        } catch (t: Throwable) {
            Log.w(TAG, "No se pudo relanzar en boot", t)
        }
    }

    companion object { private const val TAG = "BootReceiver" }
}
