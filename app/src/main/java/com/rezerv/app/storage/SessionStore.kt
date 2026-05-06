package com.rezerv.app.storage

import android.content.Context
import com.rezerv.app.data.model.ChatMessage
import com.rezerv.app.data.model.UserProfile
import org.json.JSONArray
import org.json.JSONObject

class SessionStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val messageCacheStore = SessionMessageCacheStore(
        prefs = prefs,
        uidKey = KEY_UID,
        indexKey = KEY_CHAT_MESSAGES_INDEX,
        cacheKeyPrefix = KEY_CHAT_MESSAGES_CACHE_PREFIX,
        maxMessagesPerChat = MAX_CHAT_MESSAGES_PER_CHAT_CACHE,
        maxCachedChats = MAX_CACHED_CHATS_WITH_MESSAGES
    )
    private val updateStore = SessionUpdateStore(
        prefs = prefs,
        availableCodeKey = KEY_AVAILABLE_UPDATE_CODE,
        availableNameKey = KEY_AVAILABLE_UPDATE_NAME,
        lastSeenCodeKey = KEY_LAST_SEEN_UPDATE_CODE,
        downloadedCodeKey = KEY_DOWNLOADED_UPDATE_CODE,
        downloadedPathKey = KEY_DOWNLOADED_UPDATE_PATH,
        updatesInfoCacheKey = KEY_UPDATES_INFO_CACHE
    )
    private val rememberedAccountsStore = SessionRememberedAccountsStore(
        prefs = prefs,
        rememberedAccountsKey = KEY_REMEMBERED_ACCOUNTS_JSON,
        normalizeServerUrl = ::normalizeServerUrl
    )
    private val visibilityStore = SessionVisibilityStore(
        prefs = prefs,
        uidKey = KEY_UID,
        pinnedChatsKey = KEY_PINNED_CHATS,
        pinnedChatsOrderKey = KEY_PINNED_CHATS_ORDER,
        hiddenChatsKey = KEY_HIDDEN_CHATS,
        hiddenMessagesKey = KEY_HIDDEN_MESSAGES
    )

    fun getServerUrl(): String {
        return DEFAULT_SERVER_URL
    }

    fun saveSession(token: String, user: UserProfile) {
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_UID, user.uid)
            .putString(KEY_LOGIN, user.login)
            .putString(KEY_DISPLAY_NAME, user.displayName)
            .putString(KEY_AVATAR, user.avatarUrl)
            .apply()
        rememberedAccountsStore.saveAccount(token = token, user = user, serverUrl = getServerUrl())
    }

    fun authToken(): String? = prefs.getString(KEY_TOKEN, null)

    fun clearSession() {
        messageCacheStore.clearSnapshotsForCurrentUser()
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
        messageCacheStore.saveSnapshot(chatId, messages)
    }

    fun chatMessagesSnapshot(chatId: String): List<ChatMessage> {
        return messageCacheStore.snapshot(chatId)
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
        updateStore.setAvailableUpdate(versionCode, versionName)
    }

    fun availableUpdateVersionCode(): Int = updateStore.availableUpdateVersionCode()

    fun availableUpdateVersionName(): String? = updateStore.availableUpdateVersionName()

    fun clearAvailableUpdate() {
        updateStore.clearAvailableUpdate()
    }

    fun markUpdateSeen(versionCode: Int) {
        updateStore.markUpdateSeen(versionCode)
    }

    fun lastSeenUpdateCode(): Int = updateStore.lastSeenUpdateCode()

    fun setDownloadedUpdate(versionCode: Int, filePath: String) {
        updateStore.setDownloadedUpdate(versionCode, filePath)
    }

    fun downloadedUpdateVersionCode(): Int = updateStore.downloadedUpdateVersionCode()

    fun downloadedUpdatePath(): String? = updateStore.downloadedUpdatePath()

    fun clearDownloadedUpdate() {
        updateStore.clearDownloadedUpdate()
    }

    fun setUpdatesInfoCache(rawJson: String) {
        updateStore.setUpdatesInfoCache(rawJson)
    }

    fun updatesInfoCache(): String? = updateStore.updatesInfoCache()

    fun clearUpdatesInfoCache() {
        updateStore.clearUpdatesInfoCache()
    }

    fun pinnedChatIds(): Set<String> {
        return visibilityStore.pinnedChatIds()
    }

    fun pinnedChatOrder(): List<String> {
        return visibilityStore.pinnedChatOrder()
    }

    fun isChatPinned(chatId: String): Boolean {
        return visibilityStore.isChatPinned(chatId)
    }

    fun setChatPinned(chatId: String, pinned: Boolean) {
        visibilityStore.setChatPinned(chatId, pinned)
    }

    fun hiddenChatIds(): Set<String> {
        return visibilityStore.hiddenChatIds()
    }

    fun hideChat(chatId: String) {
        visibilityStore.hideChat(chatId)
    }

    fun hiddenMessageIds(chatId: String): Set<String> {
        return visibilityStore.hiddenMessageIds(chatId)
    }

    fun hideMessage(chatId: String, messageId: String) {
        visibilityStore.hideMessage(chatId, messageId)
    }

    fun removeHiddenMessage(chatId: String, messageId: String) {
        visibilityStore.removeHiddenMessage(chatId, messageId)
    }

    fun rememberedAccounts(): List<RememberedAccount> {
        return rememberedAccountsStore.accounts()
    }

    fun rememberedAccountsForServer(serverUrl: String): List<RememberedAccount> {
        return rememberedAccountsStore.accountsForServer(serverUrl)
    }

    fun restoreRememberedAccount(login: String, serverUrl: String): Boolean {
        val normalizedServer = normalizeServerUrl(serverUrl)
        val account = rememberedAccountsStore.findAccount(login, serverUrl) ?: return false

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
        rememberedAccountsStore.removeAccount(login, serverUrl)
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
        return value.let { DEFAULT_SERVER_URL }
    }

    private fun loadChatScrollStateRoot(): JSONObject {
        val raw = prefs.getString(KEY_CHAT_SCROLL_STATE, null).orEmpty()
        if (raw.isBlank()) return JSONObject()
        return runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
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
