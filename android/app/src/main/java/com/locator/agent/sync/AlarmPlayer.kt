package com.locator.agent.sync

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Alarma remota: suena el tono de alarma del sistema a todo volumen para poder
 * encontrar el telefono aunque este en silencio (usa USAGE_ALARM, no el
 * volumen de multimedia).
 *
 * Se auto-detiene a los 2 minutos: si se pierde la conexion y no llega el
 * comando `stop_alarm`, no se queda sonando indefinidamente gastando bateria.
 * Tampoco es persistente: si el proceso muere, la alarma muere con el.
 */
class AlarmPlayer private constructor(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stop() }

    @Volatile private var player: MediaPlayer? = null

    val isRinging: Boolean
        get() = player != null

    fun start(maxMs: Long = DEFAULT_MAX_MS) {
        stop()
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        if (uri == null) {
            Log.w(TAG, "El dispositivo no tiene tono de alarma/llamada")
            return
        }

        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setDataSource(context, uri)
            mp.isLooping = true
            mp.setOnPreparedListener { it.start() }
            mp.setOnErrorListener { _, _, _ ->
                stop()
                true
            }
            mp.prepareAsync()
            player = mp
        } catch (t: Throwable) {
            Log.w(TAG, "No se pudo iniciar la alarma", t)
            runCatching { mp.release() }
            return
        }

        main.removeCallbacks(autoStop)
        main.postDelayed(autoStop, maxMs.coerceIn(5_000L, MAX_MS))
        Log.i(TAG, "Alarma activa (auto-parada en ${maxMs / 1000}s)")
    }

    fun stop() {
        main.removeCallbacks(autoStop)
        player?.let { p ->
            runCatching { if (p.isPlaying) p.stop() }
            runCatching { p.release() }
        }
        player = null
    }

    companion object {
        private const val TAG = "AlarmPlayer"
        private const val DEFAULT_MAX_MS = 120_000L
        private const val MAX_MS = 300_000L

        @Volatile private var instance: AlarmPlayer? = null

        fun get(context: Context): AlarmPlayer =
            instance ?: synchronized(this) {
                instance ?: AlarmPlayer(context.applicationContext).also { instance = it }
            }
    }
}
