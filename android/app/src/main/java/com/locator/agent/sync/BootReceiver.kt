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
        // BOOT_COMPLETED = reinicio; MY_PACKAGE_REPLACED = la app se actualizo
        // (el sistema mata el servicio al reemplazar el APK: hay que relanzarlo).
        // No se declara LOCKED_BOOT_COMPLETED: exigiria direct boot y el token
        // esta en almacenamiento cifrado con credenciales, que ahi no existe.
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        val settings = SettingsRepository.get(context)
        if (!settings.trackingEnabled || !settings.pairingComplete) return

        Log.i(TAG, "Boot completado: relanzando servicio de rastreo")
        val launch = Intent(context, LocationService::class.java)
        try {
            context.startForegroundService(launch)
        } catch (t: Throwable) {
            Log.w(TAG, "No se pudo relanzar en boot", t)
        }
        // Red de seguridad: si el OEM bloquea el arranque del servicio, el
        // worker periodico sigue comprobando y reportando el estado.
        TamperWorker.schedule(context)
        SyncWorker.schedule(context)
        WatchdogReceiver.schedule(context)
    }

    companion object { private const val TAG = "BootReceiver" }
}
