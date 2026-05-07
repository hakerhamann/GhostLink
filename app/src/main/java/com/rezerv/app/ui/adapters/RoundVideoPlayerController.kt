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
    private var autoplayMessageId: String? = null
    private var expandedMessageId: String? = null
    private var playbackMode: PlaybackMode = PlaybackMode.NONE
    private var playbackGeneration: Long = 0L
    private var overlayUntilMs: Long = 0L
    private var boundTextureView: TextureView? = null
    private var surface: Surface? = null
    private var prepared: Boolean = false
    private var shouldStartWhenReady: Boolean = false
    private var videoWidth: Int = 0
    private var videoHeight: Int = 0
    private var transformApplied: Boolean = false
    private var firstFrameReadyMessageId: String? = null

    private val progressUpdater = object : Runnable {
        override fun run() {
            val messageId = activeMessageId ?: return
            if (playbackMode != PlaybackMode.EXPANDED_PLAYING) return
            notifyMessageChanged(messageId)
            progressHandler.postDelayed(this, PLAYBACK_TICK_MS)
        }
    }

    fun playbackStateFor(item: ChatMessage): RoundVideoPlaybackState {
        val player = mediaPlayer
        val isActive = activeMessageId == item.id &&
            (player != null || downloadingMessageId == item.id || preparingMessageId == item.id)
        val isPreparedActive = isActive && prepared && player != null
        val durationFromPlayer = if (isPreparedActive) {
            runCatching { player!!.duration }.getOrDefault(0)
        } else {
            0
        }
        val durationMs = when {
            durationFromPlayer > 0 -> durationFromPlayer
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
            isAutoplay = autoplayMessageId == item.id,
            isExpanded = expandedMessageId == item.id,
            isError = errorMessageId == item.id,
            isFirstFrameReady = firstFrameReadyMessageId == item.id,
            showTransientOverlay = overlayUntilMs > System.currentTimeMillis() && activeMessageId == item.id,
            downloadProgress = if (downloadingMessageId == item.id) downloadProgress else null,
            durationMs = durationMs,
            progressMs = progressMs
        )
    }

    fun attachTexture(item: ChatMessage, textureView: TextureView) {
        if (item.id != activeMessageId) {
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
        val localSource = localPlayablePath(textureView.context, item)
        if (localSource == null && videoUrl.isNotBlank() && !videoUrl.startsWith("pending://")) {
            downloadOnly(item, textureView, videoUrl)
            return
        }
        if (activeMessageId == item.id && player != null) {
            attachTextureView(textureView)
            if (playbackMode == PlaybackMode.AUTOPLAY_MUTED && autoplayMessageId == item.id) {
                transitionToExpandedPlayback(item, textureView)
                return
            }
            if (preparingMessageId == item.id) {
                release()
                return
            }
            val isPlaying = runCatching { player.isPlaying }.getOrDefault(false)
            if (isPlaying) {
                shouldStartWhenReady = false
                overlayUntilMs = Long.MAX_VALUE
                runCatching { player.pause() }.onFailure {
                    failActive(item.id)
                    return
                }
                progressHandler.removeCallbacks(progressUpdater)
                notifyMessageChanged(item.id)
            } else {
                shouldStartWhenReady = true
                overlayUntilMs = System.currentTimeMillis() + TAP_OVERLAY_MS
                if (completedMessageId == item.id) {
                    runCatching { player.seekTo(0) }
                    completedMessageId = null
                }
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
        autoplayMessageId = null
        expandedMessageId = item.id
        playbackMode = PlaybackMode.PREPARING
        nextGeneration()
        shouldStartWhenReady = true
        prepared = false
        videoWidth = 0
        videoHeight = 0
        transformApplied = false
        firstFrameReadyMessageId = null
        notifyMessageChanged(previousActive)
        notifyMessageChanged(item.id)
        attachTextureView(textureView)
        prepareSourceAndCreatePlayer(item, videoUrl, localVideoPath)
    }

    fun autoplay(item: ChatMessage, textureView: TextureView) {
        startAutoplay(item, textureView)
    }

    fun startAutoplay(item: ChatMessage, textureView: TextureView) {
        if (item.type != MessageType.VIDEO || expandedMessageId != null || activeMessageId == item.id) return
        val source = localPlayablePath(textureView.context, item) ?: return
        val previousActive = activeMessageId
        release(clearNotifications = true)
        activeMessageId = item.id
        preparingMessageId = item.id
        downloadingMessageId = null
        errorMessageId = null
        completedMessageId = null
        autoplayMessageId = item.id
        expandedMessageId = null
        playbackMode = PlaybackMode.AUTOPLAY_MUTED
        nextGeneration()
        overlayUntilMs = 0L
        shouldStartWhenReady = true
        prepared = false
        videoWidth = 0
        videoHeight = 0
        transformApplied = false
        firstFrameReadyMessageId = null
        notifyMessageChanged(previousActive)
        notifyMessageChanged(item.id)
        attachTextureView(textureView)
        createPlayer(item, source, autoplay = true)
    }

    fun expand(item: ChatMessage, textureView: TextureView) {
        toggle(item, textureView)
    }

    fun isExpanded(): Boolean = expandedMessageId != null

    fun isAutoplaying(itemId: String): Boolean = autoplayMessageId == itemId && playbackMode == PlaybackMode.AUTOPLAY_MUTED

    fun stopAutoplay() {
        if (playbackMode == PlaybackMode.AUTOPLAY_MUTED) release()
    }

    fun detachTexture(textureView: TextureView) {
        if (boundTextureView === textureView) {
            clearTextureListener(textureView)
            releaseSurface()
            boundTextureView = null
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
            createPlayer(item, localFile.absolutePath, autoplay = false)
            return
        }

        playbackMode = PlaybackMode.DOWNLOADING
        val downloadGeneration = nextGeneration()
        downloadingMessageId = item.id
        preparingMessageId = null
        notifyMessageChanged(item.id)
        scope.launch {
            val file = RoundVideoCache.fileFor(boundTextureView?.context ?: return@launch, videoUrl) { progress ->
                progressHandler.post {
                    if (!isStale(downloadGeneration, item.id) && playbackMode == PlaybackMode.DOWNLOADING) {
                        downloadProgress = progress
                        notifyMessageChanged(item.id)
                    }
                }
            }
            if (isStale(downloadGeneration, item.id) || playbackMode != PlaybackMode.DOWNLOADING) return@launch
            downloadingMessageId = null
            downloadProgress = null
            if (file == null) {
                errorMessageId = item.id
                releasePlayerOnly()
                detachBoundTexture()
                notifyMessageChanged(item.id)
                return@launch
            }
            preparingMessageId = item.id
            playbackMode = PlaybackMode.PREPARING
            nextGeneration()
            notifyMessageChanged(item.id)
            createPlayer(item, file.absolutePath, autoplay = false)
        }
    }

    private fun downloadOnly(item: ChatMessage, textureView: TextureView, videoUrl: String) {
        val previousActive = activeMessageId
        release(clearNotifications = true)
        activeMessageId = item.id
        downloadingMessageId = item.id
        preparingMessageId = null
        expandedMessageId = null
        autoplayMessageId = null
        errorMessageId = null
        completedMessageId = null
        playbackMode = PlaybackMode.DOWNLOADING
        downloadProgress = 0f
        shouldStartWhenReady = false
        firstFrameReadyMessageId = null
        val downloadGeneration = nextGeneration()
        attachTextureView(textureView)
        notifyMessageChanged(previousActive)
        notifyMessageChanged(item.id)
        scope.launch {
            val file = RoundVideoCache.fileFor(textureView.context, videoUrl) { progress ->
                progressHandler.post {
                    if (!isStale(downloadGeneration, item.id) && playbackMode == PlaybackMode.DOWNLOADING) {
                        downloadProgress = progress
                        notifyMessageChanged(item.id)
                    }
                }
            }
            if (isStale(downloadGeneration, item.id) || playbackMode != PlaybackMode.DOWNLOADING) return@launch
            downloadingMessageId = null
            downloadProgress = null
            if (file == null) {
                errorMessageId = item.id
                releasePlayerOnly()
                detachBoundTexture()
                notifyMessageChanged(item.id)
                return@launch
            }
            activeMessageId = null
            playbackMode = PlaybackMode.NONE
            notifyMessageChanged(item.id)
            startAutoplay(item, textureView)
        }
    }

    private fun localPlayablePath(context: android.content.Context, item: ChatMessage): String? {
        val localVideoPath = item.localVideoPath?.trim().orEmpty()
        val localFile = localVideoPath.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.exists() && it.length() > 0L }
        if (localFile != null) return localFile.absolutePath
        val videoUrl = item.videoUrl?.trim().orEmpty()
        return RoundVideoCache.getCachedFileIfExists(context, videoUrl)?.absolutePath
    }

    private fun createPlayer(item: ChatMessage, dataSource: String, autoplay: Boolean, startAtZero: Boolean = false) {
        val generation = nextGeneration()
        playbackMode = if (autoplay) PlaybackMode.AUTOPLAY_MUTED else PlaybackMode.PREPARING
        runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                setDataSource(dataSource)
                isLooping = autoplay
                if (autoplay) setVolume(0f, 0f) else setVolume(1f, 1f)
                surface?.let { currentSurface ->
                    runCatching { setSurface(currentSurface) }.getOrThrow()
                }
                setOnPreparedListener { player ->
                    if (isStale(generation, item.id)) {
                        runCatching { player.release() }
                        return@setOnPreparedListener
                    }
                    prepared = true
                    preparingMessageId = null
                    playbackMode = if (autoplayMessageId == item.id) {
                        PlaybackMode.AUTOPLAY_MUTED
                    } else {
                        PlaybackMode.EXPANDED_PLAYING
                    }
                    this@RoundVideoPlayerController.videoWidth = player.videoWidth
                    this@RoundVideoPlayerController.videoHeight = player.videoHeight
                    transformApplied = false
                    firstFrameReadyMessageId = null
                    boundTextureView?.let { applyCenterCrop(it) }
                    if (startAtZero) {
                        runCatching { player.seekTo(0) }.onFailure {
                            failActive(item.id)
                            return@setOnPreparedListener
                        }
                    } else {
                        startIfReady(generation, item.id)
                    }
                    notifyMessageChanged(item.id)
                }
                setOnCompletionListener {
                    if (isStale(generation, item.id)) return@setOnCompletionListener
                    if (playbackMode != PlaybackMode.EXPANDED_PLAYING || expandedMessageId != item.id) {
                        return@setOnCompletionListener
                    }
                    collapseExpandedToAutoplay(item, generation)
                }
                setOnSeekCompleteListener {
                    if (isStale(generation, item.id)) return@setOnSeekCompleteListener
                    if (playbackMode == PlaybackMode.EXPANDED_PLAYING || playbackMode == PlaybackMode.AUTOPLAY_MUTED) {
                        startIfReady(generation, item.id)
                    }
                }
                setOnErrorListener { _, _, _ ->
                    if (isStale(generation, item.id)) return@setOnErrorListener true
                    errorMessageId = item.id
                    releasePlayerOnly()
                    detachBoundTexture()
                    notifyMessageChanged(item.id)
                    true
                }
                prepareAsync()
            }
        }.onSuccess { player ->
            mediaPlayer = player
        }.onFailure {
            failActive(item.id)
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
                if (boundTextureView === textureView) {
                    releaseSurface()
                    boundTextureView = null
                }
                return true
            }

            override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
                val messageId = activeMessageId ?: return
                if (prepared && transformApplied && firstFrameReadyMessageId != messageId) {
                    firstFrameReadyMessageId = messageId
                    notifyMessageChanged(messageId)
                }
            }
        }
        if (textureView.isAvailable) {
            attachSurface(textureView.surfaceTexture)
        }
    }

    private fun attachSurface(surfaceTexture: SurfaceTexture?) {
        if (surfaceTexture == null) return
        releaseSurface()
        val newSurface = runCatching { Surface(surfaceTexture) }.getOrElse {
            failActive(activeMessageId)
            return
        }
        surface = newSurface
        mediaPlayer?.let { player ->
            runCatching { player.setSurface(newSurface) }.onFailure {
                failActive(activeMessageId)
                return
            }
        }
        boundTextureView?.let { applyCenterCrop(it) }
        startIfReady(playbackGeneration, activeMessageId)
    }

    private fun startIfReady(expectedGeneration: Long = playbackGeneration, expectedMessageId: String? = activeMessageId) {
        if (expectedGeneration != playbackGeneration || expectedMessageId != activeMessageId) return
        val player = mediaPlayer ?: return
        if (!prepared || surface == null || !shouldStartWhenReady) return
        runCatching { player.start() }.onFailure {
            failActive(activeMessageId)
            return
        }
        completedMessageId = null
        notifyMessageChanged(activeMessageId)
        progressHandler.removeCallbacks(progressUpdater)
        if (playbackMode == PlaybackMode.EXPANDED_PLAYING) {
            progressHandler.postDelayed(progressUpdater, PLAYBACK_TICK_MS)
        }
    }

    private fun release(clearNotifications: Boolean) {
        nextGeneration()
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
        autoplayMessageId = null
        expandedMessageId = null
        playbackMode = PlaybackMode.NONE
        overlayUntilMs = 0L
        prepared = false
        shouldStartWhenReady = false
        videoWidth = 0
        videoHeight = 0
        transformApplied = false
        firstFrameReadyMessageId = null
        if (!clearNotifications) {
            notifyMessageChanged(previousActive)
            notifyMessageChanged(previousPreparing)
        }
    }

    private fun releasePlayerOnly() {
        nextGeneration()
        progressHandler.removeCallbacks(progressUpdater)
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        prepared = false
        shouldStartWhenReady = false
        playbackMode = PlaybackMode.NONE
        transformApplied = false
        firstFrameReadyMessageId = null
    }

    private fun transitionToExpandedPlayback(item: ChatMessage, textureView: TextureView) {
        val source = localPlayablePath(textureView.context, item) ?: return
        releasePlayerOnly()
        activeMessageId = item.id
        preparingMessageId = item.id
        autoplayMessageId = null
        expandedMessageId = item.id
        playbackMode = PlaybackMode.PREPARING
        completedMessageId = null
        shouldStartWhenReady = true
        prepared = false
        transformApplied = false
        firstFrameReadyMessageId = null
        overlayUntilMs = System.currentTimeMillis() + TAP_OVERLAY_MS
        attachTextureView(textureView)
        notifyMessageChanged(item.id)
        createPlayer(item, source, autoplay = false, startAtZero = true)
    }

    private fun collapseExpandedToAutoplay(item: ChatMessage, generation: Long) {
        if (isStale(generation, item.id)) return
        val textureView = boundTextureView
        val source = textureView?.context?.let { localPlayablePath(it, item) }
        expandedMessageId = null
        autoplayMessageId = item.id
        playbackMode = PlaybackMode.AUTOPLAY_MUTED
        shouldStartWhenReady = false
        completedMessageId = null
        progressHandler.removeCallbacks(progressUpdater)
        notifyMessageChanged(item.id)
        releasePlayerOnly()
        if (textureView != null && source != null) {
            activeMessageId = item.id
            preparingMessageId = item.id
            autoplayMessageId = item.id
            expandedMessageId = null
            playbackMode = PlaybackMode.AUTOPLAY_MUTED
            shouldStartWhenReady = true
            attachTextureView(textureView)
            createPlayer(item, source, autoplay = true, startAtZero = true)
        } else {
            activeMessageId = null
            autoplayMessageId = null
            playbackMode = PlaybackMode.NONE
        }
    }

    private fun nextGeneration(): Long {
        playbackGeneration += 1L
        return playbackGeneration
    }

    private fun isStale(generation: Long, messageId: String): Boolean {
        return generation != playbackGeneration || activeMessageId != messageId
    }

    private fun releaseSurface() {
        runCatching { surface?.release() }
        surface = null
    }

    private fun detachBoundTexture() {
        boundTextureView?.let { clearTextureListener(it) }
        boundTextureView = null
        releaseSurface()
    }

    private fun failActive(messageId: String?) {
        errorMessageId = messageId
        releasePlayerOnly()
        detachBoundTexture()
        notifyMessageChanged(messageId)
    }

    private fun clearTextureListener(textureView: TextureView) {
        textureView.surfaceTextureListener = null
        textureView.setTransform(Matrix())
        textureView.alpha = 0f
    }

    private fun applyCenterCrop(textureView: TextureView) {
        val viewWidth = textureView.width.toFloat()
        val viewHeight = textureView.height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f || videoWidth <= 0 || videoHeight <= 0) {
            transformApplied = false
            return
        }
        val scale = max(viewWidth / videoWidth.toFloat(), viewHeight / videoHeight.toFloat())
        val scaledWidth = videoWidth * scale
        val scaledHeight = videoHeight * scale
        val matrix = Matrix().apply {
            setScale(scaledWidth / viewWidth, scaledHeight / viewHeight, viewWidth / 2f, viewHeight / 2f)
        }
        textureView.setTransform(matrix)
        transformApplied = true
    }

    private companion object {
        const val PLAYBACK_TICK_MS = 250L
        const val TAP_OVERLAY_MS = 750L
    }

    private enum class PlaybackMode {
        NONE,
        AUTOPLAY_MUTED,
        EXPANDED_PLAYING,
        DOWNLOADING,
        PREPARING
    }
}

internal data class RoundVideoPlaybackState(
    val isActive: Boolean,
    val isPreparing: Boolean,
    val isDownloading: Boolean,
    val isPlaying: Boolean,
    val isCompleted: Boolean,
    val isAutoplay: Boolean,
    val isExpanded: Boolean,
    val isError: Boolean,
    val isFirstFrameReady: Boolean,
    val showTransientOverlay: Boolean,
    val downloadProgress: Float?,
    val durationMs: Int,
    val progressMs: Int
)
