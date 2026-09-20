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
                LogStore.log("box: promote OOM draft")
                Libbox.promoteOOMDraft()

                LogStore.log("box: creating CommandServer")
                val server = CommandServer(this@BoxService, platformInterface)
                server.start()
                commandServer = server
                LogStore.log("box: CommandServer started")

                DefaultNetworkMonitor.start()
                LogStore.log("box: network monitor started; starting service…")

                server.startOrReloadService(configJson, OverrideOptions())
                LogStore.log("box: startOrReloadService returned OK")
                onStarted()
            } catch (e: Exception) {
                Log.e(TAG, "start failed", e)
                LogStore.log("box: START FAILED: ${e.message ?: e}")
                onError(e.message ?: e.toString())
            }
        }
    }

    fun stop() {
        scope.launch {
            val server = commandServer ?: return@launch
            LogStore.log("box: closeService()")
            runCatching { server.closeService() }
                .onFailure {
                    LogStore.log("box: closeService error: ${it.message}")
                    server.setError("android: close service: ${it.message}")
                }
            runCatching { DefaultNetworkMonitor.stop() }
            runCatching { server.close() }
            commandServer = null
            LogStore.log("box: stopped")
        }
    }

    fun onDestroy() {
        scope.cancel()
    }

    // ---- CommandServerHandler (all seven methods) ----

    override fun serviceStop() {
        LogStore.log("core → serviceStop() (core requested teardown)")
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
        if (!message.isNullOrBlank()) LogStore.log("core: $message")
    }

    override fun connectSSHAgent(): Int = -1

    companion object {
        private const val TAG = "BoxService"
    }
}
