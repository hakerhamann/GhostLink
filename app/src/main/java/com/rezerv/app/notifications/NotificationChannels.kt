package com.rezerv.app.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import com.rezerv.app.R

object NotificationChannels {

    private const val LEGACY_MESSAGES_CHANNEL_ID = "ghostlink_messages"
    const val MESSAGES_CHANNEL_ID = "ghostlink_messages_v2"

    fun ensureCreated(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(LEGACY_MESSAGES_CHANNEL_ID) != null &&
            manager.getNotificationChannel(MESSAGES_CHANNEL_ID) == null
        ) {
            manager.deleteNotificationChannel(LEGACY_MESSAGES_CHANNEL_ID)
        }

        val existing = manager.getNotificationChannel(MESSAGES_CHANNEL_ID)
        if (existing != null) return

        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val channel = NotificationChannel(
            MESSAGES_CHANNEL_ID,
            context.getString(R.string.notifications_messages_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.notifications_messages_channel_description)
            enableVibration(true)
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            setSound(soundUri, audioAttributes)
        }
        manager.createNotificationChannel(channel)
    }
}
