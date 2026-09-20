package tech.aiboost.coralvpn

import org.junit.Assert.assertEquals
import org.junit.Test
import tech.aiboost.coralvpn.net.ConfigClient

class ConfigUrlTest {

    @Test
    fun addsFormatWhenAbsent() {
        assertEquals(
            "https://sub-ru.ai-boost.tech/sub/abc?fmt=singbox",
            ConfigClient.ensureSingboxFormat("https://sub-ru.ai-boost.tech/sub/abc"),
        )
    }

    @Test
    fun appendsWithAmpersandWhenQueryExists() {
        assertEquals(
            "https://sub-ru.ai-boost.tech/sub/abc?token=1&fmt=singbox",
            ConfigClient.ensureSingboxFormat("https://sub-ru.ai-boost.tech/sub/abc?token=1"),
        )
    }

    @Test
    fun leavesFormatUntouchedWhenPresent() {
        val url = "https://sub-ru.ai-boost.tech/sub/abc?fmt=singbox"
        assertEquals(url, ConfigClient.ensureSingboxFormat(url))
    }

    @Test
    fun trimsWhitespace() {
        assertEquals(
            "https://sub-ru.ai-boost.tech/sub/abc?fmt=singbox",
            ConfigClient.ensureSingboxFormat("  https://sub-ru.ai-boost.tech/sub/abc  "),
        )
    }
}
