package tech.aiboost.coralvpn.net

/**
 * Subscription metadata parsed from sub-server response headers.
 * See docs/SPEC-CLIENT-ANDROID.md §4.3. The client never invents these values —
 * they come straight from the server on every config refresh.
 */
data class SubscriptionInfo(
    val profileTitle: String? = null,
    val updateIntervalHours: Long? = null,
    val upload: Long? = null,
    val download: Long? = null,
    val total: Long? = null,
    val expireEpochSeconds: Long? = null,
    val supportUrl: String? = null,
) {
    val isExpired: Boolean
        get() = expireEpochSeconds?.let { it > 0 && it * 1000L < System.currentTimeMillis() } ?: false

    val usedBytes: Long?
        get() = if (upload != null || download != null) (upload ?: 0L) + (download ?: 0L) else null

    val remainingBytes: Long?
        get() {
            val t = total ?: return null
            val used = usedBytes ?: 0L
            return (t - used).coerceAtLeast(0L)
        }

    companion object {
        /** Parses `Subscription-Userinfo: upload=..; download=..; total=..; expire=<unix>`. */
        fun fromHeaders(
            profileTitle: String?,
            updateInterval: String?,
            userinfo: String?,
            supportUrl: String?,
        ): SubscriptionInfo {
            val kv = parseUserinfo(userinfo)
            return SubscriptionInfo(
                profileTitle = profileTitle?.takeIf { it.isNotBlank() },
                updateIntervalHours = updateInterval?.trim()?.toLongOrNull(),
                upload = kv["upload"],
                download = kv["download"],
                total = kv["total"],
                expireEpochSeconds = kv["expire"],
                supportUrl = supportUrl?.takeIf { it.isNotBlank() },
            )
        }

        private fun parseUserinfo(header: String?): Map<String, Long> {
            if (header.isNullOrBlank()) return emptyMap()
            val out = HashMap<String, Long>()
            for (part in header.split(";")) {
                val idx = part.indexOf('=')
                if (idx <= 0) continue
                val key = part.substring(0, idx).trim().lowercase()
                val value = part.substring(idx + 1).trim().toLongOrNull() ?: continue
                out[key] = value
            }
            return out
        }
    }
}
