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
    val pendingSends: Int = 0
)

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
