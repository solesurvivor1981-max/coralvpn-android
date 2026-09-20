package tech.aiboost.coralvpn.vpn

import android.util.Log
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.SystemProxyStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Wraps the libbox CommandServer lifecycle. One instance per [CoralVpnService].
 * Order (per SFA BoxService): setup drafts → CommandServer.start() → network monitor →
 * startOrReloadService(config). Stop: closeService() → close() → close tun fd.
 */
class BoxService(
    private val service: CoralVpnService,
    private val platformInterface: PlatformInterface,
) : CommandServerHandler {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var commandServer: CommandServer? = null

    fun start(configJson: String, onStarted: () -> Unit, onError: (String) -> Unit) {
        scope.launch {
            try {
                Libbox.promoteOOMDraft()
                Libbox.discardPowerReportDraft()

                val server = CommandServer(this@BoxService, platformInterface)
                server.start()
                commandServer = server

                DefaultNetworkMonitor.start()

                server.startOrReloadService(configJson, OverrideOptions())
                onStarted()
            } catch (e: Exception) {
                Log.e(TAG, "start failed", e)
                onError(e.message ?: e.toString())
            }
        }
    }

    fun stop() {
        scope.launch {
            val server = commandServer ?: return@launch
            runCatching { server.closeService() }
                .onFailure { server.setError("android: close service: ${it.message}") }
            runCatching { DefaultNetworkMonitor.stop() }
            runCatching { server.close() }
            commandServer = null
        }
    }

    fun onDestroy() {
        scope.cancel()
    }

    // ---- CommandServerHandler (all seven methods) ----

    override fun serviceStop() {
        service.requestStopFromCore()
    }

    override fun serviceReload() {
        // Minimal client: no live reload.
    }

    override fun getSystemProxyStatus(): SystemProxyStatus =
        SystemProxyStatus().apply {
            available = false
            enabled = false
        }

    override fun setSystemProxyEnabled(isEnabled: Boolean) {}

    override fun triggerNativeCrash() {}

    override fun writeDebugMessage(message: String?) {
        Log.d("sing-box", message ?: "")
    }

    override fun connectSSHAgent(): Int = -1

    companion object {
        private const val TAG = "BoxService"
    }
}
