package com.rezerv.app.data.model

data class ChatMessage(
    val id: String = "",
    val senderId: String = "",
    val senderName: String = "",
    val senderAvatarUrl: String? = null,
    val text: String = "",
    val type: MessageType = MessageType.TEXT,
    val voiceUrl: String? = null,
    val voiceDurationSec: Int = 0,
    val imageUrl: String? = null,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val imageUrls: List<String> = emptyList(),
    val imageWidths: List<Int> = emptyList(),
    val imageHeights: List<Int> = emptyList(),
    val videoUrl: String? = null,
    val videoThumbnailUrl: String? = null,
    val localVideoPath: String? = null,
    val uploadProgress: Float? = null,
    val videoDurationSec: Int = 0,
    val replyToMessageId: String? = null,
    val replyToSenderName: String? = null,
    val replyToText: String? = null,
    val replyToImageUrl: String? = null,
    val timestamp: Long = 0L,
    val deliveredBy: List<String> = emptyList(),
    val readBy: List<String> = emptyList(),
    val edited: Boolean = false,
    val sendState: MessageSendState = MessageSendState.SENT
)

enum class MessageType {
    TEXT,
    VOICE,
    IMAGE,
    VIDEO,
    SYSTEM,
    SYSTEM_AVATAR
}

enum class MessageSendState {
    SENDING,
    FAILED,
    SENT
}
