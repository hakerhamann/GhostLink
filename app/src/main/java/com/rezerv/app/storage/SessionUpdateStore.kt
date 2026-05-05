package com.rezerv.app.storage

import android.content.SharedPreferences

internal class SessionUpdateStore(
    private val prefs: SharedPreferences,
    private val availableCodeKey: String,
    private val availableNameKey: String,
    private val lastSeenCodeKey: String,
    private val downloadedCodeKey: String,
    private val downloadedPathKey: String,
    private val updatesInfoCacheKey: String
) {
    fun setAvailableUpdate(versionCode: Int, versionName: String?) {
        prefs.edit()
            .putInt(availableCodeKey, versionCode)
            .putString(availableNameKey, versionName)
            .apply()
    }

    fun availableUpdateVersionCode(): Int = prefs.getInt(availableCodeKey, 0)

    fun availableUpdateVersionName(): String? = prefs.getString(availableNameKey, null)

    fun clearAvailableUpdate() {
        prefs.edit()
            .remove(availableCodeKey)
            .remove(availableNameKey)
            .apply()
    }

    fun markUpdateSeen(versionCode: Int) {
        prefs.edit().putInt(lastSeenCodeKey, versionCode).apply()
    }

    fun lastSeenUpdateCode(): Int = prefs.getInt(lastSeenCodeKey, 0)

    fun setDownloadedUpdate(versionCode: Int, filePath: String) {
        prefs.edit()
            .putInt(downloadedCodeKey, versionCode)
            .putString(downloadedPathKey, filePath)
            .apply()
    }

    fun downloadedUpdateVersionCode(): Int = prefs.getInt(downloadedCodeKey, 0)

    fun downloadedUpdatePath(): String? = prefs.getString(downloadedPathKey, null)

    fun clearDownloadedUpdate() {
        prefs.edit()
            .remove(downloadedCodeKey)
            .remove(downloadedPathKey)
            .apply()
    }

    fun setUpdatesInfoCache(rawJson: String) {
        if (rawJson.isBlank()) return
        prefs.edit().putString(updatesInfoCacheKey, rawJson).apply()
    }

    fun updatesInfoCache(): String? = prefs.getString(updatesInfoCacheKey, null)

    fun clearUpdatesInfoCache() {
        prefs.edit().remove(updatesInfoCacheKey).apply()
    }
}
