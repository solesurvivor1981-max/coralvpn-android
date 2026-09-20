package tech.aiboost.coralvpn.net

import org.json.JSONObject

/**
 * Reads the server list from the CORALVPN selector in a sing-box config, for the UI picker.
 * This is read-only — the config is still handed to libbox unchanged (see the invariant in
 * CLAUDE.md). We only need the human-named outbound tags to let the user pick a country.
 */
object ConfigParser {
    const val GROUP_TAG = "CORALVPN"

    data class Servers(val group: String, val tags: List<String>, val default: String?)

    fun parseServers(configJson: String?): Servers? {
        if (configJson.isNullOrBlank()) return null
        return runCatching {
            val outbounds = JSONObject(configJson).optJSONArray("outbounds") ?: return null
            for (i in 0 until outbounds.length()) {
                val o = outbounds.optJSONObject(i) ?: continue
                if (o.optString("type") == "selector" && o.optString("tag") == GROUP_TAG) {
                    val arr = o.optJSONArray("outbounds") ?: return null
                    val tags = ArrayList<String>(arr.length())
                    for (j in 0 until arr.length()) arr.optString(j).takeIf { it.isNotBlank() }?.let(tags::add)
                    val default = o.optString("default").takeIf { it.isNotBlank() }
                    return@runCatching Servers(GROUP_TAG, tags, default)
                }
            }
            null
        }.getOrNull()
    }
}
