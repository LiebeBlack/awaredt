package com.locator.agent.sync

import android.content.Context
import android.util.Log
import com.locator.agent.admin.DeviceAdmin
import com.locator.agent.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Canal de control remoto agente <- panel admin.
 *
 * Como funciona: el agente consulta `pull_commands` (como mucho una vez por
 * minuto) y ejecuta SOLO los comandos de la lista blanca [RemoteCommand.ALLOWED].
 * Cada ejecucion se reporta con `ack_command`, asi que el panel ve el resultado
 * real ("pantalla bloqueada" / "device admin no activo") en vez de un boton mudo.
 *
 * Limites deliberados:
 *  - El agente siempre inicia la conexion (pull). No hay push: no se abre
 *    ningun puerto ni se mantiene un socket escuchando ordenes.
 *  - Las acciones destructivas sobre datos estan fuera: no hay comando para
 *    borrar el telefono, instalar apps ni leer mensajes/contactos.
 *  - El rastreo se puede parar en remoto, y al hacerlo se apaga tambien la
 *    bandera local para que el watchdog NO lo resucite (parar es parar).
 */
class CommandChannel private constructor(context: Context) {

    private val app = context.applicationContext
    private val settings = SettingsRepository.get(app)
    private val api = SupabaseClient(settings)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    /** Consulta y ejecuta en segundo plano (no bloquea al llamante). */
    fun pollAsync() {
        if (!allowedNow()) return
        scope.launch { pollOnce() }
    }

    private fun allowedNow(): Boolean = settings.pairingComplete && settings.remoteControl

    /** Consulta y ejecuta los comandos pendientes. Silencioso ante fallos de red. */
    suspend fun pollOnce() {
        if (!allowedNow()) return
        mutex.withLock {
            val commands = try {
                api.pullCommands()
            } catch (e: Exception) {
                // Sin red o sin credenciales: se reintenta en el proximo ciclo
                Log.d(TAG, "Sin comandos: ${e.message}")
                return
            }
            commands.forEach { executeSimple(it) }
        }
    }

    private suspend fun executeSimple(cmd: RemoteCommand) {
        if (!cmd.allowed) {
            Log.w(TAG, "Comando ignorado por la lista blanca: ${cmd.name}")
            ack(cmd, "failed", "comando no permitido")
            return
        }

        var ok = true
        val result: String = try {
            when (cmd.name) {
                "locate_now" -> {
                    LocationService.requestOneShot(app)
                    "fix solicitado"
                }
                "flush" -> {
                    SyncManager.get(app).flushNow(force = true)
                    "buffer vaciado"
                }
                "lock" -> {
                    if (DeviceAdmin.lockNow(app)) "pantalla bloqueada"
                    else {
                        ok = false
                        "modo antirrobo no activo en el dispositivo"
                    }
                }
                "alarm" -> {
                    AlarmPlayer.get(app).start()
                    "alarma activa (2 min máx.)"
                }
                "stop_alarm" -> {
                    AlarmPlayer.get(app).stop()
                    "alarma detenida"
                }
                "stop_tracking" -> {
                    settings.trackingEnabled = false
                    AlarmPlayer.get(app).stop()
                    LocationService.stop(app)
                    "rastreo detenido"
                }
                "burst" -> {
                    // Persecucion: mas frecuencia y alta precision por unos minutos.
                    // Si el dueño detuvo el rastreo, NO se resucita desde aqui
                    // ("parar es parar"): se reporta el motivo y se queda asi.
                    if (!LocationService.isRunning) {
                        ok = false
                        "el rastreo está detenido en el dispositivo: hay que iniciarlo a mano"
                    } else {
                        val minutes = cmd.args.optInt("minutes", DEFAULT_BURST_MIN)
                        val interval = cmd.args.optInt("interval_sec", DEFAULT_BURST_INTERVAL_SEC)
                        settings.startBurst(minutes, interval)
                        // Reaplica el request ya (no esperar al proximo ciclo)
                        LocationService.start(app)
                        "persecución activa: cada ${settings.burstIntervalSec} s durante " +
                            "${settings.burstRemainingSec() / 60} min"
                    }
                }
                "stop_burst" -> {
                    settings.stopBurst()
                    if (LocationService.isRunning) LocationService.start(app)
                    "persecución detenida"
                }
                else -> {
                    ok = false
                    "comando no soportado"
                }
            }
        } catch (t: Throwable) {
            ok = false
            "error: " + (t.message ?: t.javaClass.simpleName)
        }

        Log.i(TAG, "Comando ${cmd.name} -> $result")
        // Visible en la propia app del telefono: quien lo lleva ve que el panel le
        // acaba de mandar algo (y si funciono).
        ServiceStateHolder.update {
            it.copy(lastCommand = cmd.name + (if (ok) " OK" else " fallo"))
        }
        ack(cmd, if (ok) "done" else "failed", result)
    }

    private suspend fun ack(cmd: RemoteCommand, status: String, result: String) {
        try {
            api.ackCommand(cmd.id, status, result)
        } catch (e: Exception) {
            Log.d(TAG, "No se pudo reportar el comando ${cmd.id}: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "CommandChannel"
        private const val DEFAULT_BURST_MIN = 10
        private const val DEFAULT_BURST_INTERVAL_SEC = 3

        @Volatile private var instance: CommandChannel? = null

        fun get(context: Context): CommandChannel =
            instance ?: synchronized(this) {
                instance ?: CommandChannel(context.applicationContext).also { instance = it }
            }
    }
}
