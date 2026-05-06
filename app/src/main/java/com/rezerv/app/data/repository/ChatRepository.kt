package com.rezerv.app.data.repository

import com.rezerv.app.data.model.ChatMessage
import com.rezerv.app.data.model.ChatPreview
import com.rezerv.app.data.model.GroupInfo
import com.rezerv.app.data.model.UserProfile
import com.rezerv.app.network.ApiClient
import com.rezerv.app.storage.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class ChatRepository(
    private val apiClient: ApiClient,
    private val sessionStore: SessionStore
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val chatsCache = ConcurrentHashMap<String, List<ChatPreview>>()
    private val messageCache = ChatMessageCache(sessionStore)
    private val mediaRepository = ChatMediaRepository(apiClient)
    private val preloadingMessageChats = ConcurrentHashMap.newKeySet<String>()

    fun observeChats(
        userId: String,
        onUpdate: (List<ChatPreview>) -> Unit,
        onError: (Throwable) -> Unit
    ): RealtimeSubscription {
        val job = scope.launch {
            var lastEmitted: List<ChatPreview>? = null
            var hasDeliveredNetworkSnapshot = false
            if (chatsCache.containsKey(userId)) {
                val cached = chatsCache[userId] ?: emptyList()
                lastEmitted = cached
                onUpdate(cached)
            }
            while (isActive) {
                runCatching {
                    fetchChats()
                }.onSuccess { chats ->
                    chatsCache[userId] = chats
                    if (!hasDeliveredNetworkSnapshot || lastEmitted == null || chats != lastEmitted) {
                        lastEmitted = chats
                        onUpdate(chats)
                        hasDeliveredNetworkSnapshot = true
                    }
                }.onFailure(onError)

                delay(POLL_INTERVAL_MS)
            }
        }

        return object : RealtimeSubscription {
            override fun remove() {
                job.cancel()
            }
        }
    }

    fun observeMessages(
        chatId: String,
        onUpdate: (List<ChatMessage>) -> Unit,
        onError: (Throwable) -> Unit
    ): RealtimeSubscription {
        val job = scope.launch {
            var lastEmitted: List<ChatMessage>? = null
            var hasDeliveredNetworkSnapshot = false
            messageCache.ensureLoaded(chatId)
            if (messageCache.contains(chatId)) {
                val cached = messageCache.get(chatId)
                lastEmitted = cached
                onUpdate(cached)
            }
            while (isActive) {
                runCatching {
                    fetchMessages(chatId)
                }.onSuccess { messages ->
                    messageCache.saveSnapshot(chatId, messages)
                    if (!hasDeliveredNetworkSnapshot || lastEmitted == null || messages != lastEmitted) {
                        lastEmitted = messages
                        onUpdate(messages)
                        hasDeliveredNetworkSnapshot = true
                    }
                }.onFailure(onError)

                delay(POLL_INTERVAL_MS)
            }
        }

        return object : RealtimeSubscription {
            override fun remove() {
                job.cancel()
            }
        }
    }

    suspend fun createDirectChat(otherLogin: String): ChatPreview {
        val payload = JSONObject().put("login", otherLogin.trim().lowercase())
        val response = apiClient.post(path = "/api/chats/direct", payload = payload)
        return ChatJsonParsers.chatPreview(response.getJSONObject("chat"))
    }

    suspend fun listUsers(query: String = ""): List<UserProfile> {
        val path = if (query.isBlank()) {
            "/api/users"
        } else {
            "/api/users?q=${java.net.URLEncoder.encode(query.trim(), "UTF-8")}"
        }
        val response = apiClient.get(path)
        val items = response.optJSONArray("items") ?: return emptyList()
        val result = ArrayList<UserProfile>(items.length())
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            result += UserProfile(
                uid = item.optString("uid"),
                login = item.optString("login"),
                displayName = item.optString("displayName").ifBlank { item.optString("login") },
                avatarUrl = item.optString("avatarUrl").ifBlank { null }
            )
        }
        return result
    }

    suspend fun createGroupChat(title: String, memberLogins: List<String>): ChatPreview {
        val membersArray = org.json.JSONArray()
        memberLogins
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { membersArray.put(it) }

        val payload = JSONObject()
            .put("title", title.trim())
            .put("members", membersArray)
        val response = apiClient.post(path = "/api/chats/group", payload = payload)
        return ChatJsonParsers.chatPreview(response.getJSONObject("chat"))
    }

    suspend fun uploadGroupAvatar(chatId: String, imageBytes: ByteArray): ChatPreview {
        val response = apiClient.uploadChatAvatar(chatId = chatId, imageBytes = imageBytes)
        return ChatJsonParsers.chatPreview(response.getJSONObject("chat"))
    }

    suspend fun getGroupInfo(chatId: String): GroupInfo {
        val response = apiClient.get("/api/chats/$chatId/group-info")
        return ChatJsonParsers.groupInfo(response)
    }

    suspend fun addUserToGroup(chatId: String, userLogin: String): GroupInfo {
        val payload = JSONObject().put("login", userLogin.trim().lowercase())
        val response = apiClient.post(path = "/api/chats/$chatId/members", payload = payload)
        return ChatJsonParsers.groupInfo(response)
    }

    suspend fun leaveGroup(chatId: String) {
        apiClient.post(path = "/api/chats/$chatId/leave", payload = JSONObject())
    }

    suspend fun removeUserFromGroup(chatId: String, userLogin: String): GroupInfo {
        val payload = JSONObject().put("login", userLogin.trim().lowercase())
        val response = apiClient.delete(path = "/api/chats/$chatId/members", payload = payload)
        return ChatJsonParsers.groupInfo(response)
    }

    suspend fun sendMessage(
        chatId: String,
        text: String,
        replyToMessageId: String? = null
    ): ChatMessage {
        val payload = text.trim()
        if (payload.isBlank()) throw IllegalArgumentException("Message is empty")

        val request = JSONObject().put("text", payload)
        if (!replyToMessageId.isNullOrBlank()) {
            request.put("replyToMessageId", replyToMessageId)
        }

        val response = apiClient.post(
            path = "/api/chats/$chatId/messages",
            payload = request
        )
        return ChatJsonParsers.messageFromSendResponse(response)
    }

    suspend fun uploadVoice(chatId: String, voiceBytes: ByteArray, fileName: String): String {
        return mediaRepository.uploadVoice(chatId, voiceBytes, fileName)
    }

    suspend fun uploadPhoto(chatId: String, imageBytes: ByteArray, fileName: String): String {
        return mediaRepository.uploadPhoto(chatId, imageBytes, fileName)
    }

    suspend fun uploadVideo(
        chatId: String,
        videoBytes: ByteArray,
        fileName: String,
        onProgress: ((Float) -> Unit)? = null
    ): String {
        return mediaRepository.uploadVideo(chatId, videoBytes, fileName, onProgress)
    }

    suspend fun sendVoiceMessage(
        chatId: String,
        voiceUrl: String,
        durationSec: Int,
        fallbackText: String = "\uD83C\uDFA4 Voice message",
        replyToMessageId: String? = null
    ): ChatMessage {
        return mediaRepository.sendVoiceMessage(
            chatId = chatId,
            voiceUrl = voiceUrl,
            durationSec = durationSec,
            fallbackText = fallbackText,
            replyToMessageId = replyToMessageId
        )
    }

    suspend fun sendPhotoMessage(
        chatId: String,
        photoUrls: List<String>,
        width: Int,
        height: Int,
        widths: List<Int>? = null,
        heights: List<Int>? = null,
        caption: String? = null,
        fallbackText: String = PHOTO_PREVIEW_TEXT,
        replyToMessageId: String? = null
    ): ChatMessage {
        return mediaRepository.sendPhotoMessage(
            chatId = chatId,
            photoUrls = photoUrls,
            width = width,
            height = height,
            widths = widths,
            heights = heights,
            caption = caption,
            fallbackText = fallbackText,
            replyToMessageId = replyToMessageId
        )
    }

    suspend fun sendVideoMessage(
        chatId: String,
        videoUrl: String,
        durationSec: Int,
        fallbackText: String = VIDEO_PREVIEW_TEXT,
        replyToMessageId: String? = null
    ): ChatMessage {
        return mediaRepository.sendVideoMessage(
            chatId = chatId,
            videoUrl = videoUrl,
            durationSec = durationSec,
            fallbackText = fallbackText,
            replyToMessageId = replyToMessageId
        )
    }

    suspend fun editMessage(
        chatId: String,
        messageId: String,
        text: String,
        imageUrls: List<String>? = null,
        imageWidths: List<Int>? = null,
        imageHeights: List<Int>? = null
    ): ChatMessage {
        val payload = JSONObject().put("text", text.trim())
        val cleanedImageUrls = imageUrls
            ?.asSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.takeIf { it.any() }
            ?.toList()
        if (cleanedImageUrls != null) {
            payload.put("imageUrl", cleanedImageUrls.first())
            payload.put("imageUrls", JSONArray(cleanedImageUrls))

            val cleanedWidths = imageWidths
                ?.map { it.coerceAtLeast(0) }
                ?.takeIf { it.isNotEmpty() }
            val cleanedHeights = imageHeights
                ?.map { it.coerceAtLeast(0) }
                ?.takeIf { it.isNotEmpty() }

            if (cleanedWidths != null) {
                payload.put("imageWidths", JSONArray(cleanedWidths))
            }
            if (cleanedHeights != null) {
                payload.put("imageHeights", JSONArray(cleanedHeights))
            }
        }

        val response = apiClient.put(
            path = "/api/chats/$chatId/messages/$messageId",
            payload = payload
        )
        val message = ChatJsonParsers.messageFromSendResponse(response)
        replaceCachedMessage(chatId, message)
        return message
    }

    suspend fun deleteMessage(chatId: String, messageId: String) {
        apiClient.delete(path = "/api/chats/$chatId/messages/$messageId")
    }

    suspend fun markChatRead(
        chatId: String,
        readUpToTimestampMs: Long? = null
    ) {
        val payload = JSONObject()
        if ((readUpToTimestampMs ?: 0L) > 0L) {
            payload.put("readUpToTimestamp", readUpToTimestampMs)
        }
        apiClient.post(path = "/api/chats/$chatId/read", payload = payload)
    }

    suspend fun loadChatsOnce(): List<ChatPreview> {
        return fetchChats()
    }

    fun prefetchMessagesForChats(chatIds: List<String>, limit: Int = MESSAGES_PREFETCH_CHAT_LIMIT) {
        val targets = chatIds
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(limit.coerceAtLeast(1))
            .toList()
        if (targets.isEmpty()) return

        targets.forEach { chatId ->
            messageCache.ensureLoaded(chatId)
            if (messageCache.raw(chatId)?.isNotEmpty() == true) return@forEach
            if (!preloadingMessageChats.add(chatId)) return@forEach
            scope.launch {
                runCatching { fetchMessages(chatId) }
                    .onSuccess { messages -> messageCache.saveSnapshot(chatId, messages) }
                preloadingMessageChats.remove(chatId)
            }
        }
    }

    fun hasCachedChats(userId: String): Boolean {
        return chatsCache.containsKey(userId)
    }

    fun getCachedChats(userId: String): List<ChatPreview> {
        return chatsCache[userId] ?: emptyList()
    }

    fun hasCachedMessages(chatId: String): Boolean {
        return messageCache.contains(chatId)
    }

    fun getCachedMessages(chatId: String): List<ChatMessage> {
        return messageCache.get(chatId)
    }

    fun replaceCachedMessage(chatId: String, message: ChatMessage) {
        messageCache.replaceMessage(chatId, message)
    }

    private suspend fun fetchChats(): List<ChatPreview> {
        val response = apiClient.get("/api/chats")
        val items = response.optJSONArray("items") ?: return emptyList()
        val result = ArrayList<ChatPreview>(items.length())

        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            result += ChatJsonParsers.chatPreview(item)
        }

        return result
    }

    private suspend fun fetchMessages(chatId: String): List<ChatMessage> {
        val response = apiClient.get("/api/chats/$chatId/messages")
        val items = response.optJSONArray("items") ?: return emptyList()
        val result = ArrayList<ChatMessage>(items.length())

        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            result += ChatJsonParsers.chatMessage(item)
        }

        return result
    }

    fun shutdown() {
        scope.cancel()
    }

    private companion object {
        const val POLL_INTERVAL_MS = 1_200L
        const val MESSAGES_PREFETCH_CHAT_LIMIT = 12
        const val PHOTO_PREVIEW_TEXT = "\uD83D\uDCF7 \u0424\u043E\u0442\u043E"
        const val VIDEO_PREVIEW_TEXT = "\uD83C\uDFA5 \u0412\u0438\u0434\u0435\u043E"
    }
}

