package com.rezerv.app.data.repository

import com.rezerv.app.data.model.ChatMessage
import com.rezerv.app.data.model.ChatPreview
import com.rezerv.app.data.model.GroupInfo
import com.rezerv.app.data.model.MessageSendState
import com.rezerv.app.data.model.MessageType
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
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class ChatRepository(
    private val apiClient: ApiClient,
    private val sessionStore: SessionStore
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val chatsCache = ConcurrentHashMap<String, List<ChatPreview>>()
    private val messagesCache = ConcurrentHashMap<String, List<ChatMessage>>()
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
            ensureMessagesCacheLoaded(chatId)
            if (messagesCache.containsKey(chatId)) {
                val cached = messagesCache[chatId] ?: emptyList()
                lastEmitted = cached
                onUpdate(cached)
            }
            while (isActive) {
                runCatching {
                    fetchMessages(chatId)
                }.onSuccess { messages ->
                    cacheMessagesSnapshot(chatId, messages)
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
        return parseChatPreview(response.getJSONObject("chat"))
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
        return parseChatPreview(response.getJSONObject("chat"))
    }

    suspend fun uploadGroupAvatar(chatId: String, imageBytes: ByteArray): ChatPreview {
        val response = apiClient.uploadChatAvatar(chatId = chatId, imageBytes = imageBytes)
        return parseChatPreview(response.getJSONObject("chat"))
    }

    suspend fun getGroupInfo(chatId: String): GroupInfo {
        val response = apiClient.get("/api/chats/$chatId/group-info")
        return parseGroupInfo(response)
    }

    suspend fun addUserToGroup(chatId: String, userLogin: String): GroupInfo {
        val payload = JSONObject().put("login", userLogin.trim().lowercase())
        val response = apiClient.post(path = "/api/chats/$chatId/members", payload = payload)
        return parseGroupInfo(response)
    }

    suspend fun leaveGroup(chatId: String) {
        apiClient.post(path = "/api/chats/$chatId/leave", payload = JSONObject())
    }

    suspend fun removeUserFromGroup(chatId: String, userLogin: String): GroupInfo {
        val payload = JSONObject().put("login", userLogin.trim().lowercase())
        val response = apiClient.delete(path = "/api/chats/$chatId/members", payload = payload)
        return parseGroupInfo(response)
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
        return parseMessageFromSendResponse(response)
    }

    suspend fun uploadVoice(chatId: String, voiceBytes: ByteArray, fileName: String): String {
        val response = apiClient.uploadVoice(chatId = chatId, voiceBytes = voiceBytes, fileName = fileName)
        return response.optString("voiceUrl").ifBlank { null }
            ?: response.optString("fileName").ifBlank { throw IllegalStateException("Voice upload failed") }
    }

    suspend fun uploadPhoto(chatId: String, imageBytes: ByteArray, fileName: String): String {
        val response = apiClient.uploadPhoto(chatId = chatId, imageBytes = imageBytes, fileName = fileName)
        return response.optString("photoUrl").ifBlank { null }
            ?: response.optString("fileName").ifBlank { throw IllegalStateException("Photo upload failed") }
    }

    suspend fun uploadVideo(chatId: String, videoBytes: ByteArray, fileName: String): String {
        val response = apiClient.uploadVideo(chatId = chatId, videoBytes = videoBytes, fileName = fileName)
        return response.optString("videoUrl").ifBlank { null }
            ?: response.optString("fileName").ifBlank { throw IllegalStateException("Video upload failed") }
    }

    suspend fun sendVoiceMessage(
        chatId: String,
        voiceUrl: String,
        durationSec: Int,
        fallbackText: String = "\uD83C\uDFA4 Voice message",
        replyToMessageId: String? = null
    ): ChatMessage {
        val payload = JSONObject()
            .put("type", "voice")
            .put("voiceUrl", voiceUrl)
            .put("voiceDurationSec", durationSec.coerceAtLeast(0))
            .put("text", fallbackText)
        if (!replyToMessageId.isNullOrBlank()) {
            payload.put("replyToMessageId", replyToMessageId)
        }

        val response = apiClient.post(
            path = "/api/chats/$chatId/messages",
            payload = payload
        )
        return parseMessageFromSendResponse(response)
    }

    suspend fun sendPhotoMessage(
        chatId: String,
        photoUrl: String,
        width: Int,
        height: Int,
        fallbackText: String = PHOTO_PREVIEW_TEXT,
        replyToMessageId: String? = null
    ): ChatMessage {
        val payload = JSONObject()
            .put("type", "image")
            .put("imageUrl", photoUrl)
            .put("imageWidth", width.coerceAtLeast(0))
            .put("imageHeight", height.coerceAtLeast(0))
            .put("text", fallbackText)
        if (!replyToMessageId.isNullOrBlank()) {
            payload.put("replyToMessageId", replyToMessageId)
        }

        val response = apiClient.post(
            path = "/api/chats/$chatId/messages",
            payload = payload
        )
        return parseMessageFromSendResponse(response)
    }

    suspend fun sendVideoMessage(
        chatId: String,
        videoUrl: String,
        durationSec: Int,
        fallbackText: String = VIDEO_PREVIEW_TEXT,
        replyToMessageId: String? = null
    ): ChatMessage {
        val payload = JSONObject()
            .put("type", "video")
            .put("videoUrl", videoUrl)
            .put("videoDurationSec", durationSec.coerceAtLeast(0))
            .put("text", fallbackText)
        if (!replyToMessageId.isNullOrBlank()) {
            payload.put("replyToMessageId", replyToMessageId)
        }

        val response = apiClient.post(
            path = "/api/chats/$chatId/messages",
            payload = payload
        )
        return parseMessageFromSendResponse(response)
    }

    suspend fun editMessage(chatId: String, messageId: String, text: String) {
        val payload = text.trim()
        if (payload.isBlank()) return
        apiClient.put(
            path = "/api/chats/$chatId/messages/$messageId",
            payload = JSONObject().put("text", payload)
        )
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
            ensureMessagesCacheLoaded(chatId)
            if (messagesCache[chatId]?.isNotEmpty() == true) return@forEach
            if (!preloadingMessageChats.add(chatId)) return@forEach
            scope.launch {
                runCatching { fetchMessages(chatId) }
                    .onSuccess { messages -> cacheMessagesSnapshot(chatId, messages) }
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
        ensureMessagesCacheLoaded(chatId)
        return messagesCache.containsKey(chatId)
    }

    fun getCachedMessages(chatId: String): List<ChatMessage> {
        ensureMessagesCacheLoaded(chatId)
        return messagesCache[chatId] ?: emptyList()
    }

    private fun ensureMessagesCacheLoaded(chatId: String) {
        if (chatId.isBlank()) return
        if (messagesCache.containsKey(chatId)) return
        val persisted = sessionStore.chatMessagesSnapshot(chatId)
        if (persisted.isNotEmpty()) {
            messagesCache[chatId] = persisted
        }
    }

    private fun cacheMessagesSnapshot(chatId: String, messages: List<ChatMessage>) {
        if (chatId.isBlank()) return
        messagesCache[chatId] = messages
        sessionStore.saveChatMessagesSnapshot(chatId, messages)
    }

    private suspend fun fetchChats(): List<ChatPreview> {
        val response = apiClient.get("/api/chats")
        val items = response.optJSONArray("items") ?: return emptyList()
        val result = ArrayList<ChatPreview>(items.length())

        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            result += parseChatPreview(item)
        }

        return result
    }

    private suspend fun fetchMessages(chatId: String): List<ChatMessage> {
        val response = apiClient.get("/api/chats/$chatId/messages")
        val items = response.optJSONArray("items") ?: return emptyList()
        val result = ArrayList<ChatMessage>(items.length())

        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            result += parseChatMessage(item)
        }

        return result
    }

    private fun parseMessageFromSendResponse(response: JSONObject): ChatMessage {
        val message = response.optJSONObject("message")
            ?: throw IllegalStateException("Server returned no message payload")
        return parseChatMessage(message)
    }

    private fun parseChatMessage(item: JSONObject): ChatMessage {
        val deliveredByJson = item.optJSONArray("deliveredBy")
        val deliveredBy = mutableListOf<String>()
        if (deliveredByJson != null) {
            for (j in 0 until deliveredByJson.length()) {
                val value = deliveredByJson.optString(j)
                if (value.isNotBlank()) {
                    deliveredBy += value
                }
            }
        }

        val readByJson = item.optJSONArray("readBy")
        val readBy = mutableListOf<String>()
        if (readByJson != null) {
            for (j in 0 until readByJson.length()) {
                val value = readByJson.optString(j)
                if (value.isNotBlank()) {
                    readBy += value
                }
            }
        }

        return ChatMessage(
            id = item.optString("id"),
            senderId = item.optString("senderId"),
            senderName = item.optString("senderName"),
            senderAvatarUrl = item.optString("senderAvatarUrl").ifBlank { null },
            text = item.optString("text"),
            type = when (item.optString("type").lowercase()) {
                "voice" -> MessageType.VOICE
                "image" -> MessageType.IMAGE
                "video" -> MessageType.VIDEO
                else -> MessageType.TEXT
            },
            voiceUrl = item.optString("voiceUrl").ifBlank { null },
            voiceDurationSec = item.optInt("voiceDurationSec", 0).coerceAtLeast(0),
            imageUrl = item.optString("imageUrl").ifBlank { null },
            imageWidth = item.optInt("imageWidth", 0).coerceAtLeast(0),
            imageHeight = item.optInt("imageHeight", 0).coerceAtLeast(0),
            videoUrl = item.optString("videoUrl").ifBlank { null },
            videoDurationSec = item.optInt("videoDurationSec", 0).coerceAtLeast(0),
            replyToMessageId = item.optJSONObject("replyTo")?.optString("messageId").orEmpty().ifBlank { null },
            replyToSenderName = item.optJSONObject("replyTo")?.optString("senderName").orEmpty().ifBlank { null },
            replyToText = item.optJSONObject("replyTo")?.optString("text").orEmpty().ifBlank { null },
            timestamp = item.optLong("timestamp"),
            deliveredBy = deliveredBy,
            readBy = readBy,
            edited = item.optBoolean("edited"),
            sendState = MessageSendState.SENT
        )
    }

    private fun parseChatPreview(raw: JSONObject): ChatPreview {
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

    private fun parseGroupInfo(response: JSONObject): GroupInfo {
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

