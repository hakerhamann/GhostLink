package com.rezerv.app.ui.adapters

import android.view.TextureView
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import com.rezerv.app.data.model.ChatMessage
import com.rezerv.app.data.model.MessageSendState
import com.rezerv.app.ui.widget.RoundVideoProgressView

internal object RoundVideoMessageBinder {
    fun bind(
        container: View,
        textureView: TextureView,
        thumbnailView: ImageView,
        placeholderView: View,
        progressView: RoundVideoProgressView,
        playButton: TextView,
        durationView: TextView,
        item: ChatMessage,
        playback: RoundVideoPlaybackState,
        onToggleVideo: (TextureView) -> Unit,
        onAttachTexture: (TextureView) -> Unit,
        onDetachTexture: (TextureView) -> Unit
    ) {
        val videoUrl = item.videoUrl?.trim().orEmpty()
        if (videoUrl.isBlank()) {
            clear(
                container = container,
                textureView = textureView,
                thumbnailView = thumbnailView,
                placeholderView = placeholderView,
                progressView = progressView,
                playButton = playButton,
                durationView = durationView,
                onDetachTexture = onDetachTexture
            )
            return
        }

        val isPending = item.sendState != MessageSendState.SENT || videoUrl.startsWith("pending://")
        container.isVisible = true
        container.alpha = if (isPending) 0.66f else 1f
        placeholderView.isVisible = true
        progressView.isVisible = true
        playButton.isVisible = true
        durationView.isVisible = true
        textureView.alpha = if (playback.isActive) 1f else 0f

        val durationMs = playback.durationMs.takeIf { it > 0 }
            ?: item.videoDurationSec.coerceAtLeast(0) * 1000
        durationView.text = formatMessageDuration(durationMs)
        val progressFraction = when {
            durationMs <= 0 -> 0f
            playback.isActive -> playback.progressMs.toFloat() / durationMs.toFloat()
            else -> 0f
        }
        progressView.setProgressFraction(progressFraction)

        playButton.text = when {
            isPending || playback.isPreparing -> "\u2026"
            playback.isPlaying -> "\u275A\u275A"
            else -> "\u25B6"
        }
        playButton.alpha = when {
            playback.isPlaying -> 0.58f
            isPending -> 0.5f
            else -> 0.9f
        }

        RoundVideoThumbnailLoader.bind(thumbnailView, videoUrl)
        if (playback.isActive) {
            onAttachTexture(textureView)
        } else {
            onDetachTexture(textureView)
        }
        container.setOnClickListener(
            if (isPending) {
                null
            } else {
                { onToggleVideo(textureView) }
            }
        )
    }

    fun clear(
        container: View,
        textureView: TextureView,
        thumbnailView: ImageView,
        placeholderView: View,
        progressView: RoundVideoProgressView,
        playButton: TextView,
        durationView: TextView,
        onDetachTexture: (TextureView) -> Unit
    ) {
        onDetachTexture(textureView)
        container.isVisible = false
        container.alpha = 1f
        container.setOnClickListener(null)
        textureView.alpha = 0f
        placeholderView.isVisible = true
        progressView.setProgressFraction(0f)
        progressView.isVisible = false
        playButton.isVisible = false
        durationView.isVisible = false
        RoundVideoThumbnailLoader.clear(thumbnailView)
    }
}
