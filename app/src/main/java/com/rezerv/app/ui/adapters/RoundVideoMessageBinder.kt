package com.rezerv.app.ui.adapters

import android.view.TextureView
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import com.rezerv.app.data.model.ChatMessage
import com.rezerv.app.data.model.MessageSendState
import com.rezerv.app.data.model.MessageType
import com.rezerv.app.ui.widget.RoundVideoProgressView

internal object RoundVideoMessageBinder {
    fun bind(
        container: View,
        textureView: TextureView,
        thumbnailView: ImageView,
        placeholderView: View,
        progressView: RoundVideoProgressView,
        uploadProgressView: RoundVideoProgressView,
        playButton: TextView,
        durationView: TextView,
        item: ChatMessage,
        playback: RoundVideoPlaybackState,
        onToggleVideo: (TextureView) -> Unit,
        onAutoPlayVideo: (TextureView) -> Unit,
        onCancelUpload: (String) -> Unit,
        onAttachTexture: (TextureView) -> Unit,
        onDetachTexture: (TextureView) -> Unit,
        @Suppress("UNUSED_PARAMETER")
        onCachedVideoReady: (String) -> Unit
    ) {
        val videoUrl = item.videoUrl?.trim().orEmpty()
        val localVideoPath = item.localVideoPath?.trim().orEmpty()
        if (videoUrl.isBlank() && localVideoPath.isBlank()) {
            clear(
                container = container,
                textureView = textureView,
                thumbnailView = thumbnailView,
                placeholderView = placeholderView,
                progressView = progressView,
                uploadProgressView = uploadProgressView,
                playButton = playButton,
                durationView = durationView,
                onDetachTexture = onDetachTexture
            )
            return
        }

        val isPending = item.sendState != MessageSendState.SENT || videoUrl.startsWith("pending://")
        val cachedVideo = RoundVideoCache.getCachedFileIfExists(container.context, videoUrl)
        val isLoaded = localVideoPath.isNotBlank() || cachedVideo != null
        container.isVisible = true
        resizeContainer(container, expanded = playback.isExpanded)
        container.alpha = 1f
        placeholderView.isVisible = true
        val isUploading = item.type == MessageType.VIDEO && item.sendState == MessageSendState.SENDING
        progressView.isVisible = !isUploading && (playback.isActive || playback.isDownloading)
        uploadProgressView.isVisible = isUploading
        playButton.isVisible = when {
            isUploading -> true
            isLoaded && playback.isAutoplay -> false
            isLoaded && playback.isPlaying && !playback.isExpanded -> false
            else -> true
        }
        durationView.isVisible = false
        textureView.alpha = if (playback.isActive) 1f else 0f

        val durationMs = playback.durationMs.takeIf { it > 0 }
            ?: item.videoDurationSec.coerceAtLeast(0) * 1000
        val progressFraction = when {
            durationMs <= 0 -> 0f
            playback.isActive -> playback.progressMs.toFloat() / durationMs.toFloat()
            playback.downloadProgress != null -> playback.downloadProgress
            isPending -> 0.12f
            else -> 0f
        }
        progressView.setProgressFraction(progressFraction)
        uploadProgressView.setProgressFraction(item.uploadProgress ?: 0f)

        playButton.text = when {
            isUploading -> "\u00D7"
            playback.isError -> "!"
            playback.isDownloading || playback.isPreparing -> "\u2026"
            !isLoaded && !isPending -> "\u2B07"
            isPending -> ""
            playback.isPlaying -> ""
            else -> "\u25B6"
        }
        playButton.alpha = when {
            playback.isPlaying -> 0f
            isPending -> 0.5f
            else -> 0.9f
        }

        val thumbnailSource = localVideoPath
            .takeIf { it.isNotBlank() }
            ?: cachedVideo?.absolutePath.orEmpty()
        RoundVideoThumbnailLoader.bind(thumbnailView, thumbnailSource)
        if (playback.isActive) {
            onAttachTexture(textureView)
        } else {
            onDetachTexture(textureView)
        }
        if (isLoaded && !playback.isActive && item.sendState == MessageSendState.SENT) {
            onAutoPlayVideo(textureView)
        }
        container.setOnClickListener(
            if (isUploading) {
                { onCancelUpload(item.id) }
            } else {
                { onToggleVideo(textureView) }
            }
        )
    }

    private fun resizeContainer(container: View, expanded: Boolean) {
        val screenWidth = container.resources.displayMetrics.widthPixels
        val target = if (expanded) {
            screenWidth
        } else {
            (screenWidth * 0.55f).toInt()
        }.coerceAtLeast(1)
        val params = container.layoutParams
        if (params.width != target || params.height != target) {
            params.width = target
            params.height = target
            container.layoutParams = params
        }
    }

    fun clear(
        container: View,
        textureView: TextureView,
        thumbnailView: ImageView,
        placeholderView: View,
        progressView: RoundVideoProgressView,
        uploadProgressView: RoundVideoProgressView,
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
        uploadProgressView.setProgressFraction(0f)
        uploadProgressView.isVisible = false
        playButton.isVisible = false
        durationView.isVisible = false
        RoundVideoThumbnailLoader.clear(thumbnailView)
    }
}
