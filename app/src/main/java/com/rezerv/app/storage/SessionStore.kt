package com.rezerv.app.storage

import android.content.Context
import com.rezerv.app.data.model.ChatMessage
import com.rezerv.app.data.model.MessageSendState
import com.rezerv.app.data.model.MessageType
import com.rezerv.app.data.model.UserProfile
import org.json.JSONArray
import org.json.JSONObject

class SessionStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getServerUrl(): String {
        return prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
    }

    fun setServerUrl(rawValue: String) {
        val normalized = normalizeServerUrl(rawValue)
        val current = getServerUrl()
        prefs.edit().putString(KEY_SERVER_URL, normalized).apply()
        if (current != normalized) {
            clearSession()
            clearAvailableUpdate()
            clearDownloadedUpdate()
            clearUpdatesInfoCache()
        }
    }

    fun saveSession(token: String, user: UserProfile) {
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_UID, user.uid)
            .putString(KEY_LOGIN, user.login)
            .putString(KEY_DISPLAY_NAME, user.displayName)
            .putString(KEY_AVATAR, user.avatarUrl)
            .apply()
        saveRememberedAccount(token = token, user = user, serverUrl = getServerUrl())
    }

    fun authToken(): String? = prefs.getString(KEY_TOKEN, null)

    fun clearSession() {
        clearChatMessageSnapshotsForCurrentUser()
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_UID)
            .remove(KEY_LOGIN)
            .remove(KEY_DISPLAY_NAME)
            .remove(KEY_AVATAR)
            .remove(KEY_FCM_SYNCED_TOKEN)
            .apply()
    }

    fun saveChatMessagesSnapshot(chatId: String, messages: List<ChatMessage>) {
        val uid = prefs.getString(KEY_UID, null) ?: return
        if (chatId.isBlank()) return

        val cacheKey = "$KEY_CHAT_MESSAGES_CACHE_PREFIX$uid::$chatId"
        val payload = JSONArray()
        val filtered = messages.filter { it.id.isNotBlank() }
        val tail = if (filtered.size > MAX_CHAT_MESSAGES_PER_CHAT_CACHE) {
            filtered.takeLast(MAX_CHAT_MESSAGES_PER_CHAT_CACHE)
        } else {
            filtered
        }
        tail.forEach { payload.put(serializeChatMessage(it)) }

        val index = loadChatMessagesIndexRoot().apply {
            put(cacheKey, System.currentTimeMillis())
        }

        trimChatMessagesCache(index = index, currentUid = uid)

        prefs.edit()
            .putString(cacheKey, payload.toString())
            .putString(KEY_CHAT_MESSAGES_INDEX, index.toString())
            .apply()
    }

    fun chatMessagesSnapshot(chatId: String): List<ChatMessage> {
        val uid = prefs.getString(KEY_UID, null) ?: return emptyList()
        if (chatId.isBlank()) return emptyList()

        val cacheKey = "$KEY_CHAT_MESSAGES_CACHE_PREFIX$uid::$chatId"
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

    fun saveChatScrollState(
        chatId: String,
        anchorMessageId: String?,
        anchorIndex: Int,
        anchorOffsetPx: Int,
        wasAtBottom: Boolean
    ) {
        val uid = prefs.getString(KEY_UID, null) ?: return
        if (chatId.isBlank()) return

        val storageKey = "$uid::$chatId"
        val root = loadChatScrollStateRoot()
        val payload = JSONObject()
            .put("messageId", anchorMessageId.orEmpty())
            .put("index", anchorIndex.coerceAtLeast(0))
            .put("offsetPx", anchorOffsetPx)
            .put("atBottom", wasAtBottom)
            .put("updatedAt", System.currentTimeMillis())
        root.put(storageKey, payload)
        prefs.edit().putString(KEY_CHAT_SCROLL_STATE, root.toString()).apply()
    }

    fun chatScrollState(chatId: String): ChatScrollState? {
        val uid = prefs.getString(KEY_UID, null) ?: return null
        if (chatId.isBlank()) return null

        val storageKey = "$uid::$chatId"
        val root = loadChatScrollStateRoot()
        val payload = root.optJSONObject(storageKey) ?: return null

        return ChatScrollState(
            anchorMessageId = payload.optString("messageId").trim().ifBlank { null },
            anchorIndex = payload.optInt("index", -1),
            anchorOffsetPx = payload.optInt("offsetPx", 0),
            wasAtBottom = payload.optBoolean("atBottom", false)
        )
    }

    fun setAvailableUpdate(versionCode: Int, versionName: String?) {
        prefs.edit()
            .putInt(KEY_AVAILABLE_UPDATE_CODE, versionCode)
            .putString(KEY_AVAILABLE_UPDATE_NAME, versionName)
            .apply()
    }

    fun availableUpdateVersionCode(): Int = prefs.getInt(KEY_AVAILABLE_UPDATE_CODE, 0)

    fun availableUpdateVersionName(): String? = prefs.getString(KEY_AVAILABLE_UPDATE_NAME, null)

    fun clearAvailableUpdate() {
        prefs.edit()
            .remove(KEY_AVAILABLE_UPDATE_CODE)
            .remove(KEY_AVAILABLE_UPDATE_NAME)
            .apply()
    }

    fun markUpdateSeen(versionCode: Int) {
        prefs.edit().putInt(KEY_LAST_SEEN_UPDATE_CODE, versionCode).apply()
    }

    fun lastSeenUpdateCode(): Int = prefs.getInt(KEY_LAST_SEEN_UPDATE_CODE, 0)

    fun setDownloadedUpdate(versionCode: Int, filePath: String) {
        prefs.edit()
            .putInt(KEY_DOWNLOADED_UPDATE_CODE, versionCode)
            .putString(KEY_DOWNLOADED_UPDATE_PATH, filePath)
            .apply()
    }

    fun downloadedUpdateVersionCode(): Int = prefs.getInt(KEY_DOWNLOADED_UPDATE_CODE, 0)

    fun downloadedUpdatePath(): String? = prefs.getString(KEY_DOWNLOADED_UPDATE_PATH, null)

    fun clearDownloadedUpdate() {
        prefs.edit()
            .remove(KEY_DOWNLOADED_UPDATE_CODE)
            .remove(KEY_DOWNLOADED_UPDATE_PATH)
            .apply()
    }

    fun setUpdatesInfoCache(rawJson: String) {
        if (rawJson.isBlank()) return
        prefs.edit().putString(KEY_UPDATES_INFO_CACHE, rawJson).apply()
    }

    fun updatesInfoCache(): String? = prefs.getString(KEY_UPDATES_INFO_CACHE, null)

    fun clearUpdatesInfoCache() {
        prefs.edit().remove(KEY_UPDATES_INFO_CACHE).apply()
    }

    fun pinnedChatIds(): Set<String> {
        return prefs.getStringSet(KEY_PINNED_CHATS, emptySet())?.toSet() ?: emptySet()
    }

    fun pinnedChatOrder(): List<String> {
        val pinned = pinnedChatIds()
        if (pinned.isEmpty()) return emptyList()

        val ordered = linkedSetOf<String>()
        val raw = prefs.getString(KEY_PINNED_CHATS_ORDER, null).orEmpty()
        if (raw.isNotBlank()) {
            runCatching {
                val json = JSONArray(raw)
                for (index in 0 until json.length()) {
                    val id = json.optString(index).trim()
                    if (id.isNotBlank() && pinned.contains(id)) {
                        ordered += id
                    }
                }
            }
        }

        pinned.forEach { id ->
            if (!ordered.contains(id)) {
                ordered += id
            }
        }
        return ordered.toList()
    }

    fun isChatPinned(chatId: String): Boolean {
        return pinnedChatIds().contains(chatId)
    }

    fun setChatPinned(chatId: String, pinned: Boolean) {
        if (chatId.isBlank()) return
        // Pin set is intentionally unbounded: user can pin any number of chats.
        val current = linkedSetOf<String>().apply { addAll(pinnedChatIds()) }
        val order = pinnedChatOrder().toMutableList()
        if (pinned) {
            current += chatId
            if (!order.contains(chatId)) {
                order += chatId
            }
        } else {
            current -= chatId
            order.removeAll { it == chatId }
        }
        prefs.edit()
            .putStringSet(KEY_PINNED_CHATS, current)
            .putString(KEY_PINNED_CHATS_ORDER, JSONArray(order).toString())
            .apply()
    }

    fun hiddenChatIds(): Set<String> {
        val uid = prefs.getString(KEY_UID, null) ?: return emptySet()
        val prefix = "$uid::"
        return prefs.getStringSet(KEY_HIDDEN_CHATS, emptySet())
            ?.mapNotNull { value ->
                if (value.startsWith(prefix)) value.removePrefix(prefix) else null
            }
            ?.toSet()
            ?: emptySet()
    }

    fun hideChat(chatId: String) {
        val uid = prefs.getString(KEY_UID, null) ?: return
        if (chatId.isBlank()) return
        val key = "$uid::$chatId"
        val current = linkedSetOf<String>().apply {
            addAll(prefs.getStringSet(KEY_HIDDEN_CHATS, emptySet()) ?: emptySet())
        }
        current += key
        prefs.edit().putStringSet(KEY_HIDDEN_CHATS, current).apply()
    }

    fun hiddenMessageIds(chatId: String): Set<String> {
        val uid = prefs.getString(KEY_UID, null) ?: return emptySet()
        if (chatId.isBlank()) return emptySet()
        val prefix = "$uid::$chatId::"
        return prefs.getStringSet(KEY_HIDDEN_MESSAGES, emptySet())
            ?.mapNotNull { value ->
                if (value.startsWith(prefix)) value.removePrefix(prefix) else null
            }
            ?.toSet()
            ?: emptySet()
    }

    fun hideMessage(chatId: String, messageId: String) {
        val uid = prefs.getString(KEY_UID, null) ?: return
        if (chatId.isBlank() || messageId.isBlank()) return
        val key = "$uid::$chatId::$messageId"
        val current = linkedSetOf<String>().apply {
            addAll(prefs.getStringSet(KEY_HIDDEN_MESSAGES, emptySet()) ?: emptySet())
        }
        current += key
        prefs.edit().putStringSet(KEY_HIDDEN_MESSAGES, current).apply()
    }

    fun removeHiddenMessage(chatId: String, messageId: String) {
        val uid = prefs.getString(KEY_UID, null) ?: return
        if (chatId.isBlank() || messageId.isBlank()) return
        val key = "$uid::$chatId::$messageId"
        val current = linkedSetOf<String>().apply {
            addAll(prefs.getStringSet(KEY_HIDDEN_MESSAGES, emptySet()) ?: emptySet())
        }
        if (current.remove(key)) {
            prefs.edit().putStringSet(KEY_HIDDEN_MESSAGES, current).apply()
        }
    }

    fun rememberedAccounts(): List<RememberedAccount> {
        val raw = prefs.getString(KEY_REMEMBERED_ACCOUNTS_JSON, null) ?: return emptyList()
        return runCatching {
            val json = JSONArray(raw)
            val result = ArrayList<RememberedAccount>(json.length())
            for (index in 0 until json.length()) {
                val item = json.optJSONObject(index) ?: continue
                val token = item.optString("token").trim()
                val uid = item.optString("uid").trim()
                val login = item.optString("login").trim()
                val displayName = item.optString("displayName").ifBlank { login }
                val serverUrl = item.optString("serverUrl").trim()
                if (token.isBlank() || uid.isBlank() || login.isBlank() || serverUrl.isBlank()) {
                    continue
                }
                val avatarUrl = item.optString("avatarUrl").ifBlank { null }
                result += RememberedAccount(
                    uid = uid,
                    login = login,
                    displayName = displayName,
                    avatarUrl = avatarUrl,
                    token = token,
                    serverUrl = serverUrl
                )
            }
            result
        }.getOrDefault(emptyList())
    }

    fun rememberedAccountsForServer(serverUrl: String): List<RememberedAccount> {
        val normalizedServer = normalizeServerUrl(serverUrl)
        return rememberedAccounts()
            .filter { normalizeServerUrl(it.serverUrl) == normalizedServer }
            .sortedBy { it.displayName.lowercase() }
    }

    fun restoreRememberedAccount(login: String, serverUrl: String): Boolean {
        val safeLogin = login.trim().lowercase()
        val normalizedServer = normalizeServerUrl(serverUrl)
        val account = rememberedAccounts().firstOrNull {
            it.login.equals(safeLogin, ignoreCase = true) &&
                normalizeServerUrl(it.serverUrl) == normalizedServer
        } ?: return false

        prefs.edit()
            .putString(KEY_SERVER_URL, normalizedServer)
            .putString(KEY_TOKEN, account.token)
            .putString(KEY_UID, account.uid)
            .putString(KEY_LOGIN, account.login)
            .putString(KEY_DISPLAY_NAME, account.displayName)
            .putString(KEY_AVATAR, account.avatarUrl)
            .apply()
        return true
    }

    fun removeRememberedAccount(login: String, serverUrl: String) {
        val safeLogin = login.trim().lowercase()
        val normalizedServer = normalizeServerUrl(serverUrl)
        val updated = rememberedAccounts().filterNot {
            it.login.equals(safeLogin, ignoreCase = true) &&
                normalizeServerUrl(it.serverUrl) == normalizedServer
        }
        saveRememberedAccounts(updated)
    }

    fun currentUser(): UserProfile? {
        val uid = prefs.getString(KEY_UID, null) ?: return null
        val login = prefs.getString(KEY_LOGIN, null) ?: return null
        val displayName = prefs.getString(KEY_DISPLAY_NAME, null) ?: login
        val avatar = prefs.getString(KEY_AVATAR, null)
        return UserProfile(uid = uid, login = login, displayName = displayName, avatarUrl = avatar)
    }

    fun fcmLocalToken(): String? = prefs.getString(KEY_FCM_LOCAL_TOKEN, null)

    fun setFcmLocalToken(value: String) {
        val token = value.trim()
        if (token.isBlank()) return
        prefs.edit().putString(KEY_FCM_LOCAL_TOKEN, token).apply()
    }

    fun fcmSyncedToken(): String? = prefs.getString(KEY_FCM_SYNCED_TOKEN, null)

    fun setFcmSyncedToken(value: String?) {
        if (value.isNullOrBlank()) {
            prefs.edit().remove(KEY_FCM_SYNCED_TOKEN).apply()
        } else {
            prefs.edit().putString(KEY_FCM_SYNCED_TOKEN, value.trim()).apply()
        }
    }

    private fun normalizeServerUrl(value: String): String {
        var result = value.trim()
        if (result.isBlank()) {
            result = DEFAULT_SERVER_URL
        }
        if (!result.startsWith("http://") && !result.startsWith("https://")) {
            result = "http://$result"
        }
        return result.removeSuffix("/")
    }

    private fun loadChatScrollStateRoot(): JSONObject {
        val raw = prefs.getString(KEY_CHAT_SCROLL_STATE, null).orEmpty()
        if (raw.isBlank()) return JSONObject()
        return runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
    }

    private fun loadChatMessagesIndexRoot(): JSONObject {
        val raw = prefs.getString(KEY_CHAT_MESSAGES_INDEX, null).orEmpty()
        if (raw.isBlank()) return JSONObject()
        return runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
    }

    private fun trimChatMessagesCache(index: JSONObject, currentUid: String) {
        val prefix = "$KEY_CHAT_MESSAGES_CACHE_PREFIX$currentUid::"
        val cacheItems = mutableListOf<Pair<String, Long>>()
        val keys = index.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (!key.startsWith(prefix)) continue
            val updatedAt = runCatching { index.optLong(key, 0L) }.getOrDefault(0L)
            cacheItems += key to updatedAt
        }
        if (cacheItems.size <= MAX_CACHED_CHATS_WITH_MESSAGES) return

        val toRemove = cacheItems
            .sortedBy { it.second }
            .take(cacheItems.size - MAX_CACHED_CHATS_WITH_MESSAGES)
            .map { it.first }
        val editor = prefs.edit()
        toRemove.forEach { key ->
            editor.remove(key)
            index.remove(key)
        }
        editor.putString(KEY_CHAT_MESSAGES_INDEX, index.toString()).apply()
    }

    private fun clearChatMessageSnapshotsForCurrentUser() {
        val uid = prefs.getString(KEY_UID, null) ?: return
        val prefix = "$KEY_CHAT_MESSAGES_CACHE_PREFIX$uid::"
        val index = loadChatMessagesIndexRoot()
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
        editor.putString(KEY_CHAT_MESSAGES_INDEX, index.toString()).apply()
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

        val deliveredBy = mutableListOf<String>()
        val deliveredByRaw = raw.optJSONArray("deliveredBy")
        if (deliveredByRaw != null) {
            for (index in 0 until deliveredByRaw.length()) {
                val value = deliveredByRaw.optString(index).trim()
                if (value.isNotBlank()) {
                    deliveredBy += value
                }
            }
        }

        val readBy = mutableListOf<String>()
        val readByRaw = raw.optJSONArray("readBy")
        if (readByRaw != null) {
            for (index in 0 until readByRaw.length()) {
                val value = readByRaw.optString(index).trim()
                if (value.isNotBlank()) {
                    readBy += value
                }
            }
        }

        val type = when (raw.optString("type").trim().lowercase()) {
            "voice" -> MessageType.VOICE
            "image" -> MessageType.IMAGE
            "video" -> MessageType.VIDEO
            else -> MessageType.TEXT
        }

        val imageUrls = mutableListOf<String>()
        val imageUrlsRaw = raw.optJSONArray("imageUrls")
        if (imageUrlsRaw != null) {
            for (index in 0 until imageUrlsRaw.length()) {
                val value = imageUrlsRaw.optString(index).trim()
                if (value.isNotBlank()) {
                    imageUrls += value
                }
            }
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

    private fun parseIntList(raw: JSONArray?): List<Int> {
        if (raw == null) return emptyList()
        val values = ArrayList<Int>(raw.length())
        for (index in 0 until raw.length()) {
            values += raw.optInt(index, 0).coerceAtLeast(0)
        }
        return values
    }

    private companion object {
        const val PREFS_NAME = "reserv_session"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_TOKEN = "token"
        const val KEY_UID = "uid"
        const val KEY_LOGIN = "login"
        const val KEY_DISPLAY_NAME = "display_name"
        const val KEY_AVATAR = "avatar"
        const val KEY_PINNED_CHATS = "pinned_chats"
        const val KEY_PINNED_CHATS_ORDER = "pinned_chats_order"
        const val KEY_AVAILABLE_UPDATE_CODE = "available_update_code"
        const val KEY_AVAILABLE_UPDATE_NAME = "available_update_name"
        const val KEY_LAST_SEEN_UPDATE_CODE = "last_seen_update_code"
        const val KEY_DOWNLOADED_UPDATE_CODE = "downloaded_update_code"
        const val KEY_DOWNLOADED_UPDATE_PATH = "downloaded_update_path"
        const val KEY_UPDATES_INFO_CACHE = "updates_info_cache"
        const val KEY_FCM_LOCAL_TOKEN = "fcm_local_token"
        const val KEY_FCM_SYNCED_TOKEN = "fcm_synced_token"
        const val KEY_REMEMBERED_ACCOUNTS_JSON = "remembered_accounts_json"
        const val KEY_HIDDEN_CHATS = "hidden_chats"
        const val KEY_HIDDEN_MESSAGES = "hidden_messages"
        const val KEY_CHAT_SCROLL_STATE = "chat_scroll_state"
        const val KEY_CHAT_MESSAGES_INDEX = "chat_messages_index"
        const val KEY_CHAT_MESSAGES_CACHE_PREFIX = "chat_messages_cache::"
        const val DEFAULT_SERVER_URL = "http://130.49.128.205:18080"
        const val MAX_CHAT_MESSAGES_PER_CHAT_CACHE = 140
        const val MAX_CACHED_CHATS_WITH_MESSAGES = 16
    }

    private fun saveRememberedAccount(token: String, user: UserProfile, serverUrl: String) {
        val safeToken = token.trim()
        if (safeToken.isBlank()) return
        val safeServer = normalizeServerUrl(serverUrl)

        val current = rememberedAccounts().toMutableList()
        current.removeAll {
            it.login.equals(user.login, ignoreCase = true) &&
                normalizeServerUrl(it.serverUrl) == safeServer
        }
        current += RememberedAccount(
            uid = user.uid,
            login = user.login.lowercase(),
            displayName = user.displayName.ifBlank { user.login },
            avatarUrl = user.avatarUrl,
            token = safeToken,
            serverUrl = safeServer
        )
        saveRememberedAccounts(current)
    }

    private fun saveRememberedAccounts(items: List<RememberedAccount>) {
        val json = JSONArray()
        items.forEach { account ->
            json.put(
                JSONObject()
                    .put("uid", account.uid)
                    .put("login", account.login)
                    .put("displayName", account.displayName)
                    .put("avatarUrl", account.avatarUrl)
                    .put("token", account.token)
                    .put("serverUrl", account.serverUrl)
            )
        }
        prefs.edit().putString(KEY_REMEMBERED_ACCOUNTS_JSON, json.toString()).apply()
    }

    data class RememberedAccount(
        val uid: String,
        val login: String,
        val displayName: String,
        val avatarUrl: String?,
        val token: String,
        val serverUrl: String
    )

    data class ChatScrollState(
        val anchorMessageId: String?,
        val anchorIndex: Int,
        val anchorOffsetPx: Int,
        val wasAtBottom: Boolean
    )
}

private fun JSONObject.optCleanString(name: String): String? {
    if (isNull(name)) return null
    return optString(name).trim().takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) }
}
