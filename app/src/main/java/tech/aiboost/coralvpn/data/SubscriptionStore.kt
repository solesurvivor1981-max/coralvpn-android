package tech.aiboost.coralvpn.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import tech.aiboost.coralvpn.net.SubscriptionInfo

/**
 * Persists the subscription URL, cached config and last-known subscription info.
 * The URL/UUID are secrets — stored in EncryptedSharedPreferences and never logged
 * (see docs/SPEC-CLIENT-ANDROID.md §6).
 */
class SubscriptionStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "coralvpn_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var subscriptionUrl: String?
        get() = prefs.getString(KEY_URL, null)
        set(value) = prefs.edit().putString(KEY_URL, value).apply()

    var cachedConfig: String?
        get() = prefs.getString(KEY_CONFIG, null)
        set(value) = prefs.edit().putString(KEY_CONFIG, value).apply()

    var lastRefreshEpochMs: Long
        get() = prefs.getLong(KEY_LAST_REFRESH, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_REFRESH, value).apply()

    val hasSubscription: Boolean
        get() = !subscriptionUrl.isNullOrBlank()

    fun saveInfo(info: SubscriptionInfo) {
        prefs.edit().apply {
            putString(KEY_TITLE, info.profileTitle)
            putString(KEY_SUPPORT_URL, info.supportUrl)
            info.updateIntervalHours?.let { putLong(KEY_INTERVAL, it) } ?: remove(KEY_INTERVAL)
            info.upload?.let { putLong(KEY_UPLOAD, it) } ?: remove(KEY_UPLOAD)
            info.download?.let { putLong(KEY_DOWNLOAD, it) } ?: remove(KEY_DOWNLOAD)
            info.total?.let { putLong(KEY_TOTAL, it) } ?: remove(KEY_TOTAL)
            info.expireEpochSeconds?.let { putLong(KEY_EXPIRE, it) } ?: remove(KEY_EXPIRE)
            apply()
        }
    }

    fun loadInfo(): SubscriptionInfo = SubscriptionInfo(
        profileTitle = prefs.getString(KEY_TITLE, null),
        updateIntervalHours = prefs.getLongOrNull(KEY_INTERVAL),
        upload = prefs.getLongOrNull(KEY_UPLOAD),
        download = prefs.getLongOrNull(KEY_DOWNLOAD),
        total = prefs.getLongOrNull(KEY_TOTAL),
        expireEpochSeconds = prefs.getLongOrNull(KEY_EXPIRE),
        supportUrl = prefs.getString(KEY_SUPPORT_URL, null),
    )

    fun clear() = prefs.edit().clear().apply()

    private fun SharedPreferences.getLongOrNull(key: String): Long? =
        if (contains(key)) getLong(key, 0L) else null

    companion object {
        private const val KEY_URL = "sub_url"
        private const val KEY_CONFIG = "config_json"
        private const val KEY_LAST_REFRESH = "last_refresh_ms"
        private const val KEY_TITLE = "info_title"
        private const val KEY_SUPPORT_URL = "info_support_url"
        private const val KEY_INTERVAL = "info_interval_h"
        private const val KEY_UPLOAD = "info_upload"
        private const val KEY_DOWNLOAD = "info_download"
        private const val KEY_TOTAL = "info_total"
        private const val KEY_EXPIRE = "info_expire"
    }
}
