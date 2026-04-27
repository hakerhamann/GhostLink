package com.rezerv.app.data.repository

import com.rezerv.app.data.model.UserProfile
import com.rezerv.app.network.ApiClient
import com.rezerv.app.network.ApiException
import com.rezerv.app.storage.SessionStore
import org.json.JSONObject

class AuthRepository(
    private val apiClient: ApiClient,
    private val sessionStore: SessionStore
) {

    fun currentUserId(): String? = sessionStore.currentUser()?.uid

    suspend fun currentUserProfile(): UserProfile? {
        val token = sessionStore.authToken() ?: return null
        if (token.isBlank()) return null

        return runCatching {
            val response = apiClient.get("/api/auth/me")
            val user = parseUser(response.getJSONObject("user"))
            sessionStore.saveSession(token = token, user = user)
            user
        }.getOrElse { throwable ->
            val apiError = throwable as? ApiException
            if (apiError?.statusCode == 401 || apiError?.statusCode == 403) {
                sessionStore.clearSession()
                null
            } else {
                sessionStore.currentUser()
            }
        }
    }

    suspend fun signIn(login: String, password: String): Result<UserProfile> = runCatching {
        val safeLogin = normalizeLogin(login)
        require(safeLogin.isNotBlank()) { "Введите логин" }
        require(password.isNotBlank()) { "Введите пароль" }

        val payload = JSONObject()
            .put("login", safeLogin)
            .put("password", password)

        val response = apiClient.post(path = "/api/auth/login", payload = payload, withAuth = false)
        val token = response.getString("token")
        val user = parseUser(response.getJSONObject("user"))
        sessionStore.saveSession(token = token, user = user)
        user
    }

    suspend fun register(
        login: String,
        password: String,
        displayName: String
    ): Result<UserProfile> = runCatching {
        val safeLogin = normalizeLogin(login)
        require(safeLogin.isNotBlank()) { "Введите логин" }
        require(password.length >= 6) { "Пароль должен быть не короче 6 символов" }

        val payload = JSONObject()
            .put("login", safeLogin)
            .put("password", password)
            .put("displayName", displayName.ifBlank { safeLogin })

        val response = apiClient.post(path = "/api/auth/register", payload = payload, withAuth = false)
        val token = response.getString("token")
        val user = parseUser(response.getJSONObject("user"))
        sessionStore.saveSession(token = token, user = user)
        user
    }

    suspend fun updateDisplayName(displayName: String): Result<UserProfile> = runCatching {
        val safeName = displayName.trim()
        require(safeName.isNotBlank()) { "Введите отображаемое имя" }

        val payload = JSONObject().put("displayName", safeName)
        val response = apiClient.post(path = "/api/profile", payload = payload)
        val user = parseUser(response.getJSONObject("user"))
        val token = sessionStore.authToken() ?: throw IllegalStateException("Сессия недоступна")
        sessionStore.saveSession(token = token, user = user)
        user
    }

    suspend fun uploadAvatar(imageBytes: ByteArray): Result<UserProfile> = runCatching {
        require(imageBytes.isNotEmpty()) { "Пустой файл аватара" }

        val response = apiClient.uploadAvatar(imageBytes = imageBytes)
        val user = parseUser(response.getJSONObject("user"))
        val token = sessionStore.authToken() ?: throw IllegalStateException("Сессия недоступна")
        sessionStore.saveSession(token = token, user = user)
        user
    }

    suspend fun updateFcmToken(token: String) {
        val safeToken = token.trim()
        if (safeToken.isBlank()) return

        val payload = JSONObject().put("token", safeToken)
        apiClient.post(path = "/api/push/register", payload = payload)
        sessionStore.setFcmSyncedToken(safeToken)
    }

    suspend fun unregisterFcmToken(token: String) {
        val safeToken = token.trim()
        if (safeToken.isBlank()) return

        val payload = JSONObject().put("token", safeToken)
        runCatching {
            apiClient.post(path = "/api/push/unregister", payload = payload)
        }
        sessionStore.setFcmSyncedToken(null)
    }

    fun signOut() {
        sessionStore.clearSession()
    }

    private fun parseUser(raw: JSONObject): UserProfile {
        return UserProfile(
            uid = raw.optString("uid"),
            login = raw.optString("login"),
            displayName = raw.optString("displayName").ifBlank { raw.optString("login") },
            avatarUrl = raw.optString("avatarUrl").ifBlank { null }
        )
    }

    private fun normalizeLogin(login: String): String {
        return login.trim().lowercase()
    }
}
