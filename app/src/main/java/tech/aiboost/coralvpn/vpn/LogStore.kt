package tech.aiboost.coralvpn.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * In-memory ring buffer of diagnostic lines, surfaced in the UI so failures are
 * visible without adb (the tunnel currently "connects then drops" and we need the
 * reason). Not for production PII — never log sub_id/UUID here.
 */
object LogStore {
    private const val MAX = 300
    private val lines = ArrayDeque<String>()

    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text

    @Synchronized
    fun log(message: String) {
        val stamp = android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis())
        lines.addLast("$stamp  $message")
        while (lines.size > MAX) lines.removeFirst()
        _text.value = lines.joinToString("\n")
    }

    @Synchronized
    fun snapshot(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() {
        lines.clear()
        _text.value = ""
    }
}
