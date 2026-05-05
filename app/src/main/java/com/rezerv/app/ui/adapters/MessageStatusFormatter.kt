package com.rezerv.app.ui.adapters

import com.rezerv.app.data.model.ChatMessage
import com.rezerv.app.data.model.MessageSendState

internal object MessageStatusFormatter {
    fun resolve(
        item: ChatMessage,
        currentUserId: String,
        recipientsCount: Int,
        isGroupChat: Boolean
    ): MessageStatusUi {
        when (item.sendState) {
            MessageSendState.SENDING -> {
                return MessageStatusUi(
                    text = "...",
                    color = 0xFF93A994.toInt()
                )
            }

            MessageSendState.FAILED -> {
                return MessageStatusUi(
                    text = "!",
                    color = 0xFFFF6B6B.toInt()
                )
            }

            MessageSendState.SENT -> Unit
        }

        val recipients = recipientsCount.coerceAtLeast(1)
        val deliveredCount = item.deliveredBy.asSequence().filter { it != currentUserId }.toSet().size
            .coerceIn(0, recipients)
        val readCount = item.readBy.asSequence().filter { it != currentUserId }.toSet().size
            .coerceIn(0, recipients)

        val glyph = when {
            readCount > 0 -> "✓✓"
            deliveredCount > 0 -> "✓✓"
            else -> "✓"
        }
        val color = if (readCount > 0) {
            0xFF89FF3AL.toInt()
        } else {
            0xFF93A994.toInt()
        }

        val text = if (isGroupChat) {
            "$glyph $readCount/$recipients"
        } else {
            glyph
        }
        return MessageStatusUi(text = text, color = color)
    }
}

internal data class MessageStatusUi(
    val text: String,
    val color: Int
)
