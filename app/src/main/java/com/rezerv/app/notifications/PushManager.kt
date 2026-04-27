package com.rezerv.app.notifications

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.rezerv.app.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

object PushManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        AppContainer.init(appContext)
        ensureFirebaseInitialized(appContext)
        NotificationChannels.ensureCreated(appContext)
        syncTokenNow(appContext, force = false)
    }

    fun syncTokenNow(context: Context, force: Boolean = true) {
        val appContext = context.applicationContext
        AppContainer.init(appContext)
        ensureFirebaseInitialized(appContext)
        scope.launch {
            val token = runCatching { FirebaseMessaging.getInstance().token.await() }.getOrNull()
            if (!token.isNullOrBlank()) {
                syncResolvedToken(token = token, force = force)
            }
        }
    }

    fun onNewToken(context: Context, token: String) {
        val appContext = context.applicationContext
        AppContainer.init(appContext)
        ensureFirebaseInitialized(appContext)
        scope.launch {
            syncResolvedToken(token = token, force = true)
        }
    }

    suspend fun unregisterTokenBeforeSignOut() {
        val auth = AppContainer.sessionStore.authToken()
        if (auth.isNullOrBlank()) return

        val localToken = AppContainer.sessionStore.fcmLocalToken()?.trim().orEmpty()
        if (localToken.isBlank()) return

        AppContainer.authRepository.unregisterFcmToken(localToken)
    }

    private suspend fun syncResolvedToken(token: String, force: Boolean) {
        val safeToken = token.trim()
        if (safeToken.isBlank()) return

        AppContainer.sessionStore.setFcmLocalToken(safeToken)
        val auth = AppContainer.sessionStore.authToken()
        if (auth.isNullOrBlank()) return

        val syncedToken = AppContainer.sessionStore.fcmSyncedToken()
        if (!force && syncedToken == safeToken) return

        runCatching {
            AppContainer.authRepository.updateFcmToken(safeToken)
        }
    }

    private fun ensureFirebaseInitialized(context: Context) {
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(context)
        }
    }
}
