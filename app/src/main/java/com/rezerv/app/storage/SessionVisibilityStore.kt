package com.rezerv.app.storage

import android.content.SharedPreferences
import org.json.JSONArray

internal class SessionVisibilityStore(
    private val prefs: SharedPreferences,
    private val uidKey: String,
    private val pinnedChatsKey: String,
    private val pinnedChatsOrderKey: String,
    private val hiddenChatsKey: String,
    private val hiddenMessagesKey: String
) {
    fun pinnedChatIds(): Set<String> {
        return prefs.getStringSet(pinnedChatsKey, emptySet())?.toSet() ?: emptySet()
    }

    fun pinnedChatOrder(): List<String> {
        val pinned = pinnedChatIds()
        if (pinned.isEmpty()) return emptyList()

        val ordered = linkedSetOf<String>()
        val raw = prefs.getString(pinnedChatsOrderKey, null).orEmpty()
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
            .putStringSet(pinnedChatsKey, current)
            .putString(pinnedChatsOrderKey, JSONArray(order).toString())
            .apply()
    }

    fun hiddenChatIds(): Set<String> {
        val uid = prefs.getString(uidKey, null) ?: return emptySet()
        val prefix = "$uid::"
        return prefs.getStringSet(hiddenChatsKey, emptySet())
            ?.mapNotNull { value ->
                if (value.startsWith(prefix)) value.removePrefix(prefix) else null
            }
            ?.toSet()
            ?: emptySet()
    }

    fun hideChat(chatId: String) {
        val uid = prefs.getString(uidKey, null) ?: return
        if (chatId.isBlank()) return
        val key = "$uid::$chatId"
        val current = linkedSetOf<String>().apply {
            addAll(prefs.getStringSet(hiddenChatsKey, emptySet()) ?: emptySet())
        }
        current += key
        prefs.edit().putStringSet(hiddenChatsKey, current).apply()
    }

    fun hiddenMessageIds(chatId: String): Set<String> {
        val uid = prefs.getString(uidKey, null) ?: return emptySet()
        if (chatId.isBlank()) return emptySet()
        val prefix = "$uid::$chatId::"
        return prefs.getStringSet(hiddenMessagesKey, emptySet())
            ?.mapNotNull { value ->
                if (value.startsWith(prefix)) value.removePrefix(prefix) else null
            }
            ?.toSet()
            ?: emptySet()
    }

    fun hideMessage(chatId: String, messageId: String) {
        val uid = prefs.getString(uidKey, null) ?: return
        if (chatId.isBlank() || messageId.isBlank()) return
        val key = "$uid::$chatId::$messageId"
        val current = linkedSetOf<String>().apply {
            addAll(prefs.getStringSet(hiddenMessagesKey, emptySet()) ?: emptySet())
        }
        current += key
        prefs.edit().putStringSet(hiddenMessagesKey, current).apply()
    }

    fun removeHiddenMessage(chatId: String, messageId: String) {
        val uid = prefs.getString(uidKey, null) ?: return
        if (chatId.isBlank() || messageId.isBlank()) return
        val key = "$uid::$chatId::$messageId"
        val current = linkedSetOf<String>().apply {
            addAll(prefs.getStringSet(hiddenMessagesKey, emptySet()) ?: emptySet())
        }
        if (current.remove(key)) {
            prefs.edit().putStringSet(hiddenMessagesKey, current).apply()
        }
    }
}
