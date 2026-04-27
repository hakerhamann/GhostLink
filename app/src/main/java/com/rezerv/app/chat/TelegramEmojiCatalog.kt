package com.rezerv.app.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object TelegramEmojiCatalog {

    data class Category(
        val id: String,
        val icon: String,
        val title: String,
        val emojis: List<String>
    )

    @Volatile
    private var cached: List<Category>? = null

    fun load(context: Context): List<Category> {
        cached?.let { return it }
        return synchronized(this) {
            cached?.let { return@synchronized it }
            val loaded = runCatching {
                val raw = context.assets.open(CATALOG_ASSET_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
                parseCategories(JSONObject(raw).optJSONArray("categories"))
            }.getOrDefault(emptyList())
            cached = loaded
            loaded
        }
    }

    private fun parseCategories(raw: JSONArray?): List<Category> {
        if (raw == null) return emptyList()
        val categories = ArrayList<Category>(raw.length())
        for (index in 0 until raw.length()) {
            val item = raw.optJSONObject(index) ?: continue
            val emojisJson = item.optJSONArray("emojis") ?: continue
            val emojis = ArrayList<String>(emojisJson.length())
            for (emojiIndex in 0 until emojisJson.length()) {
                val emoji = emojisJson.optString(emojiIndex).trim()
                if (emoji.isNotEmpty()) {
                    emojis += emoji
                }
            }
            if (emojis.isEmpty()) continue
            categories += Category(
                id = item.optString("id").ifBlank { "cat_${index + 1}" },
                icon = item.optString("icon").ifBlank { emojis.first() },
                title = item.optString("title").ifBlank { "Category ${index + 1}" },
                emojis = emojis
            )
        }
        return categories
    }

    private const val CATALOG_ASSET_PATH = "telegram_emoji_catalog.json"
}
