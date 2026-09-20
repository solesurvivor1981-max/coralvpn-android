package tech.aiboost.coralvpn.vpn

import io.nekohasekai.libbox.Libbox

/**
 * Switches the active outbound inside the CORALVPN selector at runtime via a standalone
 * libbox command client (talks to the running CommandServer). No-op / logged if the service
 * isn't running. Call off the main thread.
 */
object OutboundSelector {
    fun select(group: String, tag: String) {
        runCatching {
            Libbox.newStandaloneCommandClient().selectOutbound(group, tag)
            LogStore.log("selector: $group -> $tag")
        }.onFailure { LogStore.log("selector: select failed: ${it.message}") }
    }
}
