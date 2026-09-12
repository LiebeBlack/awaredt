package com.locator.agent.sync

import org.json.JSONObject

/**
 * Comando remoto emitido desde `admin.html` (tabla `device_commands`).
 *
 * La lista de comandos es CERRADA y vive en [RemoteCommand.ALLOWED]: el agente
 * ignora cualquier otro valor aunque llegue en la respuesta del servidor. No
 * hay canal para ejecutar codigo arbitrario, instalar APKs ni leer datos
 * personales: los comandos mueven el propio agente, nada mas.
 */
data class RemoteCommand(
    val id: Long,
    val name: String,
    val args: JSONObject
) {
    val allowed: Boolean
        get() = ALLOWED.contains(name)

    companion object {
        /**
         * locate_now   -> fix inmediato de alta precision (no espera al ciclo)
         * flush        -> vacia el buffer cifrado ya
         * lock         -> bloquea la pantalla (requiere modo antirrobo activo)
         * alarm        -> suena la alarma del sistema (auto-parada 2 min)
         * stop_alarm   -> detiene la alarma
         * stop_tracking-> detiene el rastreo y NO lo relanza (apaga el watchdog)
         * burst        -> persecucion temporal (args: minutes, interval_sec; tope 30 min)
         * stop_burst   -> corta la persecucion antes de que caduque
         */
        val ALLOWED = setOf(
            "locate_now",
            "flush",
            "lock",
            "alarm",
            "stop_alarm",
            "stop_tracking",
            "burst",
            "stop_burst"
        )
    }
}
