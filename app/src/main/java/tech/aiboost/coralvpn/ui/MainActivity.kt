package tech.aiboost.coralvpn.ui

import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.aiboost.coralvpn.R
import tech.aiboost.coralvpn.data.SubscriptionStore
import tech.aiboost.coralvpn.databinding.ActivityMainBinding
import tech.aiboost.coralvpn.net.ConfigClient
import tech.aiboost.coralvpn.net.SubscriptionInactiveException
import tech.aiboost.coralvpn.util.Formats
import tech.aiboost.coralvpn.vpn.CoralVpnService
import tech.aiboost.coralvpn.vpn.VpnController
import tech.aiboost.coralvpn.vpn.VpnStatus

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: SubscriptionStore
    private val configClient = ConfigClient()

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                startVpnService()
            } else {
                Toast.makeText(this, R.string.status_disconnected, Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = SubscriptionStore(this)

        binding.saveLinkButton.setOnClickListener { onSaveLink() }
        binding.connectButton.setOnClickListener { onConnectToggle() }
        binding.renewButton.setOnClickListener { openBot() }

        observeVpnState()
        handleDeeplink(intent)
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeeplink(intent)
        render()
    }

    override fun onResume() {
        super.onResume()
        maybeAutoRefresh()
    }

    /** Accepts coralvpn://add?url=<enc> and plain https sub links opened into the app. */
    private fun handleDeeplink(intent: Intent?) {
        val data: Uri = intent?.data ?: return
        val url = when {
            data.scheme == "coralvpn" -> data.getQueryParameter("url")
            data.scheme?.startsWith("http") == true -> data.toString()
            else -> null
        } ?: return
        binding.linkEditText.setText(url)
        saveAndFetch(url)
    }

    private fun onSaveLink() {
        val url = binding.linkEditText.text?.toString()?.trim().orEmpty()
        if (url.isEmpty()) {
            Toast.makeText(this, R.string.no_link, Toast.LENGTH_SHORT).show()
            return
        }
        saveAndFetch(url)
    }

    private fun saveAndFetch(url: String) {
        store.subscriptionUrl = url
        refreshConfig(showToast = true)
    }

    private fun refreshConfig(showToast: Boolean) {
        val url = store.subscriptionUrl ?: return
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { configClient.fetch(url) }
                store.cachedConfig = result.configJson
                store.saveInfo(result.info)
                store.lastRefreshEpochMs = System.currentTimeMillis()
            } catch (e: SubscriptionInactiveException) {
                if (showToast) Toast.makeText(this@MainActivity, R.string.err_inactive, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                if (showToast) Toast.makeText(this@MainActivity, R.string.err_network, Toast.LENGTH_LONG).show()
            } finally {
                render()
            }
        }
    }

    private fun maybeAutoRefresh() {
        if (!store.hasSubscription) return
        val info = store.loadInfo()
        val intervalMs = (info.updateIntervalHours ?: 1L) * 3600_000L
        val elapsed = System.currentTimeMillis() - store.lastRefreshEpochMs
        if (elapsed >= intervalMs) refreshConfig(showToast = false)
    }

    private fun onConnectToggle() {
        if (VpnController.state.value.status == VpnStatus.CONNECTED ||
            VpnController.state.value.status == VpnStatus.CONNECTING
        ) {
            ContextCompat.startForegroundService(this, CoralVpnService.disconnectIntent(this))
            return
        }
        if (store.loadInfo().isExpired) {
            Toast.makeText(this, R.string.err_inactive, Toast.LENGTH_LONG).show()
            return
        }
        val prepare = VpnService.prepare(this)
        if (prepare != null) {
            vpnPermissionLauncher.launch(prepare)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        val serverName = store.loadInfo().profileTitle
        ContextCompat.startForegroundService(this, CoralVpnService.connectIntent(this, serverName))
    }

    private fun observeVpnState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                VpnController.state.collect { renderVpnState() }
            }
        }
    }

    private fun render() {
        val hasSub = store.hasSubscription
        binding.linkInputGroup.visibility = if (hasSub) android.view.View.GONE else android.view.View.VISIBLE
        binding.connectGroup.visibility = if (hasSub) android.view.View.VISIBLE else android.view.View.GONE

        val info = store.loadInfo()
        binding.expiredBanner.visibility = if (hasSub && info.isExpired) android.view.View.VISIBLE else android.view.View.GONE
        binding.serverText.text = info.profileTitle?.let { getString(R.string.current_server, it) } ?: ""
        binding.expiresText.text = getString(R.string.expires_at, Formats.date(info.expireEpochSeconds))
        binding.trafficText.text = getString(R.string.traffic_remaining, Formats.bytes(info.remainingBytes))
        renderVpnState()
    }

    private fun renderVpnState() {
        val state = VpnController.state.value
        val expired = store.loadInfo().isExpired
        binding.connectButton.isEnabled = !expired
        val (label, colorRes) = when (state.status) {
            VpnStatus.CONNECTED -> R.string.disconnect to R.color.connected
            VpnStatus.CONNECTING -> R.string.connecting to R.color.connected
            else -> R.string.connect to R.color.disconnected
        }
        binding.connectButton.text = getString(label)
        binding.connectButton.backgroundTintList =
            ContextCompat.getColorStateList(this, colorRes)
        binding.statusText.text = when (state.status) {
            VpnStatus.CONNECTED -> getString(R.string.status_connected)
            VpnStatus.CONNECTING -> getString(R.string.connecting)
            else -> getString(R.string.status_disconnected)
        }
    }

    private fun openBot() {
        val support = store.loadInfo().supportUrl ?: "https://t.me/Coral_VPN_bot"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(support)))
        } catch (_: Exception) {
            // no browser/telegram installed — ignore
        }
    }
}
