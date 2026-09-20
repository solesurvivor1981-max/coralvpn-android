package tech.aiboost.coralvpn

import android.app.Application
import android.os.Build
import android.util.Log
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import org.json.JSONObject
import tech.aiboost.coralvpn.vpn.DefaultNetworkMonitor
import java.util.Locale

/**
 * Initializes libbox once for the whole process (see docs/SPEC §6 + libbox setup.go).
 * Must run before any CommandServer is created.
 */
class CoralApp : Application() {

    override fun onCreate() {
        super.onCreate()

        runCatching { Libbox.setLocale(Locale.getDefault().toLanguageTag()) }
            .onFailure { Log.d(TAG, "setLocale: ${it.message}") }

        val baseDir = filesDir.also { it.mkdirs() }
        val tempDir = cacheDir.also { it.mkdirs() }

        val options = SetupOptions().also {
            it.basePath = baseDir.path
            it.workingPath = baseDir.path
            it.tempPath = tempDir.path
            it.fixAndroidStack = false
            it.logMaxLines = 3000
            it.debug = BuildConfig.DEBUG
            it.crashReportSource = "CoralVPN"
            it.appVersion = BuildConfig.VERSION_CODE.toString()
            it.appMarketingVersion = BuildConfig.VERSION_NAME
            it.oomKillerEnabled = false
            it.oomKillerDisabled = false
            it.oomMemoryLimit = 0L
            it.powerReportEnabled = false
            it.platformMetadata = JSONObject().apply {
                put("os", "Android " + Build.VERSION.RELEASE)
                put("sdk", Build.VERSION.SDK_INT)
                put("manufacturer", Build.MANUFACTURER)
                put("model", Build.MODEL)
            }.toString()
        }
        runCatching { Libbox.setup(options) }
            .onFailure { Log.e(TAG, "libbox setup failed", it) }

        DefaultNetworkMonitor.init(applicationContext)
    }

    companion object {
        private const val TAG = "CoralApp"
    }
}
