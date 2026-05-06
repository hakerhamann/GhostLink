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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

internal class RoundVideoPlayerController(
    private val notifyMessageChanged: (String?) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val progressHandler = Handler(Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null
    private var activeMessageId: String? = null
    private var preparingMessageId: String? = null
    private var downloadingMessageId: String? = null
    private var errorMessageId: String? = null
    private var downloadProgress: Float? = null
    private var completedMessageId: String? = null
    private var overlayUntilMs: Long = 0L
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
        val isPreparedActive = isActive && prepared && player != null
        val durationMs = when {
            isPreparedActive && runCatching { player!!.duration }.getOrDefault(0) > 0 -> player!!.duration
            else -> item.videoDurationSec.coerceAtLeast(0) * 1000
        }
        val progressMs = when {
            isPreparedActive -> runCatching { player!!.currentPosition.coerceAtLeast(0) }.getOrDefault(0)
            else -> 0
        }
        return RoundVideoPlaybackState(
            isActive = isActive,
            isPreparing = preparingMessageId == item.id,
            isDownloading = downloadingMessageId == item.id,
            isPlaying = isActive && runCatching { player?.isPlaying == true }.getOrDefault(false),
            isCompleted = completedMessageId == item.id,
            isError = errorMessageId == item.id,
            showTransientOverlay = overlayUntilMs > System.currentTimeMillis() && activeMessageId == item.id,
            downloadProgress = if (downloadingMessageId == item.id) downloadProgress else null,
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
        val localVideoPath = item.localVideoPath?.trim().orEmpty()
        if ((videoUrl.isBlank() || videoUrl.startsWith("pending://")) && localVideoPath.isBlank()) return

        val player = mediaPlayer
        if (activeMessageId == item.id && player != null) {
            attachTextureView(textureView)
            if (preparingMessageId == item.id) {
                release()
                return
            }
            if (player.isPlaying) {
                shouldStartWhenReady = false
                overlayUntilMs = Long.MAX_VALUE
                player.pause()
                progressHandler.removeCallbacks(progressUpdater)
                notifyMessageChanged(item.id)
            } else {
                shouldStartWhenReady = true
                overlayUntilMs = System.currentTimeMillis() + TAP_OVERLAY_MS
                startIfReady()
            }
            return
        }

        val previousActive = activeMessageId
        release(clearNotifications = true)
        activeMessageId = item.id
        preparingMessageId = item.id
        downloadingMessageId = null
        errorMessageId = null
        completedMessageId = null
        overlayUntilMs = System.currentTimeMillis() + TAP_OVERLAY_MS
        shouldStartWhenReady = true
        prepared = false
        videoWidth = 0
        videoHeight = 0
        notifyMessageChanged(previousActive)
        notifyMessageChanged(item.id)
        attachTextureView(textureView)
        prepareSourceAndCreatePlayer(item, videoUrl, localVideoPath)
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

    fun cancel() {
        release()
        scope.cancel()
    }

    private fun prepareSourceAndCreatePlayer(item: ChatMessage, videoUrl: String, localVideoPath: String) {
        val localFile = localVideoPath.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.exists() && it.length() > 0L }
        if (localFile != null) {
            createPlayer(item, localFile.absolutePath)
            return
        }

        downloadingMessageId = item.id
        preparingMessageId = null
        notifyMessageChanged(item.id)
        scope.launch {
            val file = RoundVideoCache.fileFor(boundTextureView?.context ?: return@launch, videoUrl) { progress ->
                progressHandler.post {
                    if (activeMessageId == item.id) {
                        downloadProgress = progress
                        notifyMessageChanged(item.id)
                    }
                }
            }
            if (activeMessageId != item.id) return@launch
            downloadingMessageId = null
            downloadProgress = null
            if (file == null) {
                errorMessageId = item.id
                releasePlayerOnly()
                notifyMessageChanged(item.id)
                return@launch
            }
            preparingMessageId = item.id
            notifyMessageChanged(item.id)
            createPlayer(item, file.absolutePath)
        }
    }

    private fun createPlayer(item: ChatMessage, dataSource: String) {
        runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                setDataSource(dataSource)
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
                    if (activeMessageId == item.id) {
                        completedMessageId = item.id
                        shouldStartWhenReady = false
                        progressHandler.removeCallbacks(progressUpdater)
                        runCatching { it.seekTo(0) }
                        notifyMessageChanged(item.id)
                    }
                }
                setOnErrorListener { _, _, _ ->
                    errorMessageId = item.id
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
        runCatching { player.start() }.onFailure {
            errorMessageId = activeMessageId
            release()
            return
        }
        completedMessageId = null
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
        downloadingMessageId = null
        downloadProgress = null
        completedMessageId = null
        overlayUntilMs = 0L
        prepared = false
        shouldStartWhenReady = false
        videoWidth = 0
        videoHeight = 0
        if (!clearNotifications) {
            notifyMessageChanged(previousActive)
            notifyMessageChanged(previousPreparing)
        }
    }

    private fun releasePlayerOnly() {
        progressHandler.removeCallbacks(progressUpdater)
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        prepared = false
        shouldStartWhenReady = false
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
        const val TAP_OVERLAY_MS = 750L
    }
}

internal data class RoundVideoPlaybackState(
    val isActive: Boolean,
    val isPreparing: Boolean,
    val isDownloading: Boolean,
    val isPlaying: Boolean,
    val isCompleted: Boolean,
    val isError: Boolean,
    val showTransientOverlay: Boolean,
    val downloadProgress: Float?,
    val durationMs: Int,
    val progressMs: Int
)
