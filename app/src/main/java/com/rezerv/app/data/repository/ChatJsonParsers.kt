package com.rezerv.app.data.repository

import com.rezerv.app.data.model.ChatMessage
import com.rezerv.app.data.model.ChatPreview
import com.rezerv.app.data.model.GroupInfo
import com.rezerv.app.data.model.MessageSendState
import com.rezerv.app.data.model.MessageType
import com.rezerv.app.data.model.UserProfile
import org.json.JSONArray
import org.json.JSONObject

internal object ChatJsonParsers {
    fun messageFromSendResponse(response: JSONObject): ChatMessage {
        val message = response.optJSONObject("message")
            ?: throw IllegalStateException("Server returned no message payload")
        return chatMessage(message)
    }

    fun chatMessage(item: JSONObject): ChatMessage {
        val imageUrls = parseStringList(item.optJSONArray("imageUrls")).ifEmpty {
            item.optString("imageUrl").trim().takeIf { it.isNotBlank() }?.let { listOf(it) }.orEmpty()
        }
        val imageWidths = parseIntList(item.optJSONArray("imageWidths")).ifEmpty {
            item.optInt("imageWidth", 0).coerceAtLeast(0).takeIf { it > 0 }?.let { listOf(it) }.orEmpty()
        }
        val imageHeights = parseIntList(item.optJSONArray("imageHeights")).ifEmpty {
            item.optInt("imageHeight", 0).coerceAtLeast(0).takeIf { it > 0 }?.let { listOf(it) }.orEmpty()
        }
        val deliveredBy = parseStringList(item.optJSONArray("deliveredBy"))
        val readBy = parseStringList(item.optJSONArray("readBy"))

        return ChatMessage(
            id = item.optString("id"),
            senderId = item.optString("senderId"),
            senderName = item.optString("senderName"),
            senderAvatarUrl = item.optCleanString("senderAvatarUrl"),
            text = item.optString("text"),
            type = when (item.optString("type").lowercase()) {
                "voice" -> MessageType.VOICE
                "image" -> MessageType.IMAGE
                "video" -> MessageType.VIDEO
                else -> MessageType.TEXT
            },
            voiceUrl = item.optCleanString("voiceUrl"),
            voiceDurationSec = item.optInt("voiceDurationSec", 0).coerceAtLeast(0),
            imageUrl = item.optCleanString("imageUrl"),
            imageWidth = item.optInt("imageWidth", 0).coerceAtLeast(0),
            imageHeight = item.optInt("imageHeight", 0).coerceAtLeast(0),
            imageUrls = imageUrls,
            imageWidths = imageWidths,
            imageHeights = imageHeights,
            videoUrl = item.optCleanString("videoUrl"),
            videoDurationSec = item.optInt("videoDurationSec", 0).coerceAtLeast(0),
            replyToMessageId = item.optJSONObject("replyTo")?.optString("messageId").orEmpty().ifBlank { null },
            replyToSenderName = item.optJSONObject("replyTo")?.optString("senderName").orEmpty().ifBlank { null },
            replyToText = item.optJSONObject("replyTo")?.optString("text").orEmpty().ifBlank { null },
            replyToImageUrl = item.optJSONObject("replyTo")?.optCleanString("imageUrl"),
            timestamp = item.optLong("timestamp"),
            deliveredBy = deliveredBy,
            readBy = readBy,
            edited = item.optBoolean("edited"),
            sendState = MessageSendState.SENT
        )
    }

    fun chatPreview(raw: JSONObject): ChatPreview {
        return ChatPreview(
            id = raw.optString("id"),
            title = raw.optString("title").ifBlank { "Чат" },
            avatarUrl = raw.optString("avatarUrl").ifBlank { null },
            peerUid = raw.optString("peerUid").ifBlank { null },
            peerLogin = raw.optString("peerLogin").ifBlank { null },
            peerDisplayName = raw.optString("peerDisplayName").ifBlank { null },
            lastMessage = raw.optString("lastMessage"),
            timestamp = raw.optLong("timestamp"),
            unreadCount = raw.optInt("unreadCount"),
            lastMessageOutgoing = raw.optBoolean("lastMessageOutgoing"),
            lastMessageDelivered = raw.optBoolean("lastMessageDelivered", raw.optBoolean("lastMessageRead")),
            lastMessageRead = raw.optBoolean("lastMessageRead"),
            isGroup = raw.optBoolean("isGroup"),
            memberCount = raw.optInt("memberCount")
        )
    }

    fun groupInfo(response: JSONObject): GroupInfo {
        val group = response.optJSONObject("group") ?: JSONObject()
        val membersRaw = response.optJSONArray("members")
        val members = ArrayList<UserProfile>(membersRaw?.length() ?: 0)

        if (membersRaw != null) {
            for (index in 0 until membersRaw.length()) {
                val item = membersRaw.optJSONObject(index) ?: continue
                members += UserProfile(
                    uid = item.optString("uid"),
                    login = item.optString("login"),
                    displayName = item.optString("displayName").ifBlank { item.optString("login") },
                    avatarUrl = item.optString("avatarUrl").ifBlank { null }
                )
            }
        }

        return GroupInfo(
            chatId = group.optString("id"),
            title = group.optString("title").ifBlank { "Группа" },
            description = group.optString("description"),
            avatarUrl = group.optString("avatarUrl").ifBlank { null },
            createdByUid = group.optString("createdByUid").ifBlank { null },
            createdByLogin = group.optString("createdByLogin").ifBlank { null },
            members = members
        )
    }

    private fun parseStringList(raw: JSONArray?): List<String> {
        if (raw == null) return emptyList()
        val values = ArrayList<String>(raw.length())
        for (index in 0 until raw.length()) {
            val value = raw.optString(index).trim()
            if (value.isNotBlank()) {
                values += value
            }
        }
        return values
    }

    private fun parseIntList(raw: JSONArray?): List<Int> {
        if (raw == null) return emptyList()
        val values = ArrayList<Int>(raw.length())
        for (index in 0 until raw.length()) {
            values += raw.optInt(index, 0).coerceAtLeast(0)
        }
        return values
    }
}

private fun JSONObject.optCleanString(name: String): String? {
    if (isNull(name)) return null
    return optString(name).trim().takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) }
}
