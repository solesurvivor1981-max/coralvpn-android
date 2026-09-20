package tech.aiboost.coralvpn.util

import java.text.DateFormat
import java.util.Date
import java.util.Locale

object Formats {
    fun bytes(value: Long?): String {
        if (value == null) return "—"
        if (value < 1024) return "$value B"
        val units = arrayOf("KB", "MB", "GB", "TB", "PB")
        var v = value.toDouble()
        var i = -1
        while (v >= 1024 && i < units.lastIndex) {
            v /= 1024.0
            i++
        }
        return String.format(Locale.US, "%.1f %s", v, units[i])
    }

    fun date(epochSeconds: Long?): String {
        if (epochSeconds == null || epochSeconds <= 0) return "—"
        return DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochSeconds * 1000L))
    }
}
