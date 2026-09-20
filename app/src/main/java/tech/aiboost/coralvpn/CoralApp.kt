package tech.aiboost.coralvpn

import android.app.Application
import android.util.Log
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import tech.aiboost.coralvpn.vpn.DefaultNetworkMonitor
import tech.aiboost.coralvpn.vpn.LogStore
import java.util.Locale

/**
 * Initializes libbox once for the whole process (see docs/SPEC §6 + libbox setup.go).
 * Must run before any CommandServer is created.
 */
class CoralApp : Application() {

    override fun onCreate() {
        super.onCreate()

        LogStore.init(this)
        installCrashLogger()

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
        }
        runCatching { Libbox.setup(options) }
            .onFailure { Log.e(TAG, "libbox setup failed", it) }

        DefaultNetworkMonitor.init(applicationContext)
    }

    /** Persist JVM crashes to the diagnostic log so the reason survives the process death. */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val sw = java.io.StringWriter()
                throwable.printStackTrace(java.io.PrintWriter(sw))
                LogStore.log("FATAL on ${thread.name}: ${sw.toString().take(2000)}")
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        private const val TAG = "CoralApp"
    }
}
