package com.locator.agent.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class ServiceState(
    val running: Boolean = false,
    val lastLat: Double? = null,
    val lastLon: Double? = null,
    val lastAccuracy: Float? = null,
    val lastSource: String? = null,
    val lastFixAt: Long? = null,
    val pendingSends: Int = 0,
    /** true = el gate de movimiento decidio no enviar por estar quieto. */
    val stationary: Boolean = false,
    /** true = modo persecucion activo (muestreo agresivo temporal). */
    val burst: Boolean = false,
    /**
     * Ultimo mando remoto ejecutado ("alarm ✓"), para que quien tiene el
     * telefono en la mano vea que el panel le hizo algo. Transparencia, no logs.
     */
    val lastCommand: String? = null
)

/**
 * ¿Esta la app (MainActivity) en primer plano ahora mismo?
 *
 * Se usa para una regla de Android 14 que no admite discusion: un servicio en
 * primer plano de tipo `location` NO se puede crear desde segundo plano (la
 * ubicacion es un permiso "mientras se usa"). Si se intenta, el sistema lanza
 * SecurityException y mata el proceso. Con esta bandera el servicio arranca
 * desde segundo plano solo con `dataSync` y se PROMOCIONA a `location` en
 * cuanto hay una actividad visible.
 */
object AppVisibility {
    @Volatile
    var visible: Boolean = false
        private set

    fun set(value: Boolean) {
        visible = value
    }
}

/**
 * Estado compartido servicio <-> UI dentro del mismo proceso.
 * [LocationService] publica, [MainActivity] observa.
 */
object ServiceStateHolder {
    private val _state = MutableStateFlow(ServiceState())
    val state: StateFlow<ServiceState> = _state

    fun update(transform: (ServiceState) -> ServiceState) {
        _state.value = transform(_state.value)
    }

    fun reset() {
        _state.value = ServiceState(running = false)
    }
}
