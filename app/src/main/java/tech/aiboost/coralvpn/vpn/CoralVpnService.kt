package tech.aiboost.coralvpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager.NameNotFoundException
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import io.nekohasekai.libbox.AutoRedirectHandler
import io.nekohasekai.libbox.AutoRedirectSession
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification as LibboxNotification
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import tech.aiboost.coralvpn.R
import tech.aiboost.coralvpn.ui.MainActivity

/**
 * Real sing-box/libbox VPN service. Extends [VpnService] and implements libbox's
 * [PlatformInterface]. See docs/SPEC §4/§6 and the libbox integration notes in CLAUDE.md.
 */
class CoralVpnService : VpnService(), PlatformInterface {

    private val box = BoxService(this, this)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Kept so we can close the tun on stop; also returned as fd from [openTun]. */
    var fileDescriptor: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                stopTunnel()
                return START_NOT_STICKY
            }
            else -> {
                val configJson = intent?.getStringExtra(EXTRA_CONFIG_JSON)
                val serverName = intent?.getStringExtra(EXTRA_SERVER_NAME)
                if (configJson.isNullOrBlank()) {
                    Log.e(TAG, "empty config")
                    VpnController.update(VpnState(VpnStatus.ERROR, message = "empty config"))
                    stopSelf()
                    return START_NOT_STICKY
                }
                LogStore.log("service: onStartCommand CONNECT (server=$serverName, cfg=${configJson.length}B)")
                VpnController.update(VpnState(VpnStatus.CONNECTING, serverName))
                try {
                    startForeground(NOTIFICATION_ID, buildNotification(serverName, getString(R.string.connecting)))
                } catch (e: Exception) {
                    LogStore.log("service: startForeground FAILED: ${e.message ?: e}")
                    VpnController.update(VpnState(VpnStatus.ERROR, serverName, "startForeground: ${e.message}"))
                    stopSelf()
                    return START_NOT_STICKY
                }
                box.start(
                    configJson,
                    onStarted = {
                        VpnController.update(VpnState(VpnStatus.CONNECTED, serverName))
                        updateNotification(serverName, getString(R.string.status_connected))
                    },
                    onError = { msg ->
                        Log.e(TAG, "start failed: $msg")
                        VpnController.update(VpnState(VpnStatus.ERROR, serverName, msg))
                        mainHandler.post { stopTunnel() }
                    },
                )
                return START_STICKY
            }
        }
    }

    /** Called by [BoxService] when the core requests a stop. */
    fun requestStopFromCore() {
        mainHandler.post { stopTunnel() }
    }

    private fun stopTunnel() {
        LogStore.log("service: stopTunnel()")
        box.stop()
        fileDescriptor?.let { runCatching { it.close() } }
        fileDescriptor = null
        val prev = VpnController.state.value
        // Preserve an error message if we're tearing down because of one.
        if (prev.status == VpnStatus.ERROR) {
            VpnController.update(VpnState(VpnStatus.ERROR, prev.serverName, prev.message))
        } else {
            VpnController.update(VpnState(VpnStatus.DISCONNECTED))
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        LogStore.log("service: onRevoke() (OS revoked VPN)")
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        box.onDestroy()
        VpnController.update(VpnState(VpnStatus.DISCONNECTED))
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    // ---- PlatformInterface: meaningful ----

    override fun openTun(options: TunOptions): Int {
        if (prepare(this) != null) error("android: missing vpn permission")
        LogStore.log("openTun: mtu=${options.mtu} autoRoute=${options.autoRoute} dnsMode=${runCatching { options.dnsMode.value }.getOrNull()}")

        val builder = Builder()
            .setSession("CoralVPN")
            .setMtu(options.mtu)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)

        val inet4 = options.inet4Address
        while (inet4.hasNext()) {
            val a = inet4.next()
            builder.addAddress(a.address(), a.prefix())
        }
        val inet6 = options.inet6Address
        while (inet6.hasNext()) {
            val a = inet6.next()
            builder.addAddress(a.address(), a.prefix())
        }

        if (options.autoRoute) {
            if (options.dnsMode.value != Libbox.DNSModeDisabled) {
                val dns = options.dnsServerAddress
                while (dns.hasNext()) builder.addDnsServer(dns.next())
            }
            builder.addRoute("0.0.0.0", 0)
            builder.addRoute("::", 0)

            val include = options.includePackage
            while (include.hasNext()) {
                try {
                    builder.addAllowedApplication(include.next())
                } catch (e: NameNotFoundException) {
                    Log.e(TAG, "addAllowedApplication", e)
                }
            }
            val exclude = options.excludePackage
            while (exclude.hasNext()) {
                try {
                    builder.addDisallowedApplication(exclude.next())
                } catch (e: NameNotFoundException) {
                    Log.e(TAG, "addDisallowedApplication", e)
                }
            }
        }

        val pfd = builder.establish()
            ?: error("android: the application is not prepared or is revoked")
        fileDescriptor = pfd
        LogStore.log("openTun: established fd=${pfd.fd}")
        return pfd.fd
    }

    override fun autoDetectInterfaceControl(fd: Int) {
        protect(fd)
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override fun localDNSTransport(): LocalDNSTransport? = LocalResolver

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        DefaultNetworkMonitor.setListener(listener)
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        DefaultNetworkMonitor.setListener(null)
    }

    override fun getInterfaces(): NetworkInterfaceIterator = DefaultNetworkMonitor.getInterfaces()

    // ---- PlatformInterface: stubs ----

    override fun underNetworkExtension(): Boolean = false
    override fun includeAllNetworks(): Boolean = false
    override fun readWIFIState(): WIFIState? = null
    override fun clearDNSCache() {}
    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String?,
        sourcePort: Int,
        destinationAddress: String?,
        destinationPort: Int,
    ): ConnectionOwner = error("not supported")

    override fun startNeighborMonitor(listener: NeighborUpdateListener?) {}
    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) {}
    override fun registerMyInterface(name: String?) {}
    override fun usePlatformShell(): Boolean = false
    override fun checkPlatformShell() {
        error("not supported")
    }

    override fun openShellSession(
        user: PlatformUser?,
        command: String?,
        environ: StringIterator?,
        term: String?,
        rows: Int,
        cols: Int,
    ): ShellSession = error("not supported")

    override fun lookupUser(username: String?): PlatformUser = error("not supported")
    override fun lookupSFTPServer(): String = error("not supported")
    override fun readSystemSSHHostKey(): String = error("not supported")
    override fun tailscaleHostname(): String = "${Build.MANUFACTURER} ${Build.MODEL}"
    override fun usePlatformBridge(): Boolean = false
    override fun createBridge(options: BridgeOptions?): BridgeSession = error("not supported")
    override fun usePlatformAutoRedirect(): Boolean = false
    override fun createAutoRedirect(
        options: ByteArray?,
        handler: AutoRedirectHandler?,
    ): AutoRedirectSession = error("not supported")

    override fun sendNotification(notification: LibboxNotification?) {}
    override fun cancelNotification(identifier: String?, typeID: Int) {}

    // ---- Notification ----

    private fun buildNotification(serverName: String?, text: String): Notification {
        ensureChannel()
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopFlags =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val stop = PendingIntent.getService(
            this,
            1,
            disconnectIntent(this),
            stopFlags,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_coral)
            .setContentTitle(serverName ?: getString(R.string.notif_connected))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.disconnect), stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(serverName: String?, text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(serverName, text))
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.notif_channel_name),
                        NotificationManager.IMPORTANCE_LOW,
                    ),
                )
            }
        }
    }

    companion object {
        private const val TAG = "CoralVpnService"
        const val ACTION_CONNECT = "tech.aiboost.coralvpn.CONNECT"
        const val ACTION_DISCONNECT = "tech.aiboost.coralvpn.DISCONNECT"
        const val EXTRA_CONFIG_JSON = "config_json"
        const val EXTRA_SERVER_NAME = "server_name"

        private const val CHANNEL_ID = "vpn_status"
        private const val NOTIFICATION_ID = 1

        fun connectIntent(context: Context, configJson: String, serverName: String?): Intent =
            Intent(context, CoralVpnService::class.java)
                .setAction(ACTION_CONNECT)
                .putExtra(EXTRA_CONFIG_JSON, configJson)
                .putExtra(EXTRA_SERVER_NAME, serverName)

        fun disconnectIntent(context: Context): Intent =
            Intent(context, CoralVpnService::class.java).setAction(ACTION_DISCONNECT)
    }
}
