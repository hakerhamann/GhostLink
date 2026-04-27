package com.rezerv.app.notifications

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.rezerv.app.R
import com.rezerv.app.chat.ChatActivity
import com.rezerv.app.data.model.ChatPreview

object MessageNotificationHelper {

    private const val CHAT_NOTIFICATION_ID = 1001
    private const val CHAT_NOTIFICATION_TAG_PREFIX = "chat:"

    fun showIncomingMessage(context: Context, chat: ChatPreview) {
        val payload = IncomingPushMessage(
            chatId = chat.id,
            chatTitle = chat.title,
            messageText = chat.lastMessage.ifBlank { context.getString(R.string.message_hint) },
            memberCount = chat.memberCount,
            isGroup = chat.isGroup,
            peerUid = chat.peerUid,
            peerLogin = chat.peerLogin,
            peerDisplayName = chat.peerDisplayName
        )
        showIncomingMessage(context, payload)
    }

    fun showIncomingMessage(context: Context, payload: IncomingPushMessage) {
        if (!hasNotificationPermission(context)) return

        val safeChatId = payload.chatId.trim()
        if (safeChatId.isBlank()) return

        NotificationChannels.ensureCreated(context)
        val pendingIntent = PendingIntent.getActivity(
            context,
            safeChatId.hashCode(),
            Intent(context, ChatActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(ChatActivity.EXTRA_CHAT_ID, safeChatId)
                putExtra(ChatActivity.EXTRA_CHAT_TITLE, payload.chatTitle)
                putExtra(ChatActivity.EXTRA_CHAT_MEMBER_COUNT, payload.memberCount)
                putExtra(ChatActivity.EXTRA_CHAT_IS_GROUP, payload.isGroup)
                putExtra(ChatActivity.EXTRA_CHAT_PEER_UID, payload.peerUid)
                putExtra(ChatActivity.EXTRA_CHAT_PEER_LOGIN, payload.peerLogin)
                putExtra(ChatActivity.EXTRA_CHAT_PEER_DISPLAY_NAME, payload.peerDisplayName)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = payload.messageText.ifBlank { context.getString(R.string.message_hint) }
        val notification = NotificationCompat.Builder(context, NotificationChannels.MESSAGES_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_app)
            .setContentTitle(context.getString(R.string.notifications_new_message_title, payload.chatTitle))
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(notificationTag(safeChatId), CHAT_NOTIFICATION_ID, notification)
    }

    fun cancelChatNotification(context: Context, chatId: String?) {
        val safeChatId = chatId?.trim().orEmpty()
        if (safeChatId.isBlank()) return
        val manager = NotificationManagerCompat.from(context)
        manager.cancel(notificationTag(safeChatId), CHAT_NOTIFICATION_ID)
        manager.cancel(safeChatId.hashCode())
    }

    fun cancelAllMessageNotifications(context: Context) {
        NotificationManagerCompat.from(context).cancelAll()
    }

    private fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun notificationTag(chatId: String): String = "$CHAT_NOTIFICATION_TAG_PREFIX$chatId"
}
