package com.rezerv.app.chat

import android.net.Uri
import androidx.core.view.isVisible
import com.rezerv.app.data.model.ChatMessage
import com.rezerv.app.data.model.MessageType
import com.rezerv.app.databinding.ActivityChatBinding

internal class ChatInlineEditController(
    private val binding: ActivityChatBinding,
    private val selectedPhotoUris: MutableList<Uri>,
    private val resolveMessagePhotoUrls: (ChatMessage) -> List<String>,
    private val clearReplyTarget: () -> Unit,
    private val hideEmojiPanelForKeyboard: () -> Unit,
    private val showKeyboard: () -> Unit,
    private val updatePhotoDraftUi: () -> Unit,
    private val updateComposerActionState: () -> Unit
) {
    private val removedEditedPhotoUrls = mutableSetOf<String>()
    private var state: InlineEditState? = null

    val currentState: InlineEditState?
        get() = state

    fun isEditingMessage(): Boolean = state != null

    fun isEditingPhotoMessage(): Boolean = state?.message?.type == MessageType.IMAGE

    fun isEditingTextMessage(): Boolean = state?.message?.type == MessageType.TEXT

    fun begin(message: ChatMessage, serverMessageId: String, emojiVisible: Boolean) {
        state = InlineEditState(
            message = message,
            serverMessageId = serverMessageId
        )
        clearReplyTarget()
        selectedPhotoUris.clear()
        removedEditedPhotoUrls.clear()
        if (emojiVisible) {
            hideEmojiPanelForKeyboard()
        }

        binding.etMessage.setText(
            message.text.takeIf {
                it.isNotBlank() && !(message.type == MessageType.IMAGE && it.trim() == PHOTO_PREVIEW_TEXT)
            }.orEmpty()
        )
        binding.etMessage.setSelection(binding.etMessage.text?.length ?: 0)
        updateInlineEditUi()
        updatePhotoDraftUi()
        updateComposerActionState()
        binding.etMessage.requestFocus()
        showKeyboard()
    }

    fun cancel(): Boolean {
        if (!isEditingMessage()) return false
        clear()
        return true
    }

    fun finish() {
        clear()
    }

    fun updateInlineEditUi() {
        val editState = state
        binding.editContainer.isVisible = editState != null && editState.message.type != MessageType.IMAGE
        if (editState == null) {
            binding.tvEditTitle.text = ""
            binding.tvEditText.text = ""
            binding.btnCancelEdit.isEnabled = false
            return
        }

        binding.btnCancelEdit.isEnabled = true
        binding.tvEditTitle.text = when (editState.message.type) {
            MessageType.IMAGE -> "Редактирование фото"
            MessageType.VOICE -> "Редактирование голосового"
            MessageType.VIDEO -> "Редактирование видео"
            MessageType.TEXT -> "Редактирование сообщения"
        }
        binding.tvEditText.text = when (editState.message.type) {
            MessageType.IMAGE -> {
                val caption = editState.message.text.replace('\n', ' ')
                    .trim()
                    .takeIf { it.isNotBlank() && it != PHOTO_PREVIEW_TEXT }
                caption ?: "Фото: ${resolveMessagePhotoUrls(editState.message).size}"
            }

            MessageType.VOICE -> {
                editState.message.text.replace('\n', ' ').trim().ifBlank { VOICE_PREVIEW_TEXT }
            }

            MessageType.VIDEO -> {
                editState.message.text.replace('\n', ' ').trim().ifBlank { VIDEO_PREVIEW_TEXT }
            }

            MessageType.TEXT -> {
                editState.message.text.replace('\n', ' ').trim().ifBlank { "Текст сообщения" }
            }
        }
    }

    fun existingPhotoCountForLimit(): Int {
        val message = state?.message ?: return 0
        if (message.type != MessageType.IMAGE) return 0
        return resolveMessagePhotoUrls(message).count { it !in removedEditedPhotoUrls }
    }

    fun existingPhotoPreviews(): List<PhotoDraftPreview> {
        val message = state?.message ?: return emptyList()
        if (message.type != MessageType.IMAGE) return emptyList()
        return resolveMessagePhotoUrls(message)
            .filterNot { it in removedEditedPhotoUrls }
            .map { PhotoDraftPreview(source = it, existingUrl = it, selectedUriIndex = null) }
    }

    fun removeExistingPhoto(url: String) {
        removedEditedPhotoUrls += url
    }

    fun currentEditedPhotoSources(): List<String> {
        val message = state?.message ?: return emptyList()
        if (message.type != MessageType.IMAGE) return emptyList()
        return resolveMessagePhotoUrls(message)
            .filterNot { it in removedEditedPhotoUrls } +
            selectedPhotoUris.map(Uri::toString)
    }

    private fun clear() {
        state = null
        selectedPhotoUris.clear()
        removedEditedPhotoUrls.clear()
        binding.etMessage.text?.clear()
        updateInlineEditUi()
        updatePhotoDraftUi()
        updateComposerActionState()
    }

    private companion object {
        private const val VOICE_PREVIEW_TEXT = "\uD83C\uDFA4 Voice message"
        private const val PHOTO_PREVIEW_TEXT = "\uD83D\uDCF7 \u0424\u043E\u0442\u043E"
        private const val VIDEO_PREVIEW_TEXT = "\uD83C\uDFA5 \u0412\u0438\u0434\u0435\u043E"
    }
}

internal data class InlineEditState(
    val message: ChatMessage,
    val serverMessageId: String
)

internal data class PhotoDraftPreview(
    val source: String,
    val existingUrl: String?,
    val selectedUriIndex: Int?
)
