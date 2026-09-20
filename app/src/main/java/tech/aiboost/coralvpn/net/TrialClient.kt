package tech.aiboost.coralvpn.net

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import tech.aiboost.coralvpn.vpn.LogStore
import java.io.IOException
import java.net.URLEncoder

/** Thrown when the trial was already used / limit reached (server returns 403/409/429). */
class TrialUsedException : IOException("trial already used")

/** Thrown when the trial server itself errors (5xx) — not the user's network. */
class TrialServerException(val code: Int) : IOException("trial server error $code")

/**
 * Requests a personal trial subscription so the app works without the bot (see docs/SPEC).
 * Server contract: GET /trial?device={id} → 200 JSON {"sub_url": "...", "expire": <unix>}
 * (also tolerates a plain sub-link body). Returns the subscription URL to use like a pasted one.
 */
class TrialClient(
    private val http: OkHttpClient = ConfigClient.defaultClient(),
) {
    /** @return subscription URL. @throws TrialUsedException / IOException. */
    fun requestTrial(deviceId: String): String {
        val url = "$TRIAL_URL?device=" + URLEncoder.encode(deviceId, "UTF-8")
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", ConfigClient.USER_AGENT)
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            LogStore.log("trial: HTTP ${resp.code}")
            if (resp.code == 403 || resp.code == 409 || resp.code == 429) throw TrialUsedException()
            if (resp.code in 500..599) throw TrialServerException(resp.code)
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body?.string()?.trim().orEmpty()
            if (body.isEmpty()) throw IOException("empty trial response")
            return if (body.startsWith("{")) {
                JSONObject(body).optString("sub_url").takeIf { it.isNotBlank() }
                    ?: throw IOException("trial response has no sub_url")
            } else {
                body // plain sub link fallback
            }
        }
    }

    companion object {
        const val TRIAL_URL = "https://sub-ru.ai-boost.tech/trial"
    }
}
