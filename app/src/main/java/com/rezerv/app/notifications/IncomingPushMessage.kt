package com.rezerv.app.notifications

data class IncomingPushMessage(
    val chatId: String,
    val chatTitle: String,
    val messageText: String,
    val memberCount: Int,
    val isGroup: Boolean,
    val peerUid: String?,
    val peerLogin: String?,
    val peerDisplayName: String?
)
