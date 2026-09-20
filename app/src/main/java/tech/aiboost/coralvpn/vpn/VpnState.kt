package tech.aiboost.coralvpn.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class VpnStatus { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

data class VpnState(
    val status: VpnStatus = VpnStatus.DISCONNECTED,
    val serverName: String? = null,
    val message: String? = null,
)

/** Process-wide observable VPN state shared between the service and the UI. */
object VpnController {
    private val _state = MutableStateFlow(VpnState())
    val state: StateFlow<VpnState> = _state

    fun update(state: VpnState) {
        _state.value = state
    }

    fun update(block: (VpnState) -> VpnState) {
        _state.value = block(_state.value)
    }
}
