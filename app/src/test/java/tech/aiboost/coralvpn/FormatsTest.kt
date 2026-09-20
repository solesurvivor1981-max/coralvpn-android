package tech.aiboost.coralvpn

import org.junit.Assert.assertEquals
import org.junit.Test
import tech.aiboost.coralvpn.util.Formats

class FormatsTest {

    @Test
    fun formatsBytesAcrossUnits() {
        assertEquals("—", Formats.bytes(null))
        assertEquals("512 B", Formats.bytes(512))
        assertEquals("1.0 KB", Formats.bytes(1024))
        assertEquals("1.5 KB", Formats.bytes(1536))
        assertEquals("1.0 MB", Formats.bytes(1024L * 1024))
        assertEquals("1.0 GB", Formats.bytes(1024L * 1024 * 1024))
    }

    @Test
    fun dateHandlesEmptyValues() {
        assertEquals("—", Formats.date(null))
        assertEquals("—", Formats.date(0))
        assertEquals("—", Formats.date(-5))
    }
}
