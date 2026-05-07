package com.rezerv.app.ui.adapters

import android.view.TextureView
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
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
        onReplyToMessage: (ChatMessage) -> Unit,
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
        bindExpandedSwipe(container, playback.isExpanded, item, onCollapseExpandedByGesture, onReplyToMessage)
    }

    private fun bindExpandedSwipe(
        container: View,
        isExpanded: Boolean,
        item: ChatMessage,
        onCollapseExpandedByGesture: () -> Boolean,
        onReplyToMessage: (ChatMessage) -> Unit
    ) {
        if (!isExpanded) {
            container.setOnTouchListener(null)
            return
        }
        val threshold = 96f * container.resources.displayMetrics.density
        val interpolator = FastOutSlowInInterpolator()
        var downX = 0f
        var downY = 0f
        var dragging = false
        container.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.animate().cancel()
                    downX = event.rawX
                    downY = event.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && (abs(dx) > 8f || abs(dy) > 8f)) {
                        dragging = true
                    }
                    if (dragging) {
                        if (dx < 0f && abs(dx) > abs(dy)) {
                            view.parent?.requestDisallowInterceptTouchEvent(false)
                        } else {
                            view.parent?.requestDisallowInterceptTouchEvent(true)
                            view.translationX = dx.coerceAtLeast(0f)
                            view.translationY = dy
                            val progress = ((abs(dx) + abs(dy)) / threshold).coerceIn(0f, 1f)
                            val scale = 1f - 0.04f * progress
                            view.scaleX = scale
                            view.scaleY = scale
                            view.alpha = 1f - 0.08f * progress
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    when {
                        dx < -threshold && abs(dx) > abs(dy) -> {
                            animateReset(view, interpolator) {
                                onReplyToMessage(item)
                            }
                        }
                        dx > threshold || abs(dy) > threshold -> {
                            animateCollapse(view, dx, dy, threshold, interpolator) {
                                onCollapseExpandedByGesture()
                            }
                        }
                        dragging -> animateReset(view, interpolator)
                        else -> view.performClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    animateReset(view, interpolator)
                    true
                }
                else -> true
            }
        }
    }

    private fun animateCollapse(
        view: View,
        dx: Float,
        dy: Float,
        threshold: Float,
        interpolator: FastOutSlowInInterpolator,
        endAction: () -> Unit
    ) {
        val targetX = if (dx > 0f) threshold * 1.4f else 0f
        val targetY = if (abs(dy) > threshold) dy.coerceIn(-threshold * 1.4f, threshold * 1.4f) else 0f
        view.animate()
            .translationX(targetX)
            .translationY(targetY)
            .scaleX(0.92f)
            .scaleY(0.92f)
            .alpha(0.85f)
            .setDuration(120L)
            .setInterpolator(interpolator)
            .withEndAction {
                view.translationX = 0f
                view.translationY = 0f
                view.scaleX = 1f
                view.scaleY = 1f
                view.alpha = 1f
                endAction()
            }
            .start()
    }

    private fun animateReset(
        view: View,
        interpolator: FastOutSlowInInterpolator,
        endAction: (() -> Unit)? = null
    ) {
        view.animate()
            .translationX(0f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(170L)
            .setInterpolator(interpolator)
            .withEndAction { endAction?.invoke() }
            .start()
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
