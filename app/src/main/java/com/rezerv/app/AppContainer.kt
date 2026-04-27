package com.rezerv.app

import android.content.Context
import com.rezerv.app.data.repository.AuthRepository
import com.rezerv.app.data.repository.ChatRepository
import com.rezerv.app.data.repository.UpdateRepository
import com.rezerv.app.network.ApiClient
import com.rezerv.app.storage.SessionStore

object AppContainer {
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    val sessionStore: SessionStore by lazy {
        SessionStore(appContext)
    }

    private val apiClient: ApiClient by lazy {
        ApiClient(sessionStore)
    }

    val authRepository: AuthRepository by lazy {
        AuthRepository(apiClient = apiClient, sessionStore = sessionStore)
    }

    val chatRepository: ChatRepository by lazy {
        ChatRepository(apiClient = apiClient, sessionStore = sessionStore)
    }

    val updateRepository: UpdateRepository by lazy {
        UpdateRepository(
            context = appContext,
            apiClient = apiClient,
            sessionStore = sessionStore
        )
    }
}
