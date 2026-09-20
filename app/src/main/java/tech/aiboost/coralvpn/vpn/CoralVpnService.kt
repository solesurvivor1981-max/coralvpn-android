package tech.aiboost.coralvpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import androidx.core.app.NotificationCompat
import tech.aiboost.coralvpn.R
import tech.aiboost.coralvpn.ui.MainActivity

/**
 * VPN service.
 *
 * NOTE (milestone 2a): this is a skeleton — it manages the foreground notification and
 * the observable [VpnController] state, but does NOT yet route traffic. The real libbox /
 * sing-box tunnel (CommandServer + startOrReloadService + openTun) lands in milestone 2b.
 */
class CoralVpnService : VpnService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                stopTunnel()
                return START_NOT_STICKY
            }
            else -> startTunnel(intent?.getStringExtra(EXTRA_SERVER_NAME))
        }
        return START_STICKY
    }

    private fun startTunnel(serverName: String?) {
        VpnController.update(VpnState(VpnStatus.CONNECTING, serverName))
        startForeground(NOTIFICATION_ID, buildNotification(serverName))
        // TODO(2b): Libbox.setup once; CommandServer(handler, platformInterface).start();
        //           commandServer.startOrReloadService(configJson, OverrideOptions()).
        VpnController.update(VpnState(VpnStatus.CONNECTED, serverName))
    }

    private fun stopTunnel() {
        // TODO(2b): commandServer.closeService(); commandServer.close(); close tun fd.
        VpnController.update(VpnState(VpnStatus.DISCONNECTED))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        VpnController.update(VpnState(VpnStatus.DISCONNECTED))
        super.onDestroy()
    }

    private fun buildNotification(serverName: String?): Notification {
        ensureChannel()
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_coral)
            .setContentTitle(getString(R.string.notif_connected))
            .setContentText(serverName)
            .setOngoing(true)
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
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
        const val ACTION_CONNECT = "tech.aiboost.coralvpn.CONNECT"
        const val ACTION_DISCONNECT = "tech.aiboost.coralvpn.DISCONNECT"
        const val EXTRA_SERVER_NAME = "server_name"

        private const val CHANNEL_ID = "vpn_status"
        private const val NOTIFICATION_ID = 1

        fun connectIntent(context: Context, serverName: String?): Intent =
            Intent(context, CoralVpnService::class.java)
                .setAction(ACTION_CONNECT)
                .putExtra(EXTRA_SERVER_NAME, serverName)

        fun disconnectIntent(context: Context): Intent =
            Intent(context, CoralVpnService::class.java).setAction(ACTION_DISCONNECT)
    }
}
