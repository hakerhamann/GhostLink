package com.rezerv.app.ui.adapters

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import com.rezerv.app.data.model.ChatMessage

internal object MessageReplyPreviewBinder {
    fun bind(
        container: View,
        senderView: TextView,
        textView: TextView,
        imageView: ImageView,
        item: ChatMessage,
        fallbackSenderName: String,
        onReplyPreviewTap: (ChatMessage) -> Unit
    ) {
        val hasReply = !item.replyToMessageId.isNullOrBlank() && !item.replyToText.isNullOrBlank()
        container.isVisible = hasReply
        if (hasReply) {
            senderView.text = item.replyToSenderName.orEmpty().ifBlank { fallbackSenderName }
            textView.text = item.replyToText.orEmpty()
            bindReplyImage(imageView, item.replyToImageUrl)
            textView.isVisible = shouldShowReplyText(item.replyToText, item.replyToImageUrl)
            container.setOnClickListener { onReplyPreviewTap(item) }
        } else {
            clearReplyImage(imageView)
            textView.isVisible = true
            container.setOnClickListener(null)
        }
    }

    private fun bindReplyImage(imageView: ImageView, imageUrl: String?) {
        val safeUrl = imageUrl?.trim().orEmpty()
        if (safeUrl.isBlank() || safeUrl.equals("null", ignoreCase = true)) {
            clearReplyImage(imageView)
            return
        }
        imageView.isVisible = true
        imageView.tag = safeUrl
        imageView.setImageDrawable(null)
        MessagePhotoBinder.bindThumbnail(imageView, safeUrl)
    }

    private fun clearReplyImage(imageView: ImageView) {
        imageView.isVisible = false
        imageView.tag = null
        imageView.setImageDrawable(null)
    }

    private fun shouldShowReplyText(replyText: String?, replyImageUrl: String?): Boolean {
        val text = replyText?.trim().orEmpty()
        if (text.isBlank()) return false
        return replyImageUrl.isNullOrBlank() || text != MessagePhotoBinder.PHOTO_FALLBACK_TEXT
    }
}
