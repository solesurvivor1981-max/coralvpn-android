package tech.aiboost.coralvpn.net

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Thrown when the sub-server returns 404 — subscription not active. */
class SubscriptionInactiveException : IOException("subscription inactive (404)")

/**
 * Downloads the ready-made sing-box config from our sub-server.
 * The client does NOT parse or mutate the config — it hands the JSON to libbox as-is
 * (see docs/SPEC-CLIENT-ANDROID.md §4). Here we only fetch the body + read headers.
 */
class ConfigClient(
    private val http: OkHttpClient = defaultClient(),
) {
    data class Result(val configJson: String, val info: SubscriptionInfo)

    /** @throws SubscriptionInactiveException on 404, IOException on other failures. */
    fun fetch(subscriptionUrl: String): Result {
        val request = Request.Builder()
            .url(ensureSingboxFormat(subscriptionUrl))
            .header("User-Agent", USER_AGENT)
            .get()
            .build()

        http.newCall(request).execute().use { resp ->
            if (resp.code == 404) throw SubscriptionInactiveException()
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body?.string()
            if (body.isNullOrBlank()) throw IOException("empty config body")

            val info = SubscriptionInfo.fromHeaders(
                profileTitle = resp.header("Profile-Title"),
                updateInterval = resp.header("Profile-Update-Interval"),
                userinfo = resp.header("Subscription-Userinfo"),
                supportUrl = resp.header("Support-Url"),
            )
            return Result(body, info)
        }
    }

    companion object {
        const val USER_AGENT = "CoralVPN-Android/0.1 (sing-box)"

        /** Ensures the request asks for the sing-box format the client understands. */
        fun ensureSingboxFormat(raw: String): String {
            val url = raw.trim()
            if (url.contains("fmt=singbox")) return url
            val sep = if (url.contains('?')) '&' else '?'
            return "$url${sep}fmt=singbox"
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
