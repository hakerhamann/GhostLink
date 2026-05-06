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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal object RoundVideoMessageBinder {
    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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
        onAttachTexture: (TextureView) -> Unit,
        onDetachTexture: (TextureView) -> Unit,
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
        container.isVisible = true
        container.alpha = 1f
        placeholderView.isVisible = true
        val isUploading = item.type == MessageType.VIDEO && item.sendState == MessageSendState.SENDING
        progressView.isVisible = !isUploading
        uploadProgressView.isVisible = isUploading
        playButton.isVisible = !playback.isPlaying || playback.showTransientOverlay
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
            playback.isError -> "!"
            isUploading || playback.isDownloading || isPending || playback.isPreparing -> "\u2026"
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
            ?: RoundVideoCache.getCachedFileIfExists(thumbnailView.context, videoUrl)?.absolutePath.orEmpty()
        RoundVideoThumbnailLoader.bind(thumbnailView, thumbnailSource)
        if (
            thumbnailSource.isBlank() &&
            videoUrl.isNotBlank() &&
            !videoUrl.startsWith("pending://") &&
            item.sendState == MessageSendState.SENT &&
            !playback.isDownloading
        ) {
            val context = thumbnailView.context.applicationContext
            prefetchScope.launch {
                val cached = RoundVideoCache.fileFor(context, videoUrl) { }
                if (cached != null) onCachedVideoReady(item.id)
            }
        }
        if (playback.isActive) {
            onAttachTexture(textureView)
        } else {
            onDetachTexture(textureView)
        }
        container.setOnClickListener(
            if (isPending && localVideoPath.isBlank()) {
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
