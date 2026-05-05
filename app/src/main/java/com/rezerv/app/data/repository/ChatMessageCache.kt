package com.rezerv.app.data.repository

import com.rezerv.app.data.model.ChatMessage
import com.rezerv.app.storage.SessionStore
import java.util.concurrent.ConcurrentHashMap

internal class ChatMessageCache(
    private val sessionStore: SessionStore
) {
    private val messagesCache = ConcurrentHashMap<String, List<ChatMessage>>()

    fun contains(chatId: String): Boolean {
        ensureLoaded(chatId)
        return messagesCache.containsKey(chatId)
    }

    fun get(chatId: String): List<ChatMessage> {
        ensureLoaded(chatId)
        return messagesCache[chatId] ?: emptyList()
    }

    fun raw(chatId: String): List<ChatMessage>? {
        return messagesCache[chatId]
    }

    fun ensureLoaded(chatId: String) {
        if (chatId.isBlank()) return
        if (messagesCache.containsKey(chatId)) return
        val persisted = sessionStore.chatMessagesSnapshot(chatId)
        if (persisted.isNotEmpty()) {
            messagesCache[chatId] = persisted
        }
    }

    fun saveSnapshot(chatId: String, messages: List<ChatMessage>) {
        if (chatId.isBlank()) return
        messagesCache[chatId] = messages
        sessionStore.saveChatMessagesSnapshot(chatId, messages)
    }

    fun replaceMessage(chatId: String, message: ChatMessage) {
        val normalizedChatId = chatId.trim()
        val messageId = message.id.trim()
        if (normalizedChatId.isBlank() || messageId.isBlank()) return
        ensureLoaded(normalizedChatId)
        val current = messagesCache[normalizedChatId].orEmpty()
        if (current.isEmpty()) return
        val replaced = current.map { existing ->
            if (existing.id == messageId) message else existing
        }
        if (replaced == current) return
        messagesCache[normalizedChatId] = replaced
        sessionStore.saveChatMessagesSnapshot(normalizedChatId, replaced)
    }
}
