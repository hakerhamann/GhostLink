package com.rezerv.app.chat

import android.content.Context
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.rezerv.app.R
import com.rezerv.app.data.model.ChatMessage
import com.rezerv.app.data.model.MessageType
import com.rezerv.app.databinding.ActivityChatBinding
import com.rezerv.app.ui.adapters.MessageAdapter
import com.rezerv.app.util.ImageThumbnailLoader

internal class ChatReplyController(
    private val context: Context,
    private val binding: ActivityChatBinding,
    private val adapterProvider: () -> MessageAdapter?,
    private val messagesProvider: () -> List<ChatMessage>,
    private val isEditingMessage: () -> Boolean,
    private val cancelInlineEdit: () -> Unit,
    private val isEmojiPanelActiveOrPending: () -> Boolean,
    private val showKeyboard: () -> Unit,
    private val resolveServerMessageId: (String?) -> String?,
    private val resolveMessagePhotoUrls: (ChatMessage) -> List<String>,
    private val dpToPx: (Float) -> Int
) {
    var replyTargetMessage: ChatMessage? = null
        private set

    fun setReplyTarget(message: ChatMessage) {
        if (isEditingMessage()) {
            cancelInlineEdit()
        }
        replyTargetMessage = message
        binding.replyContainer.isVisible = true
        binding.tvReplyTitle.text = context.getString(R.string.replying_to, message.senderName)
        binding.tvReplyText.text = replyPreviewText(message)
        bindReplyTargetImage(replyPreviewImageUrl(message))
        if (!isEmojiPanelActiveOrPending()) {
            binding.etMessage.requestFocus()
            showKeyboard()
        }
    }

    fun clearReplyTarget() {
        replyTargetMessage = null
        binding.replyContainer.isVisible = false
        binding.tvReplyTitle.text = ""
        binding.tvReplyText.text = ""
        clearReplyTargetImage()
    }

    fun clearIfMatches(message: ChatMessage, serverMessageId: String?) {
        val replyTargetId = replyTargetMessage?.id
        val replyServerMessageId = resolveServerMessageId(replyTargetId)
        if (replyTargetId == message.id || (serverMessageId != null && replyServerMessageId == serverMessageId)) {
            clearReplyTarget()
        }
    }

    fun onReplyPreviewTap(message: ChatMessage) {
        val targetId = message.replyToMessageId ?: return
        val currentList = messagesProvider()
        val targetIndex = currentList.indexOfFirst {
            it.id == targetId || resolveServerMessageId(it.id) == targetId
        }
        if (targetIndex >= 0) {
            val targetMessageId = currentList[targetIndex].id
            binding.recyclerMessages.smoothScrollToPosition(targetIndex)
            adapterProvider()?.highlightMessage(targetMessageId)
            binding.recyclerMessages.postDelayed(
                {
                    val refreshedList = messagesProvider()
                    val refreshIndex = refreshedList.indexOfFirst {
                        it.id == targetId || resolveServerMessageId(it.id) == targetId
                    }
                    if (refreshIndex >= 0) {
                        val refreshTargetId = refreshedList[refreshIndex].id
                        binding.recyclerMessages.smoothScrollToPosition(refreshIndex)
                        adapterProvider()?.highlightMessage(refreshTargetId)
                    }
                },
                REPLY_PREVIEW_RETRY_DELAY_MS
            )
        } else {
            Toast.makeText(context, "Source message not found", Toast.LENGTH_SHORT).show()
        }
    }

    fun replyPreviewText(message: ChatMessage): String {
        return when (message.type) {
            MessageType.VOICE -> VOICE_PREVIEW_TEXT
            MessageType.IMAGE -> message.text.normalizeReplyPreviewText()
                .takeIf { it.isNotBlank() && it != PHOTO_PREVIEW_TEXT }
                ?: PHOTO_PREVIEW_TEXT
            MessageType.VIDEO -> VIDEO_PREVIEW_TEXT
            MessageType.SYSTEM,
            MessageType.SYSTEM_AVATAR -> message.text.normalizeReplyPreviewText().ifBlank { "..." }
            MessageType.TEXT -> message.text.normalizeReplyPreviewText().ifBlank { "..." }
        }
    }

    fun replyPreviewImageUrl(message: ChatMessage): String? {
        if (message.type != MessageType.IMAGE) return null
        return resolveMessagePhotoUrls(message).firstOrNull()
    }

    private fun String.normalizeReplyPreviewText(): String {
        return replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
    }

    private fun bindReplyTargetImage(imageUrl: String?) {
        val safeUrl = imageUrl?.trim().orEmpty()
        if (safeUrl.isBlank()) {
            clearReplyTargetImage()
            binding.tvReplyText.isVisible = true
            return
        }
        binding.tvReplyText.isVisible = shouldShowReplyTargetText()
        binding.ivReplyImage.isVisible = true
        ImageThumbnailLoader.bind(binding.ivReplyImage, safeUrl, dpToPx(84f))
    }

    private fun clearReplyTargetImage() {
        binding.ivReplyImage.isVisible = false
        binding.ivReplyImage.tag = null
        binding.ivReplyImage.setImageDrawable(null)
        binding.tvReplyText.isVisible = true
    }

    private fun shouldShowReplyTargetText(): Boolean {
        val text = binding.tvReplyText.text?.toString()?.trim().orEmpty()
        return text.isNotBlank() && text != PHOTO_PREVIEW_TEXT
    }

    private companion object {
        private const val REPLY_PREVIEW_RETRY_DELAY_MS = 180L
        private const val VOICE_PREVIEW_TEXT = "\uD83C\uDFA4 Voice message"
        private const val PHOTO_PREVIEW_TEXT = "\uD83D\uDCF7 \u0424\u043E\u0442\u043E"
        private const val VIDEO_PREVIEW_TEXT = "\uD83C\uDFA5 \u0412\u0438\u0434\u0435\u043E"
    }
}
