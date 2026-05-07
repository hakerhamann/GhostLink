package com.rezerv.app.ui.adapters

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.view.View
import android.view.TextureView
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.rezerv.app.data.model.ChatMessage
import com.rezerv.app.data.model.MessageSendState
import com.rezerv.app.data.model.MessageType
import com.rezerv.app.databinding.ItemMessageIncomingBinding
import com.rezerv.app.databinding.ItemMessageOutgoingBinding
import com.rezerv.app.databinding.ItemMessageSystemBinding
import com.rezerv.app.util.AvatarLoader
import com.rezerv.app.util.Formatters

class MessageAdapter(
    private val currentUserId: String,
    initialRecipientsCount: Int,
    private val isGroupChat: Boolean,
    private val onIncomingAvatarTap: (ChatMessage) -> Unit,
    private val onSenderNameTap: (ChatMessage) -> Unit,
    private val onIncomingMessageTap: (ChatMessage, Float, Float) -> Unit,
    private val onOwnMessageTap: (ChatMessage, Float, Float) -> Unit,
    private val onReplyPreviewTap: (ChatMessage) -> Unit,
    private val onMessageImageTap: (ChatMessage, Int, String) -> Unit,
    private val onCancelVideoUpload: (String) -> Unit
) : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(DiffCallback) {

    private var recipientsCount: Int = initialRecipientsCount.coerceAtLeast(1)
    private val roundVideoPlayer = RoundVideoPlayerController(::notifyMessageChanged)
    private var mediaPlayer: MediaPlayer? = null
    private var activeMessageId: String? = null
    private var preparingMessageId: String? = null
    private var highlightedMessageId: String? = null
    private var clearHighlightRunnable: Runnable? = null
    private val pendingEntranceAnimationIds = linkedSetOf<String>()
    private val entranceInterpolator = FastOutSlowInInterpolator()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recyclerView: RecyclerView? = null
    private var autoplayRunnable: Runnable? = null
    private val roundVideoScrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                scheduleRoundVideoAutoplay()
            } else {
                autoplayRunnable?.let(mainHandler::removeCallbacks)
                autoplayRunnable = null
                if (!roundVideoPlayer.isExpanded()) {
                    roundVideoPlayer.stopAutoplay()
                }
            }
        }
    }
    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressUpdater = object : Runnable {
        override fun run() {
            val messageId = activeMessageId ?: return
            notifyMessageChanged(messageId)
            progressHandler.postDelayed(this, PLAYBACK_TICK_MS)
        }
    }

    override fun getItemViewType(position: Int): Int {
        val item = getItem(position)
        return when {
            item.type == MessageType.SYSTEM || item.type == MessageType.SYSTEM_AVATAR -> TYPE_SYSTEM
            item.senderId == currentUserId -> TYPE_OUTGOING
            else -> TYPE_INCOMING
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_OUTGOING) {
            OutgoingViewHolder(ItemMessageOutgoingBinding.inflate(inflater, parent, false))
        } else if (viewType == TYPE_SYSTEM) {
            SystemViewHolder(ItemMessageSystemBinding.inflate(inflater, parent, false))
        } else {
            IncomingViewHolder(ItemMessageIncomingBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        val playbackState = playbackStateFor(item)
        val videoPlaybackState = roundVideoPlayer.playbackStateFor(item)
        when (holder) {
            is IncomingViewHolder -> holder.bind(
                item = item,
                playback = playbackState,
                videoPlayback = videoPlaybackState,
                isGroupChat = isGroupChat,
                onPlayVoice = { toggleVoicePlayback(item) },
                onToggleVideo = { textureView -> toggleRoundVideoPlayback(item, textureView) },
                onAutoPlayVideo = { textureView -> roundVideoPlayer.autoplay(item, textureView) },
                onAttachVideo = { textureView -> roundVideoPlayer.attachTexture(item, textureView) },
                onDetachVideo = { textureView -> roundVideoPlayer.detachTexture(textureView) },
                onCancelVideoUpload = onCancelVideoUpload,
                onCachedVideoReady = { id -> notifyMessageChanged(id) },
                onIncomingAvatarTap = onIncomingAvatarTap,
                onSenderNameTap = onSenderNameTap,
                onIncomingMessageTap = onIncomingMessageTap,
                onReplyPreviewTap = onReplyPreviewTap,
                onMessageImageTap = onMessageImageTap,
                isHighlighted = highlightedMessageId == item.id
            )

            is OutgoingViewHolder -> holder.bind(
                item = item,
                currentUserId = currentUserId,
                recipientsCount = recipientsCount,
                isGroupChat = isGroupChat,
                isHighlighted = highlightedMessageId == item.id,
                playback = playbackState,
                videoPlayback = videoPlaybackState,
                onPlayVoice = { toggleVoicePlayback(item) },
                onToggleVideo = { textureView -> toggleRoundVideoPlayback(item, textureView) },
                onAutoPlayVideo = { textureView -> roundVideoPlayer.autoplay(item, textureView) },
                onAttachVideo = { textureView -> roundVideoPlayer.attachTexture(item, textureView) },
                onDetachVideo = { textureView -> roundVideoPlayer.detachTexture(textureView) },
                onCancelVideoUpload = onCancelVideoUpload,
                onCachedVideoReady = { id -> notifyMessageChanged(id) },
                onOwnMessageTap = onOwnMessageTap,
                onReplyPreviewTap = onReplyPreviewTap,
                onMessageImageTap = onMessageImageTap
            )

            is SystemViewHolder -> holder.bind(item)
        }
        applyEntranceAnimationIfNeeded(holder, item.id)
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
        recyclerView.addOnScrollListener(roundVideoScrollListener)
        scheduleRoundVideoAutoplay()
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        autoplayRunnable?.let(mainHandler::removeCallbacks)
        autoplayRunnable = null
        recyclerView.removeOnScrollListener(roundVideoScrollListener)
        if (this.recyclerView === recyclerView) this.recyclerView = null
        super.onDetachedFromRecyclerView(recyclerView)
    }

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        super.onViewAttachedToWindow(holder)
        scheduleRoundVideoAutoplay()
    }

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        when (holder) {
            is IncomingViewHolder -> holder.releaseRoundVideo(roundVideoPlayer)
            is OutgoingViewHolder -> holder.releaseRoundVideo(roundVideoPlayer)
        }
        super.onViewDetachedFromWindow(holder)
        scheduleRoundVideoAutoplay()
    }

    override fun onCurrentListChanged(
        previousList: MutableList<ChatMessage>,
        currentList: MutableList<ChatMessage>
    ) {
        super.onCurrentListChanged(previousList, currentList)
        pendingEntranceAnimationIds.retainAll(currentList.mapTo(hashSetOf()) { it.id })
        if (previousList.isEmpty()) return
        val previousIds = previousList.asSequence().map { it.id }.toHashSet()
        currentList.forEach { message ->
            if (!previousIds.contains(message.id)) {
                pendingEntranceAnimationIds += message.id
            }
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        when (holder) {
            is IncomingViewHolder -> holder.releaseRoundVideo(roundVideoPlayer)
            is OutgoingViewHolder -> holder.releaseRoundVideo(roundVideoPlayer)
        }
        super.onViewRecycled(holder)
    }

    fun releasePlayback() {
        releaseVoicePlayback()
        roundVideoPlayer.release()
    }

    fun releaseVoicePlayback() {
        progressHandler.removeCallbacksAndMessages(null)
        clearHighlightRunnable = null
        highlightedMessageId = null
        mediaPlayer?.release()
        mediaPlayer = null
        activeMessageId = null
        preparingMessageId = null
    }

    fun highlightMessage(messageId: String, durationMs: Long = 2400L) {
        if (messageId.isBlank()) return

        val previous = highlightedMessageId
        highlightedMessageId = messageId
        notifyMessageChanged(previous)
        notifyMessageChanged(messageId)

        clearHighlightRunnable?.let { progressHandler.removeCallbacks(it) }
        val clearRunnable = Runnable {
            if (highlightedMessageId == messageId) {
                highlightedMessageId = null
                notifyMessageChanged(messageId)
            }
        }
        clearHighlightRunnable = clearRunnable
        progressHandler.postDelayed(clearRunnable, durationMs)
    }

    fun updateRecipientsCount(newCount: Int) {
        val sanitized = newCount.coerceAtLeast(1)
        if (recipientsCount == sanitized) return
        recipientsCount = sanitized
        notifyDataSetChanged()
    }

    private fun playbackStateFor(item: ChatMessage): VoicePlaybackState {
        val player = mediaPlayer
        val isActive = activeMessageId == item.id && player != null
        val isPreparing = preparingMessageId == item.id
        val durationMs = when {
            isActive && player != null && player.duration > 0 -> player.duration
            else -> (item.voiceDurationSec.coerceAtLeast(0) * 1000)
        }
        val progressMs = when {
            isActive && player != null -> player.currentPosition.coerceAtLeast(0)
            else -> 0
        }
        return VoicePlaybackState(
            isPlaying = isActive,
            isPreparing = isPreparing,
            durationMs = durationMs,
            progressMs = progressMs
        )
    }

    private fun toggleVoicePlayback(item: ChatMessage) {
        if (item.type != MessageType.VOICE) return
        val voiceUrl = item.voiceUrl?.trim().orEmpty()
        if (voiceUrl.isBlank()) return

        if (activeMessageId == item.id) {
            stopVoicePlayback()
            return
        }

        val previousActive = activeMessageId
        val previousPreparing = preparingMessageId
        roundVideoPlayer.release()
        stopVoicePlayback(clearStateOnly = true)

        preparingMessageId = item.id
        notifyMessageChanged(previousActive)
        notifyMessageChanged(previousPreparing)
        notifyMessageChanged(item.id)

        runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(voiceUrl)
                setOnPreparedListener { player ->
                    if (preparingMessageId != item.id) {
                        player.release()
                        return@setOnPreparedListener
                    }
                    preparingMessageId = null
                    activeMessageId = item.id
                    player.start()
                    notifyMessageChanged(item.id)
                    progressHandler.removeCallbacks(progressUpdater)
                    progressHandler.post(progressUpdater)
                }
                setOnCompletionListener {
                    stopVoicePlayback()
                }
                setOnErrorListener { _, _, _ ->
                    stopVoicePlayback()
                    true
                }
                prepareAsync()
            }
        }.onSuccess { player ->
            mediaPlayer = player
        }.onFailure {
            stopVoicePlayback()
        }
    }

    private fun toggleRoundVideoPlayback(item: ChatMessage, textureView: TextureView) {
        if (item.type != MessageType.VIDEO) return
        autoplayRunnable?.let(mainHandler::removeCallbacks)
        autoplayRunnable = null
        stopVoicePlayback()
        roundVideoPlayer.toggle(item, textureView)
    }

    private fun scheduleRoundVideoAutoplay() {
        autoplayRunnable?.let(mainHandler::removeCallbacks)
        if (roundVideoPlayer.isExpanded()) return
        val rv = recyclerView ?: return
        if (rv.scrollState != RecyclerView.SCROLL_STATE_IDLE) return
        val runnable = Runnable { startMostCenteredRoundVideoAutoplay() }
        autoplayRunnable = runnable
        mainHandler.postDelayed(runnable, ROUND_VIDEO_AUTOPLAY_DEBOUNCE_MS)
    }

    private fun startMostCenteredRoundVideoAutoplay() {
        autoplayRunnable = null
        val rv = recyclerView ?: return
        if (roundVideoPlayer.isExpanded() || rv.scrollState != RecyclerView.SCROLL_STATE_IDLE) return
        val centerY = rv.height / 2
        var bestItem: ChatMessage? = null
        var bestTexture: TextureView? = null
        var bestDistance = Int.MAX_VALUE
        for (index in 0 until itemCount) {
            val holder = rv.findViewHolderForAdapterPosition(index) ?: continue
            val candidate = when (holder) {
                is IncomingViewHolder -> holder.roundVideoCandidate()
                is OutgoingViewHolder -> holder.roundVideoCandidate()
                else -> null
            } ?: continue
            val item = candidate.first
            if (!canAutoplayRoundVideo(rv, item)) continue
            val viewCenter = (holder.itemView.top + holder.itemView.bottom) / 2
            val distance = kotlin.math.abs(viewCenter - centerY)
            if (distance < bestDistance) {
                bestDistance = distance
                bestItem = item
                bestTexture = candidate.second
            }
        }
        val item = bestItem ?: return
        val texture = bestTexture ?: return
        if (!roundVideoPlayer.isAutoplaying(item.id)) {
            roundVideoPlayer.startAutoplay(item, texture)
        }
    }

    private fun canAutoplayRoundVideo(recyclerView: RecyclerView, item: ChatMessage): Boolean {
        if (item.type != MessageType.VIDEO || item.sendState != MessageSendState.SENT) return false
        val localVideoPath = item.localVideoPath?.trim().orEmpty()
        if (localVideoPath.isNotBlank() && java.io.File(localVideoPath).let { it.exists() && it.length() > 0L }) {
            return true
        }
        val videoUrl = item.videoUrl?.trim().orEmpty()
        if (videoUrl.isBlank() || videoUrl.startsWith("pending://")) return false
        return RoundVideoCache.getCachedFileIfExists(recyclerView.context, videoUrl) != null
    }

    private fun stopVoicePlayback(clearStateOnly: Boolean = false) {
        val previousActive = activeMessageId
        val previousPreparing = preparingMessageId
        progressHandler.removeCallbacks(progressUpdater)
        mediaPlayer?.release()
        mediaPlayer = null
        activeMessageId = null
        preparingMessageId = null
        if (!clearStateOnly) {
            notifyMessageChanged(previousActive)
            notifyMessageChanged(previousPreparing)
        }
    }

    private fun notifyMessageChanged(messageId: String?) {
        if (messageId.isNullOrBlank()) return
        mainHandler.post {
            val index = currentList.indexOfFirst { it.id == messageId }
            if (index >= 0) notifyItemChanged(index)
        }
    }

    private fun applyEntranceAnimationIfNeeded(holder: RecyclerView.ViewHolder, messageId: String) {
        val view = holder.itemView
        if (!pendingEntranceAnimationIds.remove(messageId)) {
            view.animate().cancel()
            view.alpha = 1f
            view.translationY = 0f
            return
        }

        val startShift = 14f * view.resources.displayMetrics.density
        view.animate().cancel()
        view.alpha = 0f
        view.translationY = startShift
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(190L)
            .setInterpolator(entranceInterpolator)
            .start()
    }

    private class IncomingViewHolder(
        private val binding: ItemMessageIncomingBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        private val bubblePaddingStart = binding.messageBubble.paddingStart
        private val bubblePaddingTop = binding.messageBubble.paddingTop
        private val bubblePaddingEnd = binding.messageBubble.paddingEnd
        private val bubblePaddingBottom = binding.messageBubble.paddingBottom
        private var boundItem: ChatMessage? = null

        fun bind(
            item: ChatMessage,
            playback: VoicePlaybackState,
            videoPlayback: RoundVideoPlaybackState,
            isGroupChat: Boolean,
            onPlayVoice: () -> Unit,
            onToggleVideo: (TextureView) -> Unit,
            onAutoPlayVideo: (TextureView) -> Unit,
            onAttachVideo: (TextureView) -> Unit,
            onDetachVideo: (TextureView) -> Unit,
            onCancelVideoUpload: (String) -> Unit,
            onCachedVideoReady: (String) -> Unit,
            onIncomingAvatarTap: (ChatMessage) -> Unit,
            onSenderNameTap: (ChatMessage) -> Unit,
            onIncomingMessageTap: (ChatMessage, Float, Float) -> Unit,
            onReplyPreviewTap: (ChatMessage) -> Unit,
            onMessageImageTap: (ChatMessage, Int, String) -> Unit,
            isHighlighted: Boolean
        ) {
            boundItem = item
            binding.tvSender.text = item.senderName
            resetBubbleStyle(isVideo = item.type == MessageType.VIDEO, highlighted = isHighlighted)

            binding.avatarContainer.isVisible = isGroupChat
            if (isGroupChat) {
                AvatarLoader.bind(
                    imageView = binding.ivAvatar,
                    fallbackView = binding.tvAvatarFallback,
                    displayName = item.senderName,
                    avatarUrl = item.senderAvatarUrl
                )
                binding.avatarContainer.setOnClickListener { onIncomingAvatarTap(item) }
                binding.ivAvatar.setOnClickListener { onIncomingAvatarTap(item) }
                binding.tvAvatarFallback.setOnClickListener { onIncomingAvatarTap(item) }
            } else {
                binding.avatarContainer.setOnClickListener(null)
                binding.ivAvatar.setOnClickListener(null)
                binding.tvAvatarFallback.setOnClickListener(null)
            }
            binding.tvSender.setOnClickListener { onSenderNameTap(item) }

            MessageReplyPreviewBinder.bind(
                container = binding.replyContainer,
                senderView = binding.tvReplySender,
                textView = binding.tvReplyText,
                imageView = binding.ivReplyImage,
                item = item,
                fallbackSenderName = item.senderName,
                onReplyPreviewTap = onReplyPreviewTap
            )

            when (item.type) {
                MessageType.VOICE -> {
                    binding.tvMessage.isVisible = false
                    binding.ivImageMessage.isVisible = false
                    binding.ivImageMessage.tag = null
                    binding.ivImageMessage.setImageDrawable(null)
                    binding.ivImageMessage.setOnClickListener(null)
                    binding.photoAlbumGrid.isVisible = false
                    binding.photoAlbumGrid.removeAllViews()
                    clearRoundVideo(onDetachVideo)
                    MessageVoiceBinder.bind(
                        container = binding.voiceContainer,
                        playButton = binding.btnPlayVoice,
                        durationView = binding.tvVoiceDuration,
                        progressView = binding.progressVoice,
                        item = item,
                        playback = playback,
                        onPlayVoice = onPlayVoice
                    )
                }

                MessageType.IMAGE -> {
                    binding.voiceContainer.isVisible = false
                    binding.voiceContainer.setOnClickListener(null)
                    clearRoundVideo(onDetachVideo)
                    MessagePhotoBinder.bind(
                        imageView = binding.ivImageMessage,
                        albumGrid = binding.photoAlbumGrid,
                        captionView = binding.tvMessage,
                        item = item,
                        onMessageImageTap = if (item.sendState == MessageSendState.SENT) {
                            { index, photoUrl -> onMessageImageTap(item, index, photoUrl) }
                        } else {
                            null
                        }
                    )
                }

                MessageType.VIDEO -> {
                    binding.tvMessage.isVisible = false
                    binding.ivImageMessage.isVisible = false
                    binding.ivImageMessage.tag = null
                    binding.ivImageMessage.setImageDrawable(null)
                    binding.ivImageMessage.setOnClickListener(null)
                    binding.photoAlbumGrid.isVisible = false
                    binding.photoAlbumGrid.removeAllViews()
                    binding.voiceContainer.isVisible = false
                    binding.voiceContainer.setOnClickListener(null)
                    RoundVideoMessageBinder.bind(
                        container = binding.videoContainer,
                        textureView = binding.videoTexture,
                        thumbnailView = binding.ivVideoThumbnail,
                        placeholderView = binding.videoPlaceholder,
                        progressView = binding.videoProgress,
                        uploadProgressView = binding.videoUploadProgress,
                        playButton = binding.tvVideoPlay,
                        durationView = binding.tvVideoDuration,
                        item = item,
                        playback = videoPlayback,
                        onToggleVideo = onToggleVideo,
                        onAutoPlayVideo = onAutoPlayVideo,
                        onCancelUpload = onCancelVideoUpload,
                        onAttachTexture = onAttachVideo,
                        onDetachTexture = onDetachVideo,
                        onCachedVideoReady = onCachedVideoReady
                    )
                }

                MessageType.TEXT -> {
                    binding.tvMessage.isVisible = true
                    binding.ivImageMessage.isVisible = false
                    binding.ivImageMessage.tag = null
                    binding.ivImageMessage.setImageDrawable(null)
                    binding.photoAlbumGrid.isVisible = false
                    binding.photoAlbumGrid.removeAllViews()
                    clearRoundVideo(onDetachVideo)
                    binding.voiceContainer.isVisible = false
                    binding.voiceContainer.setOnClickListener(null)
                    binding.ivImageMessage.setOnClickListener(null)
                    binding.tvMessage.text = item.text
                }

                MessageType.SYSTEM,
                MessageType.SYSTEM_AVATAR -> Unit
            }

            val time = Formatters.formatTime(item.timestamp)
            binding.tvTime.text = if (item.edited) "$time (ред)" else time
            var tapX = 0f
            var tapY = 0f
            binding.root.setOnTouchListener { _, event ->
                if (event.action == android.view.MotionEvent.ACTION_DOWN || event.action == android.view.MotionEvent.ACTION_UP) {
                    tapX = event.rawX
                    tapY = event.rawY
                }
                false
            }
            binding.root.setOnClickListener {
                if (tapX == 0f && tapY == 0f) {
                    val location = IntArray(2)
                    binding.root.getLocationOnScreen(location)
                    tapX = location[0] + binding.root.width / 2f
                    tapY = location[1] + binding.root.height / 2f
                }
                onIncomingMessageTap(item, tapX, tapY)
            }
        }

        fun releaseRoundVideo(controller: RoundVideoPlayerController) {
            controller.detachTexture(binding.videoTexture)
        }

        fun roundVideoCandidate(): Pair<ChatMessage, TextureView>? {
            val item = boundItem ?: return null
            if (item.type != MessageType.VIDEO || !binding.videoContainer.isVisible) return null
            return item to binding.videoTexture
        }

        private fun clearRoundVideo(onDetachVideo: (TextureView) -> Unit) {
            RoundVideoMessageBinder.clear(
                container = binding.videoContainer,
                textureView = binding.videoTexture,
                thumbnailView = binding.ivVideoThumbnail,
                placeholderView = binding.videoPlaceholder,
                progressView = binding.videoProgress,
                uploadProgressView = binding.videoUploadProgress,
                playButton = binding.tvVideoPlay,
                durationView = binding.tvVideoDuration,
                onDetachTexture = onDetachVideo
            )
        }

        private fun resetBubbleStyle(isVideo: Boolean, highlighted: Boolean) {
            if (isVideo) {
                binding.messageBubble.setBackgroundColor(Color.TRANSPARENT)
                binding.messageBubble.setPadding(0, 0, 0, 0)
                return
            }
            binding.messageBubble.setBackgroundResource(
                if (highlighted) {
                    com.rezerv.app.R.drawable.bg_message_incoming_highlight
                } else {
                    com.rezerv.app.R.drawable.bg_message_incoming
                }
            )
            binding.messageBubble.setPaddingRelative(
                bubblePaddingStart,
                bubblePaddingTop,
                bubblePaddingEnd,
                bubblePaddingBottom
            )
        }
    }

    private class OutgoingViewHolder(
        private val binding: ItemMessageOutgoingBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        private val bubblePaddingStart = binding.messageBubble.paddingStart
        private val bubblePaddingTop = binding.messageBubble.paddingTop
        private val bubblePaddingEnd = binding.messageBubble.paddingEnd
        private val bubblePaddingBottom = binding.messageBubble.paddingBottom
        private var boundItem: ChatMessage? = null

        fun bind(
            item: ChatMessage,
            currentUserId: String,
            recipientsCount: Int,
            isGroupChat: Boolean,
            isHighlighted: Boolean,
            playback: VoicePlaybackState,
            videoPlayback: RoundVideoPlaybackState,
            onPlayVoice: () -> Unit,
            onToggleVideo: (TextureView) -> Unit,
            onAutoPlayVideo: (TextureView) -> Unit,
            onAttachVideo: (TextureView) -> Unit,
            onDetachVideo: (TextureView) -> Unit,
            onCancelVideoUpload: (String) -> Unit,
            onCachedVideoReady: (String) -> Unit,
            onOwnMessageTap: (ChatMessage, Float, Float) -> Unit,
            onReplyPreviewTap: (ChatMessage) -> Unit,
            onMessageImageTap: (ChatMessage, Int, String) -> Unit
        ) {
            boundItem = item
            resetBubbleStyle(isVideo = item.type == MessageType.VIDEO, highlighted = isHighlighted)
            MessageReplyPreviewBinder.bind(
                container = binding.replyContainer,
                senderView = binding.tvReplySender,
                textView = binding.tvReplyText,
                imageView = binding.ivReplyImage,
                item = item,
                fallbackSenderName = "Reply",
                onReplyPreviewTap = onReplyPreviewTap
            )

            when (item.type) {
                MessageType.VOICE -> {
                    binding.tvMessage.isVisible = false
                    binding.ivImageMessage.isVisible = false
                    binding.ivImageMessage.tag = null
                    binding.ivImageMessage.setImageDrawable(null)
                    binding.ivImageMessage.setOnClickListener(null)
                    binding.photoAlbumGrid.isVisible = false
                    binding.photoAlbumGrid.removeAllViews()
                    clearRoundVideo(onDetachVideo)
                    MessageVoiceBinder.bind(
                        container = binding.voiceContainer,
                        playButton = binding.btnPlayVoice,
                        durationView = binding.tvVoiceDuration,
                        progressView = binding.progressVoice,
                        item = item,
                        playback = playback,
                        canPlay = true,
                        onPlayVoice = onPlayVoice
                    )
                }

                MessageType.IMAGE -> {
                    binding.voiceContainer.isVisible = false
                    binding.voiceContainer.setOnClickListener(null)
                    clearRoundVideo(onDetachVideo)
                    MessagePhotoBinder.bind(
                        imageView = binding.ivImageMessage,
                        albumGrid = binding.photoAlbumGrid,
                        captionView = binding.tvMessage,
                        item = item,
                        onMessageImageTap = if (item.sendState == MessageSendState.SENT) {
                            { index, photoUrl -> onMessageImageTap(item, index, photoUrl) }
                        } else {
                            null
                        }
                    )
                }

                MessageType.VIDEO -> {
                    binding.tvMessage.isVisible = false
                    binding.ivImageMessage.isVisible = false
                    binding.ivImageMessage.tag = null
                    binding.ivImageMessage.setImageDrawable(null)
                    binding.ivImageMessage.setOnClickListener(null)
                    binding.photoAlbumGrid.isVisible = false
                    binding.photoAlbumGrid.removeAllViews()
                    binding.voiceContainer.isVisible = false
                    binding.voiceContainer.setOnClickListener(null)
                    RoundVideoMessageBinder.bind(
                        container = binding.videoContainer,
                        textureView = binding.videoTexture,
                        thumbnailView = binding.ivVideoThumbnail,
                        placeholderView = binding.videoPlaceholder,
                        progressView = binding.videoProgress,
                        uploadProgressView = binding.videoUploadProgress,
                        playButton = binding.tvVideoPlay,
                        durationView = binding.tvVideoDuration,
                        item = item,
                        playback = videoPlayback,
                        onToggleVideo = onToggleVideo,
                        onAutoPlayVideo = onAutoPlayVideo,
                        onCancelUpload = onCancelVideoUpload,
                        onAttachTexture = onAttachVideo,
                        onDetachTexture = onDetachVideo,
                        onCachedVideoReady = onCachedVideoReady
                    )
                }

                MessageType.TEXT -> {
                    binding.tvMessage.isVisible = true
                    binding.ivImageMessage.isVisible = false
                    binding.ivImageMessage.tag = null
                    binding.ivImageMessage.setImageDrawable(null)
                    binding.photoAlbumGrid.isVisible = false
                    binding.photoAlbumGrid.removeAllViews()
                    clearRoundVideo(onDetachVideo)
                    binding.voiceContainer.isVisible = false
                    binding.voiceContainer.setOnClickListener(null)
                    binding.ivImageMessage.setOnClickListener(null)
                    binding.tvMessage.text = item.text
                }

                MessageType.SYSTEM,
                MessageType.SYSTEM_AVATAR -> Unit
            }

            val time = Formatters.formatTime(item.timestamp)
            binding.tvTime.text = if (item.edited) "$time (ред)" else time
            val status = MessageStatusFormatter.resolve(item, currentUserId, recipientsCount, isGroupChat)
            binding.tvStatus.text = status.text
            binding.tvStatus.setTextColor(status.color)
            var tapX = 0f
            var tapY = 0f
            binding.root.setOnTouchListener { _, event ->
                if (event.action == android.view.MotionEvent.ACTION_DOWN || event.action == android.view.MotionEvent.ACTION_UP) {
                    tapX = event.rawX
                    tapY = event.rawY
                }
                false
            }
            binding.root.setOnClickListener {
                if (tapX == 0f && tapY == 0f) {
                    val location = IntArray(2)
                    binding.root.getLocationOnScreen(location)
                    tapX = location[0] + binding.root.width / 2f
                    tapY = location[1] + binding.root.height / 2f
                }
                onOwnMessageTap(item, tapX, tapY)
            }
        }

        fun releaseRoundVideo(controller: RoundVideoPlayerController) {
            controller.detachTexture(binding.videoTexture)
        }

        fun roundVideoCandidate(): Pair<ChatMessage, TextureView>? {
            val item = boundItem ?: return null
            if (item.type != MessageType.VIDEO || !binding.videoContainer.isVisible) return null
            return item to binding.videoTexture
        }

        private fun clearRoundVideo(onDetachVideo: (TextureView) -> Unit) {
            RoundVideoMessageBinder.clear(
                container = binding.videoContainer,
                textureView = binding.videoTexture,
                thumbnailView = binding.ivVideoThumbnail,
                placeholderView = binding.videoPlaceholder,
                progressView = binding.videoProgress,
                uploadProgressView = binding.videoUploadProgress,
                playButton = binding.tvVideoPlay,
                durationView = binding.tvVideoDuration,
                onDetachTexture = onDetachVideo
            )
        }

        private fun resetBubbleStyle(isVideo: Boolean, highlighted: Boolean) {
            if (isVideo) {
                binding.messageBubble.setBackgroundColor(Color.TRANSPARENT)
                binding.messageBubble.setPadding(0, 0, 0, 0)
                return
            }
            binding.messageBubble.setBackgroundResource(
                if (highlighted) {
                    com.rezerv.app.R.drawable.bg_message_outgoing_highlight
                } else {
                    com.rezerv.app.R.drawable.bg_message_outgoing
                }
            )
            binding.messageBubble.setPaddingRelative(
                bubblePaddingStart,
                bubblePaddingTop,
                bubblePaddingEnd,
                bubblePaddingBottom
            )
        }

    }

    private class SystemViewHolder(
        private val binding: ItemMessageSystemBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ChatMessage) {
            binding.tvMessage.text = item.text
            binding.avatarContainer.isVisible = item.type == MessageType.SYSTEM_AVATAR && !item.imageUrl.isNullOrBlank()
            if (binding.avatarContainer.isVisible) {
                AvatarLoader.bind(
                    imageView = binding.ivAvatar,
                    fallbackView = binding.tvAvatarFallback,
                    displayName = item.text,
                    avatarUrl = item.imageUrl
                )
            } else {
                binding.ivAvatar.setImageDrawable(null)
                binding.tvAvatarFallback.text = ""
            }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem == newItem
        }
    }

    private companion object {
        const val TYPE_INCOMING = 1
        const val TYPE_OUTGOING = 2
        const val TYPE_SYSTEM = 3
        const val PLAYBACK_TICK_MS = 250L
        const val ROUND_VIDEO_AUTOPLAY_DEBOUNCE_MS = 250L
    }
}

