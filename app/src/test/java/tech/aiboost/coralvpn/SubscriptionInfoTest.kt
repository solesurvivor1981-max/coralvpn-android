package tech.aiboost.coralvpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.aiboost.coralvpn.net.SubscriptionInfo

class SubscriptionInfoTest {

    @Test
    fun parsesFullUserinfoHeader() {
        val info = SubscriptionInfo.fromHeaders(
            profileTitle = "AI Boost Tech VPN",
            updateInterval = "1",
            userinfo = "upload=100; download=200; total=1000; expire=4102444800",
            supportUrl = "https://t.me/Coral_VPN_bot",
        )
        assertEquals("AI Boost Tech VPN", info.profileTitle)
        assertEquals(1L, info.updateIntervalHours)
        assertEquals(100L, info.upload)
        assertEquals(200L, info.download)
        assertEquals(1000L, info.total)
        assertEquals(4102444800L, info.expireEpochSeconds)
        assertEquals(300L, info.usedBytes)
        assertEquals(700L, info.remainingBytes)
    }

    @Test
    fun handlesMissingAndBlankHeaders() {
        val info = SubscriptionInfo.fromHeaders(null, null, null, "")
        assertNull(info.profileTitle)
        assertNull(info.updateIntervalHours)
        assertNull(info.total)
        assertNull(info.usedBytes)
        assertNull(info.remainingBytes)
        assertNull(info.supportUrl)
        assertFalse(info.isExpired)
    }

    @Test
    fun toleratesPartialAndMessyUserinfo() {
        val info = SubscriptionInfo.fromHeaders(
            profileTitle = "  ",
            updateInterval = "abc",
            userinfo = "download=500 ;  total = 2000 ; junk; upload=",
            supportUrl = null,
        )
        assertNull(info.profileTitle) // blank -> null
        assertNull(info.updateIntervalHours) // unparasable -> null
        assertEquals(500L, info.download)
        assertEquals(2000L, info.total)
        assertNull(info.upload) // "upload=" has no value
        assertEquals(500L, info.usedBytes)
        assertEquals(1500L, info.remainingBytes)
    }

    @Test
    fun remainingNeverNegative() {
        val info = SubscriptionInfo.fromHeaders(
            profileTitle = null,
            updateInterval = null,
            userinfo = "upload=800; download=800; total=1000",
            supportUrl = null,
        )
        assertEquals(0L, info.remainingBytes)
    }

    @Test
    fun expiredWhenExpireInPast() {
        val past = (System.currentTimeMillis() / 1000L) - 3600L
        val info = SubscriptionInfo.fromHeaders(null, null, "expire=$past", null)
        assertTrue(info.isExpired)
    }

    @Test
    fun notExpiredWhenExpireInFuture() {
        val future = (System.currentTimeMillis() / 1000L) + 3600L
        val info = SubscriptionInfo.fromHeaders(null, null, "expire=$future", null)
        assertFalse(info.isExpired)
    }

    @Test
    fun zeroExpireIsNotExpired() {
        val info = SubscriptionInfo.fromHeaders(null, null, "expire=0", null)
        assertFalse(info.isExpired)
    }
}
