package com.rezerv.app.notifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.rezerv.app.AppContainer
import com.rezerv.app.R

class GhostLinkFirebaseMessagingService : FirebaseMessagingService() {

    override fun onCreate() {
        super.onCreate()
        AppContainer.init(applicationContext)
        NotificationChannels.ensureCreated(applicationContext)
    }

    override fun onNewToken(token: String) {
        PushManager.onNewToken(applicationContext, token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val payload = parsePayload(remoteMessage) ?: return
        if (ActiveChatTracker.isActive(payload.chatId)) {
            MessageNotificationHelper.cancelChatNotification(applicationContext, payload.chatId)
            return
        }
        MessageNotificationHelper.showIncomingMessage(applicationContext, payload)
    }

    private fun parsePayload(remoteMessage: RemoteMessage): IncomingPushMessage? {
        val data = remoteMessage.data
        val chatId = data["chatId"]?.trim().orEmpty()
        if (chatId.isBlank()) return null

        val title = data["chatTitle"]?.trim().orEmpty()
            .ifBlank { remoteMessage.notification?.title.orEmpty() }
            .ifBlank { getString(R.string.app_name) }
        val text = data["text"]?.trim().orEmpty()
            .ifBlank { remoteMessage.notification?.body.orEmpty() }
            .ifBlank { getString(R.string.message_hint) }
        val memberCount = data["memberCount"]?.toIntOrNull()?.coerceAtLeast(1) ?: 2
        val isGroup = parseBooleanFlag(data["isGroup"])
        val peerUid = data["peerUid"]?.trim()?.ifBlank { null }
        val peerLogin = data["peerLogin"]?.trim()?.ifBlank { null }
        val peerDisplayName = data["peerDisplayName"]?.trim()?.ifBlank { null }

        return IncomingPushMessage(
            chatId = chatId,
            chatTitle = title,
            messageText = text,
            memberCount = memberCount,
            isGroup = isGroup,
            peerUid = peerUid,
            peerLogin = peerLogin,
            peerDisplayName = peerDisplayName
        )
    }

    private fun parseBooleanFlag(value: String?): Boolean {
        return when (value?.trim()?.lowercase()) {
            "1", "true", "yes", "y" -> true
            else -> false
        }
    }
}
