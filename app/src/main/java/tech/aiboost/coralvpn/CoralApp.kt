package tech.aiboost.coralvpn

import android.app.Application
import android.util.Log
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import tech.aiboost.coralvpn.vpn.DefaultNetworkMonitor
import tech.aiboost.coralvpn.vpn.LogStore
import java.io.File
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

        surfaceCoreCrash(baseDir)

        DefaultNetworkMonitor.init(applicationContext)
    }

    /**
     * Native Go panics inside the core don't reach the JVM crash handler — libbox redirects
     * them to workingPath/CrashReport-<source>.log and archives them under crash_reports/ on
     * the next Libbox.setup(). Surface the latest so the panic+stack is visible without adb.
     */
    private fun surfaceCoreCrash(workingDir: File) {
        runCatching {
            val live = File(workingDir, "CrashReport-CoralVPN.log")
            if (live.exists() && live.length() > 0) {
                LogStore.log("═══ CORE CRASH (live) ═══\n" + live.readText().take(4000))
            }
            val reports = File(workingDir, "crash_reports")
            if (reports.isDirectory) {
                reports.walkTopDown()
                    .filter { it.isFile && (it.name == "go.log" || it.name.endsWith(".log")) }
                    .maxByOrNull { it.lastModified() }
                    ?.let { LogStore.log("═══ CORE CRASH (${it.parentFile?.name}) ═══\n" + it.readText().take(4000)) }
            }
        }
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
