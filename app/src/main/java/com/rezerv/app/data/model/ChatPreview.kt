package com.rezerv.app.data.model

data class ChatPreview(
    val id: String,
    val title: String,
    val avatarUrl: String? = null,
    val peerUid: String? = null,
    val peerLogin: String? = null,
    val peerDisplayName: String? = null,
    val lastMessage: String,
    val timestamp: Long,
    val unreadCount: Int = 0,
    val lastMessageOutgoing: Boolean = false,
    val lastMessageDelivered: Boolean = false,
    val lastMessageRead: Boolean = false,
    val isGroup: Boolean = false,
    val memberCount: Int = 0
)
