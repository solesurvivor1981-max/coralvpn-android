package tech.aiboost.coralvpn.net

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Checks GitHub Releases for a newer APK (repo is public, so no auth needed) and reports
 * the download URL for in-app update. Compares the latest release tag to the running version.
 */
class UpdateChecker(
    private val http: OkHttpClient = ConfigClient.defaultClient(),
) {
    data class Update(val version: String, val apkUrl: String, val notes: String?)

    /** @return an Update if a newer release with an APK asset exists, else null. */
    fun check(currentVersion: String): Update? {
        val req = Request.Builder()
            .url(LATEST_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", ConfigClient.USER_AGENT)
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val o = JSONObject(resp.body?.string().orEmpty())
            val tag = o.optString("tag_name").removePrefix("v").ifBlank { return null }
            if (compareVersions(tag, currentVersion) <= 0) return null
            val assets = o.optJSONArray("assets") ?: return null
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                    apkUrl = a.optString("browser_download_url").takeIf { it.isNotBlank() }
                    break
                }
            }
            val url = apkUrl ?: return null
            return Update(tag, url, o.optString("body").takeIf { it.isNotBlank() })
        }
    }

    companion object {
        const val LATEST_URL =
            "https://api.github.com/repos/solesurvivor1981-max/coralvpn-android/releases/latest"

        /** Returns >0 if a>b, 0 if equal, <0 if a<b. Numeric dotted versions (0.1.4). */
        fun compareVersions(a: String, b: String): Int {
            val pa = a.split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            val pb = b.split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            val n = maxOf(pa.size, pb.size)
            for (i in 0 until n) {
                val d = (pa.getOrElse(i) { 0 }) - (pb.getOrElse(i) { 0 })
                if (d != 0) return d
            }
            return 0
        }
    }
}
