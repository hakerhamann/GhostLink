package com.rezerv.app.ui.adapters

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import com.rezerv.app.data.model.ChatMessage
import com.rezerv.app.data.model.MessageType
import kotlin.math.max

internal class RoundVideoPlayerController(
    private val notifyMessageChanged: (String?) -> Unit
) {
    private val progressHandler = Handler(Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null
    private var activeMessageId: String? = null
    private var preparingMessageId: String? = null
    private var boundTextureView: TextureView? = null
    private var surface: Surface? = null
    private var prepared: Boolean = false
    private var shouldStartWhenReady: Boolean = false
    private var videoWidth: Int = 0
    private var videoHeight: Int = 0

    private val progressUpdater = object : Runnable {
        override fun run() {
            val messageId = activeMessageId ?: return
            notifyMessageChanged(messageId)
            progressHandler.postDelayed(this, PLAYBACK_TICK_MS)
        }
    }

    fun playbackStateFor(item: ChatMessage): RoundVideoPlaybackState {
        val player = mediaPlayer
        val isActive = activeMessageId == item.id && player != null
        val durationMs = when {
            isActive && prepared && player != null && player.duration > 0 -> player.duration
            else -> item.videoDurationSec.coerceAtLeast(0) * 1000
        }
        val progressMs = when {
            isActive && prepared && player != null -> player.currentPosition.coerceAtLeast(0)
            else -> 0
        }
        return RoundVideoPlaybackState(
            isActive = isActive,
            isPreparing = preparingMessageId == item.id,
            isPlaying = isActive && player?.isPlaying == true,
            durationMs = durationMs,
            progressMs = progressMs
        )
    }

    fun attachTexture(item: ChatMessage, textureView: TextureView) {
        if (item.id != activeMessageId || mediaPlayer == null) {
            clearTextureListener(textureView)
            return
        }
        attachTextureView(textureView)
    }

    fun toggle(item: ChatMessage, textureView: TextureView) {
        if (item.type != MessageType.VIDEO) return
        val videoUrl = item.videoUrl?.trim().orEmpty()
        if (videoUrl.isBlank() || videoUrl.startsWith("pending://")) return

        val player = mediaPlayer
        if (activeMessageId == item.id && player != null) {
            attachTextureView(textureView)
            if (preparingMessageId == item.id) {
                release()
                return
            }
            if (player.isPlaying) {
                shouldStartWhenReady = false
                player.pause()
                progressHandler.removeCallbacks(progressUpdater)
                notifyMessageChanged(item.id)
            } else {
                shouldStartWhenReady = true
                startIfReady()
            }
            return
        }

        val previousActive = activeMessageId
        release(clearNotifications = true)
        activeMessageId = item.id
        preparingMessageId = item.id
        shouldStartWhenReady = true
        prepared = false
        videoWidth = 0
        videoHeight = 0
        notifyMessageChanged(previousActive)
        notifyMessageChanged(item.id)
        attachTextureView(textureView)
        createPlayer(item, videoUrl)
    }

    fun stopIfBoundTexture(textureView: TextureView) {
        if (boundTextureView === textureView) {
            release()
        } else {
            clearTextureListener(textureView)
        }
    }

    fun release() {
        release(clearNotifications = false)
    }

    private fun createPlayer(item: ChatMessage, videoUrl: String) {
        runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                setDataSource(videoUrl)
                surface?.let { setSurface(it) }
                setOnPreparedListener { player ->
                    if (activeMessageId != item.id) {
                        player.release()
                        return@setOnPreparedListener
                    }
                    prepared = true
                    preparingMessageId = null
                    this@RoundVideoPlayerController.videoWidth = player.videoWidth
                    this@RoundVideoPlayerController.videoHeight = player.videoHeight
                    boundTextureView?.let { applyCenterCrop(it) }
                    startIfReady()
                    notifyMessageChanged(item.id)
                }
                setOnCompletionListener {
                    release()
                }
                setOnErrorListener { _, _, _ ->
                    release()
                    true
                }
                prepareAsync()
            }
        }.onSuccess { player ->
            mediaPlayer = player
        }.onFailure {
            release()
        }
    }

    private fun attachTextureView(textureView: TextureView) {
        if (boundTextureView === textureView) {
            if (textureView.isAvailable && surface == null) {
                attachSurface(textureView.surfaceTexture)
            }
            applyCenterCrop(textureView)
            return
        }

        boundTextureView?.surfaceTextureListener = null
        releaseSurface()
        boundTextureView = textureView
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                attachSurface(surfaceTexture)
            }

            override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                applyCenterCrop(textureView)
            }

            override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                release()
                return true
            }

            override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
        }
        if (textureView.isAvailable) {
            attachSurface(textureView.surfaceTexture)
        }
    }

    private fun attachSurface(surfaceTexture: SurfaceTexture?) {
        if (surfaceTexture == null) return
        releaseSurface()
        surface = Surface(surfaceTexture)
        mediaPlayer?.setSurface(surface)
        boundTextureView?.let { applyCenterCrop(it) }
        startIfReady()
    }

    private fun startIfReady() {
        val player = mediaPlayer ?: return
        if (!prepared || surface == null || !shouldStartWhenReady) return
        player.start()
        notifyMessageChanged(activeMessageId)
        progressHandler.removeCallbacks(progressUpdater)
        progressHandler.post(progressUpdater)
    }

    private fun release(clearNotifications: Boolean) {
        val previousActive = activeMessageId
        val previousPreparing = preparingMessageId
        progressHandler.removeCallbacks(progressUpdater)
        boundTextureView?.surfaceTextureListener = null
        boundTextureView?.setTransform(Matrix())
        boundTextureView = null
        releaseSurface()
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        activeMessageId = null
        preparingMessageId = null
        prepared = false
        shouldStartWhenReady = false
        videoWidth = 0
        videoHeight = 0
        if (!clearNotifications) {
            notifyMessageChanged(previousActive)
            notifyMessageChanged(previousPreparing)
        }
    }

    private fun releaseSurface() {
        runCatching { surface?.release() }
        surface = null
    }

    private fun clearTextureListener(textureView: TextureView) {
        textureView.surfaceTextureListener = null
        textureView.setTransform(Matrix())
    }

    private fun applyCenterCrop(textureView: TextureView) {
        val viewWidth = textureView.width.toFloat()
        val viewHeight = textureView.height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f || videoWidth <= 0 || videoHeight <= 0) return
        val scale = max(viewWidth / videoWidth.toFloat(), viewHeight / videoHeight.toFloat())
        val scaledWidth = videoWidth * scale
        val scaledHeight = videoHeight * scale
        val matrix = Matrix().apply {
            setScale(scaledWidth / viewWidth, scaledHeight / viewHeight, viewWidth / 2f, viewHeight / 2f)
        }
        textureView.setTransform(matrix)
    }

    private companion object {
        const val PLAYBACK_TICK_MS = 80L
    }
}

internal data class RoundVideoPlaybackState(
    val isActive: Boolean,
    val isPreparing: Boolean,
    val isPlaying: Boolean,
    val durationMs: Int,
    val progressMs: Int
)
