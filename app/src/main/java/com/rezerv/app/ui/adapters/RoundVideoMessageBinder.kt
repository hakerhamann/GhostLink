package com.rezerv.app.ui.adapters

import android.view.TextureView
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import com.rezerv.app.data.model.ChatMessage
import com.rezerv.app.data.model.MessageSendState
import com.rezerv.app.data.model.MessageType
import com.rezerv.app.ui.widget.RoundVideoProgressView
import com.rezerv.app.util.ImageThumbnailLoader
import kotlin.math.abs

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
        @Suppress("UNUSED_PARAMETER")
        onAutoPlayVideo: (TextureView) -> Unit,
        onCancelUpload: (String) -> Unit,
        onAttachTexture: (TextureView) -> Unit,
        onDetachTexture: (TextureView) -> Unit,
        onCollapseExpandedByGesture: () -> Boolean,
        availableChatWidthPx: Int,
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
        container.clipToOutline = true
        resizeContainer(container, expanded = playback.isExpanded, availableChatWidthPx = availableChatWidthPx)
        container.alpha = 1f
        val showVideoFrame = playback.isActive &&
            !playback.isDownloading &&
            !playback.isPreparing &&
            playback.isFirstFrameReady
        placeholderView.isVisible = !showVideoFrame
        val isUploading = item.type == MessageType.VIDEO && item.sendState == MessageSendState.SENDING
        val showCenterControl = isUploading || playback.isDownloading || playback.isPreparing || !isLoaded
        progressView.isVisible = playback.isExpanded && playback.isActive && !playback.isAutoplay
        uploadProgressView.isVisible = showCenterControl && (isUploading || playback.isDownloading)
        playButton.isVisible = showCenterControl
        durationView.isVisible = false
        textureView.alpha = if (showVideoFrame) 1f else 0f

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
            thumbnailView.isVisible = !showVideoFrame
            ImageThumbnailLoader.bind(thumbnailView, thumbnailSource)
        } else {
            RoundVideoThumbnailLoader.bind(thumbnailView, thumbnailSource)
            if (showVideoFrame) thumbnailView.isVisible = false
        }
        if (playback.isActive) {
            onAttachTexture(textureView)
        } else {
            onDetachTexture(textureView)
        }
        container.setOnClickListener(
            if (isUploading) {
                { onCancelUpload(item.id) }
            } else {
                { onToggleVideo(textureView) }
            }
        )
        bindExpandedSwipe(container, playback.isExpanded, onCollapseExpandedByGesture)
    }

    private fun bindExpandedSwipe(
        container: View,
        isExpanded: Boolean,
        onCollapseExpandedByGesture: () -> Boolean
    ) {
        if (!isExpanded) {
            container.setOnTouchListener(null)
            return
        }
        val threshold = 48f * container.resources.displayMetrics.density
        var downX = 0f
        var downY = 0f
        container.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    val collapse = dx > threshold || abs(dy) > threshold
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    if (collapse) onCollapseExpandedByGesture() else view.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    true
                }
                else -> true
            }
        }
    }

    private fun resizeContainer(container: View, expanded: Boolean, availableChatWidthPx: Int) {
        val stableWidth = availableChatWidthPx.takeIf { it > 0 }
        val rootWidth = container.rootView?.width?.takeIf { it > 0 }
        val fallbackWidth = container.resources.displayMetrics.widthPixels
        val availableWidth = (stableWidth ?: rootWidth ?: fallbackWidth).coerceAtLeast(1)
        val target = if (expanded) {
            availableWidth
        } else {
            (availableWidth * 0.55f).toInt()
        }.coerceIn(1, availableWidth)
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
        container.setOnTouchListener(null)
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
