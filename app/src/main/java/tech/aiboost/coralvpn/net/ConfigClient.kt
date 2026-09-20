package tech.aiboost.coralvpn.net

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import tech.aiboost.coralvpn.vpn.LogStore
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Thrown when the sub-server returns 404 — subscription not active. */
class SubscriptionInactiveException : IOException("subscription inactive (404)")

/** Thrown when the server returns something that isn't a sing-box JSON config. */
class NotSingboxConfigException(val classification: String) :
    IOException("server did not return sing-box JSON ($classification)")

/**
 * Downloads the ready-made sing-box config from our sub-server.
 * The client does NOT parse or mutate the config — it hands the JSON to libbox as-is
 * (see docs/SPEC-CLIENT-ANDROID.md §4). Here we only fetch the body + read headers,
 * and sanity-check that we actually got JSON (not a raw hysteria2:// / base64 subscription).
 */
class ConfigClient(
    private val http: OkHttpClient = defaultClient(),
) {
    data class Result(val configJson: String, val info: SubscriptionInfo)

    /** @throws SubscriptionInactiveException on 404, NotSingboxConfigException on wrong format. */
    fun fetch(subscriptionUrl: String): Result {
        val url = ensureSingboxFormat(subscriptionUrl)
        LogStore.log("fetch: GET ${redact(url)}")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()

        http.newCall(request).execute().use { resp ->
            LogStore.log("fetch: HTTP ${resp.code} ct=${resp.header("Content-Type") ?: "-"}")
            if (resp.code == 404) throw SubscriptionInactiveException()
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body?.string()
            if (body.isNullOrBlank()) throw IOException("empty config body")

            val trimmed = body.trimStart('﻿', ' ', '\n', '\r', '\t')
            if (!trimmed.startsWith("{")) {
                val cls = classify(trimmed)
                LogStore.log("fetch: NOT sing-box JSON — $cls (len=${body.length})")
                throw NotSingboxConfigException(cls)
            }

            val info = SubscriptionInfo.fromHeaders(
                profileTitle = resp.header("Profile-Title"),
                updateInterval = resp.header("Profile-Update-Interval"),
                userinfo = resp.header("Subscription-Userinfo"),
                supportUrl = resp.header("Support-Url"),
            )
            LogStore.log("fetch: OK sing-box JSON (${body.length}B)")
            return Result(body, info)
        }
    }

    companion object {
        const val USER_AGENT = "sing-box CoralVPN-Android/0.1"

        /**
         * Forces `fmt=singbox`, stripping any conflicting/duplicate `fmt` the pasted link
         * already had (the bot exposes per-client links; a wrong one carries e.g. fmt=clash).
         * Falls back to naive append if the URL can't be parsed.
         */
        fun ensureSingboxFormat(raw: String): String {
            val cleaned = raw.trim()
            val parsed = cleaned.toHttpUrlOrNull()
                ?: return if (cleaned.contains("fmt=singbox")) cleaned
                else cleaned + (if (cleaned.contains('?')) "&" else "?") + "fmt=singbox"
            return parsed.newBuilder()
                .removeAllQueryParameters("fmt")
                .addQueryParameter("fmt", "singbox")
                .build()
                .toString()
        }

        /** Redacts the {sub_id} path segment for safe logging. */
        private fun redact(url: String): String =
            Regex("/sub/[^/?#]+").replace(url, "/sub/***")

        /** Coarse, secret-safe description of a non-JSON body (scheme prefix only). */
        private fun classify(body: String): String {
            val head = body.take(12)
            val scheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").find(head)?.value
            return when {
                scheme != null -> "looks like a $scheme subscription URI"
                head.startsWith("<") -> "looks like HTML"
                head.startsWith("#") -> "looks like a comment/plain list"
                else -> "starts with '${head.firstOrNull() ?: ' '}' (maybe base64/plain)"
            }
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
