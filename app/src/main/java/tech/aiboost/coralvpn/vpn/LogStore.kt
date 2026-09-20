package tech.aiboost.coralvpn.vpn

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Diagnostic log surfaced in the UI so failures are visible without adb.
 *
 * Persists to a file so it SURVIVES A CRASH (the tunnel currently crashes on Connect and
 * the reason is the last line before the process dies). Never log sub_id/UUID/passwords here.
 */
object LogStore {
    private const val MAX = 400
    private val lines = ArrayDeque<String>()
    private var file: File? = null

    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text

    /** Call once at app start (CoralApp). Loads the previous session's log so a crash is visible. */
    @Synchronized
    fun init(context: Context) {
        val f = File(context.filesDir, "coral-diag.log")
        file = f
        runCatching {
            if (f.exists()) {
                f.readLines().takeLast(MAX).forEach { lines.addLast(it) }
                lines.addLast("──── new session ────")
            }
        }
        _text.value = lines.joinToString("\n")
    }

    @Synchronized
    fun log(message: String) {
        val stamp = android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis())
        val line = "$stamp  $message"
        lines.addLast(line)
        while (lines.size > MAX) lines.removeFirst()
        _text.value = lines.joinToString("\n")
        // Append to disk immediately so it survives a hard/native crash.
        runCatching { file?.appendText(line + "\n") }
    }

    @Synchronized
    fun snapshot(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() {
        lines.clear()
        _text.value = ""
        runCatching { file?.writeText("") }
    }
}
