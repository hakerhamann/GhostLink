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
import com.rezerv.app.util.ImageThumbnailLoader

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
        val showCenterControl = isUploading || playback.isDownloading || playback.isPreparing || !isLoaded
        progressView.isVisible = playback.isExpanded && playback.isActive && !playback.isAutoplay
        uploadProgressView.isVisible = showCenterControl && (isUploading || playback.isDownloading)
        playButton.isVisible = showCenterControl
        durationView.isVisible = false
        textureView.alpha = if (playback.isActive && !playback.isDownloading && !playback.isPreparing) 1f else 0f

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
        uploadProgressView.setProgressFraction(
            when {
                isUploading -> item.uploadProgress ?: 0f
                playback.isDownloading -> playback.downloadProgress ?: 0f
                else -> 0f
            }
        )

        playButton.text = when {
            isUploading -> "\u00D7"
            playback.isError -> "!"
            playback.isDownloading -> "\u00D7"
            playback.isPreparing -> "\u2026"
            !isLoaded && !isPending -> "\u2B07"
            isPending -> ""
            else -> "\u25B6"
        }
        playButton.alpha = when {
            isPending -> 0.5f
            else -> 0.9f
        }

        val thumbnailSource = localVideoPath
            .takeIf { it.isNotBlank() }
            ?: cachedVideo?.absolutePath
            ?: item.videoThumbnailUrl?.trim().orEmpty()
        if (thumbnailSource.startsWith("http://") || thumbnailSource.startsWith("https://")) {
            thumbnailView.isVisible = true
            ImageThumbnailLoader.bind(thumbnailView, thumbnailSource)
        } else {
            RoundVideoThumbnailLoader.bind(thumbnailView, thumbnailSource)
        }
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
