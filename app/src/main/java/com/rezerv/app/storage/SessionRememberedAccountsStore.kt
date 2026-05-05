package com.rezerv.app.storage

import android.content.SharedPreferences
import com.rezerv.app.data.model.UserProfile
import org.json.JSONArray
import org.json.JSONObject

internal class SessionRememberedAccountsStore(
    private val prefs: SharedPreferences,
    private val rememberedAccountsKey: String,
    private val normalizeServerUrl: (String) -> String
) {
    fun accounts(): List<SessionStore.RememberedAccount> {
        val raw = prefs.getString(rememberedAccountsKey, null) ?: return emptyList()
        return runCatching {
            val json = JSONArray(raw)
            val result = ArrayList<SessionStore.RememberedAccount>(json.length())
            for (index in 0 until json.length()) {
                val item = json.optJSONObject(index) ?: continue
                val token = item.optString("token").trim()
                val uid = item.optString("uid").trim()
                val login = item.optString("login").trim()
                val displayName = item.optString("displayName").ifBlank { login }
                val serverUrl = item.optString("serverUrl").trim()
                if (token.isBlank() || uid.isBlank() || login.isBlank() || serverUrl.isBlank()) {
                    continue
                }
                val avatarUrl = item.optString("avatarUrl").ifBlank { null }
                result += SessionStore.RememberedAccount(
                    uid = uid,
                    login = login,
                    displayName = displayName,
                    avatarUrl = avatarUrl,
                    token = token,
                    serverUrl = serverUrl
                )
            }
            result
        }.getOrDefault(emptyList())
    }

    fun accountsForServer(serverUrl: String): List<SessionStore.RememberedAccount> {
        val normalizedServer = normalizeServerUrl(serverUrl)
        return accounts()
            .filter { normalizeServerUrl(it.serverUrl) == normalizedServer }
            .sortedBy { it.displayName.lowercase() }
    }

    fun findAccount(login: String, serverUrl: String): SessionStore.RememberedAccount? {
        val safeLogin = login.trim().lowercase()
        val normalizedServer = normalizeServerUrl(serverUrl)
        return accounts().firstOrNull {
            it.login.equals(safeLogin, ignoreCase = true) &&
                normalizeServerUrl(it.serverUrl) == normalizedServer
        }
    }

    fun removeAccount(login: String, serverUrl: String) {
        val safeLogin = login.trim().lowercase()
        val normalizedServer = normalizeServerUrl(serverUrl)
        val updated = accounts().filterNot {
            it.login.equals(safeLogin, ignoreCase = true) &&
                normalizeServerUrl(it.serverUrl) == normalizedServer
        }
        saveAccounts(updated)
    }

    fun saveAccount(token: String, user: UserProfile, serverUrl: String) {
        val safeToken = token.trim()
        if (safeToken.isBlank()) return
        val safeServer = normalizeServerUrl(serverUrl)

        val current = accounts().toMutableList()
        current.removeAll {
            it.login.equals(user.login, ignoreCase = true) &&
                normalizeServerUrl(it.serverUrl) == safeServer
        }
        current += SessionStore.RememberedAccount(
            uid = user.uid,
            login = user.login.lowercase(),
            displayName = user.displayName.ifBlank { user.login },
            avatarUrl = user.avatarUrl,
            token = safeToken,
            serverUrl = safeServer
        )
        saveAccounts(current)
    }

    private fun saveAccounts(items: List<SessionStore.RememberedAccount>) {
        val json = JSONArray()
        items.forEach { account ->
            json.put(
                JSONObject()
                    .put("uid", account.uid)
                    .put("login", account.login)
                    .put("displayName", account.displayName)
                    .put("avatarUrl", account.avatarUrl)
                    .put("token", account.token)
                    .put("serverUrl", account.serverUrl)
            )
        }
        prefs.edit().putString(rememberedAccountsKey, json.toString()).apply()
    }
}
