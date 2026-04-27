package com.rezerv.app.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Formatters {
    fun formatChatDate(timestamp: Long): String {
        if (timestamp <= 0L) return ""

        val now = System.currentTimeMillis()
        val diff = (now - timestamp).coerceAtLeast(0L)
        val days = diff / (24L * 60L * 60L * 1000L)

        return when {
            days == 0L -> formatTime(timestamp)
            days in 1L..6L -> "$days дн"
            else -> {
                val format = SimpleDateFormat("d MMM", Locale("ru"))
                format.format(Date(timestamp)).replace('.', ' ').trim()
            }
        }
    }

    fun formatTime(timestamp: Long): String {
        if (timestamp <= 0L) return ""
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
    }
}
