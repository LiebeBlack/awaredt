package com.locator.agent.sync

import android.content.Context
import android.util.Log
import com.locator.agent.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Avisos que lanza la persona que lleva el telefono (no el panel):
 *  - SOS: pide ayuda. Fix inmediato + persecucion corta para que se le pueda
 *    seguir el rastro mientras dura el problema.
 *  - "Llegue bien": check-in tranquilizador al llegar a casa o al colegio.
 *
 * Es la pata de SUPERVISION del control parental: quien lleva el dispositivo
 * tambien tiene voz. Un sistema donde solo el adulto puede avisar es vigilancia,
 * no cuidado.
 */
object EventReporter {

    private const val TAG = "EventReporter"
    const val KIND_SOS = "sos"
    const val KIND_CHECKIN = "checkin"

    private const val SOS_BURST_MINUTES = 10
    private const val SOS_BURST_INTERVAL_SEC = 2
    private const val MIN_GAP_MS = 5_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var lastSentAt = 0L

    /**
     * SOS: si el rastreo estuviera parado lo arranca — es el propio dueño del
     * telefono pulsando, no una orden remota — y activa una persecucion corta.
     */
    fun sendSos(context: Context, note: String? = null) {
        val app = context.applicationContext
        val settings = SettingsRepository.get(app)

        if (settings.pairingComplete) {
            if (!LocationService.isRunning) {
                settings.trackingEnabled = true
                SyncWorker.schedule(app)
                WatchdogReceiver.schedule(app)
                LocationService.start(app)
            }
            settings.startBurst(SOS_BURST_MINUTES, SOS_BURST_INTERVAL_SEC)
            if (LocationService.isRunning) LocationService.start(app) // reaplica ya
        }

        send(context, KIND_SOS, note)
    }

    /** "Llegue bien": sube el aviso con la posicion mas reciente. */
    fun sendCheckin(context: Context, note: String? = null) {
        send(context, KIND_CHECKIN, note)
    }

    private fun send(context: Context, kind: String, note: String?) {
        val app = context.applicationContext
        val settings = SettingsRepository.get(app)
        if (!settings.pairingComplete) {
            Log.w(TAG, "Sin emparejar: no se puede enviar el aviso $kind")
            return
        }

        // Antirrebote: dos toques seguidos no mandan dos avisos
        val now = System.currentTimeMillis()
        if (now - lastSentAt < MIN_GAP_MS) return
        lastSentAt = now

        LocationService.requestOneShot(app) { loc ->
            scope.launch {
                try {
                    SupabaseClient(settings).reportEvent(kind, note, loc?.latitude, loc?.longitude)
                    Log.i(TAG, "Aviso enviado: $kind")
                } catch (e: Exception) {
                    Log.w(TAG, "No se pudo enviar el aviso $kind: ${e.message}")
                }
            }
        }
    }
}
