package com.rezerv.app.notifications

object ActiveChatTracker {

    @Volatile
    private var activeChatId: String? = null

    fun setActiveChat(chatId: String?) {
        activeChatId = chatId?.takeIf { it.isNotBlank() }
    }

    fun clear(chatId: String?) {
        if (chatId.isNullOrBlank()) return
        if (activeChatId == chatId) {
            activeChatId = null
        }
    }

    fun isActive(chatId: String?): Boolean {
        if (chatId.isNullOrBlank()) return false
        return activeChatId == chatId
    }
}
