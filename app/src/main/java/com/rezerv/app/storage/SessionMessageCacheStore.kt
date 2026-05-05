package com.rezerv.app.storage

import android.content.SharedPreferences
import com.rezerv.app.data.model.ChatMessage
import com.rezerv.app.data.model.MessageSendState
import com.rezerv.app.data.model.MessageType
import org.json.JSONArray
import org.json.JSONObject

internal class SessionMessageCacheStore(
    private val prefs: SharedPreferences,
    private val uidKey: String,
    private val indexKey: String,
    private val cacheKeyPrefix: String,
    private val maxMessagesPerChat: Int,
    private val maxCachedChats: Int
) {
    fun saveSnapshot(chatId: String, messages: List<ChatMessage>) {
        val uid = prefs.getString(uidKey, null) ?: return
        if (chatId.isBlank()) return

        val cacheKey = "$cacheKeyPrefix$uid::$chatId"
        val payload = JSONArray()
        val filtered = messages.filter { it.id.isNotBlank() }
        val tail = if (filtered.size > maxMessagesPerChat) {
            filtered.takeLast(maxMessagesPerChat)
        } else {
            filtered
        }
        tail.forEach { payload.put(serializeChatMessage(it)) }

        val index = loadIndexRoot().apply {
            put(cacheKey, System.currentTimeMillis())
        }

        trimCache(index = index, currentUid = uid)

        prefs.edit()
            .putString(cacheKey, payload.toString())
            .putString(indexKey, index.toString())
            .apply()
    }

    fun snapshot(chatId: String): List<ChatMessage> {
        val uid = prefs.getString(uidKey, null) ?: return emptyList()
        if (chatId.isBlank()) return emptyList()

        val cacheKey = "$cacheKeyPrefix$uid::$chatId"
        val raw = prefs.getString(cacheKey, null).orEmpty()
        if (raw.isBlank()) return emptyList()

        return runCatching {
            val array = JSONArray(raw)
            val result = ArrayList<ChatMessage>(array.length())
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                parseChatMessage(item)?.let { result += it }
            }
            result
        }.getOrDefault(emptyList())
    }

    fun clearSnapshotsForCurrentUser() {
        val uid = prefs.getString(uidKey, null) ?: return
        val prefix = "$cacheKeyPrefix$uid::"
        val index = loadIndexRoot()
        val keysToRemove = mutableListOf<String>()
        val keys = index.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key.startsWith(prefix)) {
                keysToRemove += key
            }
        }
        if (keysToRemove.isEmpty()) return

        val editor = prefs.edit()
        keysToRemove.forEach { key ->
            editor.remove(key)
            index.remove(key)
        }
        editor.putString(indexKey, index.toString()).apply()
    }

    private fun loadIndexRoot(): JSONObject {
        val raw = prefs.getString(indexKey, null).orEmpty()
        if (raw.isBlank()) return JSONObject()
        return runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
    }

    private fun trimCache(index: JSONObject, currentUid: String) {
        val prefix = "$cacheKeyPrefix$currentUid::"
        val cacheItems = mutableListOf<Pair<String, Long>>()
        val keys = index.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (!key.startsWith(prefix)) continue
            val updatedAt = runCatching { index.optLong(key, 0L) }.getOrDefault(0L)
            cacheItems += key to updatedAt
        }
        if (cacheItems.size <= maxCachedChats) return

        val toRemove = cacheItems
            .sortedBy { it.second }
            .take(cacheItems.size - maxCachedChats)
            .map { it.first }
        val editor = prefs.edit()
        toRemove.forEach { key ->
            editor.remove(key)
            index.remove(key)
        }
        editor.putString(indexKey, index.toString()).apply()
    }

    private fun serializeChatMessage(message: ChatMessage): JSONObject {
        val deliveredBy = JSONArray().apply {
            message.deliveredBy
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .forEach { put(it) }
        }
        val readBy = JSONArray().apply {
            message.readBy
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .forEach { put(it) }
        }
        return JSONObject()
            .put("id", message.id)
            .put("senderId", message.senderId)
            .put("senderName", message.senderName)
            .put("senderAvatarUrl", message.senderAvatarUrl)
            .put("text", message.text)
            .put("type", message.type.name.lowercase())
            .put("voiceUrl", message.voiceUrl)
            .put("voiceDurationSec", message.voiceDurationSec)
            .put("imageUrl", message.imageUrl)
            .put("imageWidth", message.imageWidth)
            .put("imageHeight", message.imageHeight)
            .put("imageUrls", JSONArray().apply {
                message.imageUrls
                    .asSequence()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .forEach { put(it) }
            })
            .put("imageWidths", JSONArray().apply {
                message.imageWidths.forEach { put(it.coerceAtLeast(0)) }
            })
            .put("imageHeights", JSONArray().apply {
                message.imageHeights.forEach { put(it.coerceAtLeast(0)) }
            })
            .put("videoUrl", message.videoUrl)
            .put("videoDurationSec", message.videoDurationSec)
            .put("replyToMessageId", message.replyToMessageId)
            .put("replyToSenderName", message.replyToSenderName)
            .put("replyToText", message.replyToText)
            .put("replyToImageUrl", message.replyToImageUrl)
            .put("timestamp", message.timestamp)
            .put("deliveredBy", deliveredBy)
            .put("readBy", readBy)
            .put("edited", message.edited)
    }

    private fun parseChatMessage(raw: JSONObject): ChatMessage? {
        val id = raw.optString("id").trim()
        if (id.isBlank()) return null

        val deliveredBy = parseStringList(raw.optJSONArray("deliveredBy"))
        val readBy = parseStringList(raw.optJSONArray("readBy"))

        val type = when (raw.optString("type").trim().lowercase()) {
            "voice" -> MessageType.VOICE
            "image" -> MessageType.IMAGE
            "video" -> MessageType.VIDEO
            else -> MessageType.TEXT
        }

        val imageUrls = mutableListOf<String>()
        val imageUrlsRaw = raw.optJSONArray("imageUrls")
        if (imageUrlsRaw != null) {
            imageUrls += parseStringList(imageUrlsRaw)
        } else {
            val fallbackImageUrl = raw.optString("imageUrl").trim()
            if (fallbackImageUrl.isNotBlank()) {
                imageUrls += fallbackImageUrl
            }
        }
        val imageWidths = parseIntList(raw.optJSONArray("imageWidths")).ifEmpty {
            raw.optInt("imageWidth", 0).coerceAtLeast(0).takeIf { it > 0 }?.let { listOf(it) }.orEmpty()
        }
        val imageHeights = parseIntList(raw.optJSONArray("imageHeights")).ifEmpty {
            raw.optInt("imageHeight", 0).coerceAtLeast(0).takeIf { it > 0 }?.let { listOf(it) }.orEmpty()
        }

        return ChatMessage(
            id = id,
            senderId = raw.optString("senderId"),
            senderName = raw.optString("senderName"),
            senderAvatarUrl = raw.optCleanString("senderAvatarUrl"),
            text = raw.optString("text"),
            type = type,
            voiceUrl = raw.optCleanString("voiceUrl"),
            voiceDurationSec = raw.optInt("voiceDurationSec", 0).coerceAtLeast(0),
            imageUrl = raw.optCleanString("imageUrl"),
            imageWidth = raw.optInt("imageWidth", 0).coerceAtLeast(0),
            imageHeight = raw.optInt("imageHeight", 0).coerceAtLeast(0),
            imageUrls = imageUrls,
            imageWidths = imageWidths,
            imageHeights = imageHeights,
            videoUrl = raw.optCleanString("videoUrl"),
            videoDurationSec = raw.optInt("videoDurationSec", 0).coerceAtLeast(0),
            replyToMessageId = raw.optString("replyToMessageId").ifBlank { null },
            replyToSenderName = raw.optString("replyToSenderName").ifBlank { null },
            replyToText = raw.optString("replyToText").ifBlank { null },
            replyToImageUrl = raw.optCleanString("replyToImageUrl"),
            timestamp = raw.optLong("timestamp", 0L),
            deliveredBy = deliveredBy,
            readBy = readBy,
            edited = raw.optBoolean("edited", false),
            sendState = MessageSendState.SENT
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
