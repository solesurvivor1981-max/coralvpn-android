package tech.aiboost.coralvpn.vpn

import android.util.Log
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.OutboundGroupItemIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Subscribes to the sing-box core LOG + STATUS streams over the libbox CommandServer
 * and forwards every line to [LogStore]. This is the only channel that carries the REAL
 * runtime reason a tunnel "connects then drops": CommandServerHandler.writeDebugMessage
 * does NOT — core logs and the service error come out here, on the CommandClient log stream.
 *
 * Built against sing-box v1.15.0-alpha.6 libbox bindings (io.nekohasekai.libbox). Verified
 * against SFA `dev` (app/src/main/java/io/nekohasekai/sfa/utils/CommandClient.kt).
 *
 * NOTE (alpha.6 API): CommandClientOptions changed. There is no public `command` field any
 * more — you select streams with `options.addCommand(Libbox.CommandLog)` (repeatable) and set
 * `options.statusInterval` (nanoseconds). The handler's log callback is
 * `writeLogs(LogIterator)`, not the old `writeLogs(StringIterator)`. See docstrings below.
 */
class CommandLogClient {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var client: CommandClient? = null

    /**
     * Start the LOG (+STATUS) subscription. Call AFTER CommandServer.start() has returned
     * (the client dials the same command socket the server just opened). A handler-bound
     * CommandClient retries the dial with WaitForReady for ~a few seconds, so a small race
     * with server startup is tolerated — but do not call this before start().
     */
    fun start() {
        scope.launch {
            try {
                val options = CommandClientOptions().apply {
                    addCommand(Libbox.CommandLog)     // core runtime log lines (incl. the drop reason)
                    addCommand(Libbox.CommandStatus)  // memory/goroutines/traffic; also surfaces "service stopped"
                    statusInterval = 1_000_000_000L   // 1s, in nanoseconds
                }
                // Two-arg struct constructor (gomobile): CommandClient(handler, options).
                // NOT newStandaloneCommandClient() — that one takes no handler and is for
                // one-shot request/response calls, it does NOT stream logs.
                val c = CommandClient(Handler(), options)
                c.connect() // throws on failure; blocks briefly while probing the socket
                client = c
                LogStore.log("logclient: subscribed to core log+status")
            } catch (e: Exception) {
                Log.e(TAG, "log client connect failed", e)
                LogStore.log("logclient: connect FAILED: ${e.message ?: e}")
            }
        }
    }

    /** Stop the subscription. Call from CoralVpnService.stopTunnel() teardown. */
    fun stop() {
        scope.launch {
            val c = client ?: return@launch
            client = null
            runCatching { c.disconnect() }
                .onFailure { LogStore.log("logclient: disconnect error: ${it.message}") }
        }
    }

    /** Release the coroutine scope; call from CoralVpnService.onDestroy(). */
    fun onDestroy() {
        scope.cancel()
    }

    /**
     * Full CommandClientHandler implementation (alpha.6). Only the log/status/connection
     * callbacks do anything; everything else is stubbed. Nullability matches the gomobile
     * bindings: iterators/messages arrive nullable.
     */
    private inner class Handler : CommandClientHandler {

        override fun connected() {
            LogStore.log("logclient: connected")
        }

        override fun disconnected(message: String?) {
            // This fires when the core log stream ends — i.e. when the service stops.
            // `message` is the error string from the core (E.Cause) — the reason we want.
            LogStore.log("logclient: disconnected: ${message ?: "(no message)"}")
        }

        override fun setDefaultLogLevel(level: Int) {
            LogStore.log("logclient: default log level = $level")
        }

        override fun clearLogs() {
            // Core asked to reset the buffer; we keep history, so ignore.
        }

        override fun writeLogs(messageList: LogIterator?) {
            val it = messageList ?: return
            while (it.hasNext()) {
                val entry = it.next() ?: continue
                // io.nekohasekai.libbox.LogEntry: level (Int), message (String)
                LogStore.log("core[${entry.level}]: ${entry.message}")
            }
        }

        override fun writeStatus(message: StatusMessage?) {
            // Optional: uncomment if you want traffic/memory noise in the log.
            // val s = message ?: return
            // LogStore.log("status: up=${s.uplink} down=${s.downlink} goroutines=${s.goroutines}")
        }

        // ---- Stubs: not needed for logs+status ----

        override fun writeGroups(message: OutboundGroupIterator?) {}
        override fun writeOutbounds(message: OutboundGroupItemIterator?) {}
        override fun initializeClashMode(modeList: StringIterator?, currentMode: String?) {}
        override fun updateClashMode(newMode: String?) {}
        override fun writeConnectionEvents(events: ConnectionEvents?) {}
    }

    companion object {
        private const val TAG = "CommandLogClient"
    }
}
