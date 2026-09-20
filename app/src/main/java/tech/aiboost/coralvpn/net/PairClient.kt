package tech.aiboost.coralvpn.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * QR device-pairing client (see docs/SPEC-PAIRING.md). Lets a TV / second phone join a
 * subscription without typing: create a session, show its deep_link as a QR, poll status
 * until the bot attaches the device and returns the sub_url.
 */
class PairClient(
    private val http: OkHttpClient = ConfigClient.defaultClient(),
) {
    data class Session(
        val token: String,
        val deepLink: String,
        val code: String?,
        val expiresInSec: Long,
        val pollIntervalSec: Long,
    )

    sealed class Status {
        object Pending : Status()
        data class Ready(val subUrl: String) : Status()
        object Expired : Status()
    }

    /** POST /pair/new → session. */
    fun create(deviceId: String, kind: String, name: String?): Session {
        val payload = JSONObject()
            .put("device_id", deviceId)
            .put("kind", kind)
            .apply { if (!name.isNullOrBlank()) put("name", name) }
            .toString()
        val req = Request.Builder()
            .url("$BASE/new")
            .header("User-Agent", ConfigClient.USER_AGENT)
            .post(payload.toRequestBody(JSON))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("pair/new HTTP ${resp.code}")
            val o = JSONObject(resp.body?.string().orEmpty())
            val token = o.optString("token").ifBlank { throw IOException("no token") }
            return Session(
                token = token,
                deepLink = o.optString("deep_link").ifBlank {
                    "https://t.me/Coral_VPN_bot?start=pair_$token"
                },
                code = o.optString("code").takeIf { it.isNotBlank() },
                expiresInSec = o.optLong("expires_in", 300L),
                pollIntervalSec = o.optLong("poll_interval", 3L),
            )
        }
    }

    /** GET /pair/status?token= → status. */
    fun status(token: String): Status {
        val req = Request.Builder()
            .url("$BASE/status?token=$token")
            .header("User-Agent", ConfigClient.USER_AGENT)
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 404) return Status.Expired
            if (!resp.isSuccessful) throw IOException("pair/status HTTP ${resp.code}")
            val o = JSONObject(resp.body?.string().orEmpty())
            return when (o.optString("status")) {
                "ready" -> {
                    val sub = o.optString("sub_url")
                    if (sub.isNotBlank()) Status.Ready(sub) else Status.Pending
                }
                "expired" -> Status.Expired
                else -> Status.Pending
            }
        }
    }

    companion object {
        const val BASE = "https://sub-ru.ai-boost.tech/pair"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
