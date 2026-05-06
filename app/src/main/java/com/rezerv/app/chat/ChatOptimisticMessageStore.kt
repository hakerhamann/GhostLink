package com.rezerv.app.chat

import com.rezerv.app.data.model.ChatMessage
import com.rezerv.app.data.model.MessageSendState
import com.rezerv.app.data.model.MessageType
import com.rezerv.app.data.model.UserProfile

internal class ChatOptimisticMessageStore(
    private val replyPreviewText: (ChatMessage) -> String,
    private val replyPreviewImageUrl: (ChatMessage) -> String?,
    private val resolveMessagePhotoUrls: (ChatMessage) -> List<String>
) {
    private val localOverlayMessages = mutableListOf<ChatMessage>()
    private val localToServerMessageIds = mutableMapOf<String, String>()
    private val pendingEditedMessages = mutableMapOf<String, PendingEditedMessage>()
    private var localMessageSequence: Long = 0L

    fun hasOverlayMessages(): Boolean = localOverlayMessages.isNotEmpty()

    fun mergeMessagesForDisplay(serverMessages: List<ChatMessage>): List<ChatMessage> {
        val mergedBase = if (localOverlayMessages.isEmpty()) {
            serverMessages
        } else {
            val mappedServerIds = localToServerMessageIds.values
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toHashSet()
            val filteredServerMessages = if (mappedServerIds.isEmpty()) {
                serverMessages
            } else {
                serverMessages.filterNot { it.id in mappedServerIds }
            }
            val serverIds = filteredServerMessages.mapTo(hashSetOf()) { it.id }
            val overlay = localOverlayMessages
                .asSequence()
                .filter { it.id !in serverIds }
                .sortedBy { it.timestamp }
                .toList()
            when {
                overlay.isEmpty() -> filteredServerMessages
                filteredServerMessages.isEmpty() -> overlay
                else -> mergeByTimestamp(filteredServerMessages, overlay)
            }
        }
        return applyPendingEditedMessages(mergedBase)
    }

    fun pruneEditedMessages(serverMessages: List<ChatMessage>) {
        if (pendingEditedMessages.isEmpty()) return
        val serverById = serverMessages.associateBy { it.id }
        val iterator = pendingEditedMessages.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val pending = entry.value
            val serverMessage = serverById[pending.serverMessageId] ?: serverById[pending.replacement.id] ?: continue
            if (messagesMatchForEdit(serverMessage, pending.replacement)) {
                iterator.remove()
            }
        }
    }

    fun pruneOverlayMessages(serverMessages: List<ChatMessage>) {
        if (localOverlayMessages.isEmpty()) return
        val serverIds = serverMessages.mapTo(hashSetOf()) { it.id }
        val iterator = localOverlayMessages.iterator()
        while (iterator.hasNext()) {
            val message = iterator.next()
            if (message.id in serverIds) {
                iterator.remove()
                localToServerMessageIds.remove(message.id)
            }
        }
    }

    fun syncOverlayMessagesWithServer(serverMessages: List<ChatMessage>) {
        if (localOverlayMessages.isEmpty() || localToServerMessageIds.isEmpty()) return
        val serverById = serverMessages.associateBy { it.id }
        for (index in localOverlayMessages.indices) {
            val overlayMessage = localOverlayMessages[index]
            val mappedServerId = localToServerMessageIds[overlayMessage.id]?.trim().orEmpty()
            if (mappedServerId.isBlank()) continue
            val serverMessage = serverById[mappedServerId] ?: continue
            localOverlayMessages[index] = serverMessage.copy(
                id = overlayMessage.id,
                sendState = MessageSendState.SENT
            )
        }
    }

    fun appendOverlayMessage(message: ChatMessage) {
        localOverlayMessages += message
    }

    fun markOverlayMessageSent(localId: String, confirmedMessage: ChatMessage, latestMessages: List<ChatMessage>) {
        localToServerMessageIds[localId] = confirmedMessage.id
        val replacement = confirmedMessage.copy(id = localId, sendState = MessageSendState.SENT)
        val index = localOverlayMessages.indexOfFirst { it.id == localId }
        if (index >= 0) {
            localOverlayMessages[index] = replacement
        } else if (latestMessages.none { it.id == confirmedMessage.id }) {
            localOverlayMessages += replacement
        }
    }

    fun markOverlayMessageFailed(localId: String) {
        localToServerMessageIds.remove(localId)
        val index = localOverlayMessages.indexOfFirst { it.id == localId }
        if (index < 0) return
        localOverlayMessages[index] = localOverlayMessages[index].copy(sendState = MessageSendState.FAILED)
    }

    fun removeOverlayMessage(rawId: String?): Boolean {
        val normalizedId = rawId?.trim().orEmpty()
        if (normalizedId.isBlank() || localOverlayMessages.isEmpty()) return false
        var removed = false
        val iterator = localOverlayMessages.iterator()
        while (iterator.hasNext()) {
            val overlayMessage = iterator.next()
            val mappedServerId = localToServerMessageIds[overlayMessage.id]?.trim().orEmpty()
            if (overlayMessage.id == normalizedId || (mappedServerId.isNotBlank() && mappedServerId == normalizedId)) {
                iterator.remove()
                localToServerMessageIds.remove(overlayMessage.id)
                removed = true
            }
        }
        return removed
    }

    fun resolveServerMessageId(rawId: String?): String? {
        val id = rawId?.trim().orEmpty()
        if (id.isBlank()) return null
        if (!id.startsWith("local:", ignoreCase = true)) return id
        return localToServerMessageIds[id]?.trim().orEmpty().ifBlank { null }
    }

    fun putPendingEditedMessage(messageId: String, serverMessageId: String, replacement: ChatMessage) {
        pendingEditedMessages[messageId] = PendingEditedMessage(
            serverMessageId = serverMessageId,
            replacement = replacement
        )
    }

    fun removePendingEditedMessage(messageId: String) {
        pendingEditedMessages.remove(messageId)
    }

    fun buildOptimisticTextMessage(
        user: UserProfile,
        text: String,
        replyTarget: ChatMessage?
    ): ChatMessage {
        return buildOptimisticBaseMessage(
            user = user,
            type = MessageType.TEXT,
            text = text,
            replyTarget = replyTarget
        )
    }

    fun buildOptimisticVoiceMessage(
        user: UserProfile,
        durationSec: Int,
        replyTarget: ChatMessage?
    ): ChatMessage {
        return buildOptimisticBaseMessage(
            user = user,
            type = MessageType.VOICE,
            text = VOICE_PREVIEW_TEXT,
            replyTarget = replyTarget
        ).copy(
            voiceDurationSec = durationSec.coerceAtLeast(0)
        )
    }

    fun buildOptimisticImageMessage(
        user: UserProfile,
        imageUrls: List<String>,
        imageWidths: List<Int> = emptyList(),
        imageHeights: List<Int> = emptyList(),
        caption: String,
        replyTarget: ChatMessage?
    ): ChatMessage {
        val message = buildOptimisticBaseMessage(
            user = user,
            type = MessageType.IMAGE,
            text = caption.ifBlank { PHOTO_PREVIEW_TEXT },
            replyTarget = replyTarget
        )
        val safeImageUrls = imageUrls.mapIndexed { index, value ->
            value.ifBlank { "pending://image/${message.id}/$index" }
        }
        return message.copy(
            imageUrl = safeImageUrls.firstOrNull() ?: "pending://image/${message.id}",
            imageUrls = safeImageUrls,
            imageWidth = imageWidths.firstOrNull()?.coerceAtLeast(0) ?: 0,
            imageHeight = imageHeights.firstOrNull()?.coerceAtLeast(0) ?: 0,
            imageWidths = imageWidths.map { it.coerceAtLeast(0) },
            imageHeights = imageHeights.map { it.coerceAtLeast(0) }
        )
    }

    fun buildOptimisticVideoMessage(
        user: UserProfile,
        durationSec: Int,
        replyTarget: ChatMessage?,
        localVideoPath: String? = null
    ): ChatMessage {
        val message = buildOptimisticBaseMessage(
            user = user,
            type = MessageType.VIDEO,
            text = VIDEO_PREVIEW_TEXT,
            replyTarget = replyTarget
        )
        return message.copy(
            videoUrl = "pending://video/${message.id}",
            localVideoPath = localVideoPath?.trim()?.takeIf { it.isNotBlank() },
            videoDurationSec = durationSec.coerceAtLeast(0)
        )
    }

    fun buildOptimisticEditedMessage(
        message: ChatMessage,
        newText: String,
        replacementPhotoSources: List<String>?
    ): ChatMessage {
        return when (message.type) {
            MessageType.IMAGE -> {
                val photoUrls = replacementPhotoSources
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?.takeIf { it.isNotEmpty() }
                    ?: resolveMessagePhotoUrls(message)
                message.copy(
                    text = newText,
                    imageUrl = photoUrls.firstOrNull(),
                    imageUrls = photoUrls,
                    imageWidths = message.imageWidths,
                    imageHeights = message.imageHeights,
                    edited = message.edited
                )
            }
            else -> message.copy(
                text = newText,
                edited = message.edited
            )
        }
    }

    private fun mergeByTimestamp(
        filteredServerMessages: List<ChatMessage>,
        overlay: List<ChatMessage>
    ): List<ChatMessage> {
        val merged = ArrayList<ChatMessage>(filteredServerMessages.size + overlay.size)
        var serverIndex = 0
        var overlayIndex = 0
        while (serverIndex < filteredServerMessages.size && overlayIndex < overlay.size) {
            val serverMessage = filteredServerMessages[serverIndex]
            val overlayMessage = overlay[overlayIndex]
            val takeServerMessage = if (serverMessage.timestamp != overlayMessage.timestamp) {
                serverMessage.timestamp <= overlayMessage.timestamp
            } else {
                true
            }
            if (takeServerMessage) {
                merged += serverMessage
                serverIndex += 1
            } else {
                merged += overlayMessage
                overlayIndex += 1
            }
        }
        while (serverIndex < filteredServerMessages.size) {
            merged += filteredServerMessages[serverIndex++]
        }
        while (overlayIndex < overlay.size) {
            merged += overlay[overlayIndex++]
        }
        return merged
    }

    private fun applyPendingEditedMessages(messages: List<ChatMessage>): List<ChatMessage> {
        if (pendingEditedMessages.isEmpty()) return messages
        return messages.map { current ->
            val pendingEntry = pendingEditedMessages.entries.firstOrNull { (_, pending) ->
                current.id == pending.replacement.id || current.id == pending.serverMessageId
            } ?: return@map current
            pendingEntry.value.replacement.copy(id = current.id, sendState = current.sendState)
        }
    }

    private fun messagesMatchForEdit(first: ChatMessage, second: ChatMessage): Boolean {
        return first.type == second.type &&
            first.text == second.text &&
            first.voiceUrl == second.voiceUrl &&
            first.voiceDurationSec == second.voiceDurationSec &&
            first.imageUrl == second.imageUrl &&
            first.imageUrls == second.imageUrls &&
            first.imageWidth == second.imageWidth &&
            first.imageHeight == second.imageHeight &&
            first.imageWidths == second.imageWidths &&
            first.imageHeights == second.imageHeights &&
            first.videoUrl == second.videoUrl &&
            first.localVideoPath == second.localVideoPath &&
            first.videoDurationSec == second.videoDurationSec &&
            first.replyToMessageId == second.replyToMessageId &&
            first.replyToSenderName == second.replyToSenderName &&
            first.replyToText == second.replyToText &&
            first.replyToImageUrl == second.replyToImageUrl &&
            first.edited == second.edited
    }

    private fun buildOptimisticBaseMessage(
        user: UserProfile,
        type: MessageType,
        text: String,
        replyTarget: ChatMessage?,
        timestampMs: Long = System.currentTimeMillis()
    ): ChatMessage {
        val localId = nextLocalMessageId(timestampMs)
        return ChatMessage(
            id = localId,
            senderId = user.uid,
            senderName = user.displayName.ifBlank { user.login },
            senderAvatarUrl = user.avatarUrl,
            text = text,
            type = type,
            replyToMessageId = sanitizeReplyMessageId(replyTarget),
            replyToSenderName = replyTarget?.senderName,
            replyToText = replyTarget?.let(replyPreviewText),
            replyToImageUrl = replyTarget?.let(replyPreviewImageUrl),
            timestamp = timestampMs,
            deliveredBy = listOf(user.uid),
            readBy = listOf(user.uid),
            sendState = MessageSendState.SENDING
        )
    }

    private fun sanitizeReplyMessageId(message: ChatMessage?): String? {
        return resolveServerMessageId(message?.id)
    }

    private fun nextLocalMessageId(timestampMs: Long): String {
        return "local:${timestampMs}:${localMessageSequence++}"
    }

    private data class PendingEditedMessage(
        val serverMessageId: String,
        val replacement: ChatMessage
    )

    private companion object {
        private const val VOICE_PREVIEW_TEXT = "\uD83C\uDFA4 Voice message"
        private const val PHOTO_PREVIEW_TEXT = "\uD83D\uDCF7 \u0424\u043E\u0442\u043E"
        private const val VIDEO_PREVIEW_TEXT = "\uD83C\uDFA5 \u0412\u0438\u0434\u0435\u043E"
    }
}
