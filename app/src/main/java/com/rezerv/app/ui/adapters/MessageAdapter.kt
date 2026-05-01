package com.rezerv.app.ui.adapters

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
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
import com.rezerv.app.util.AvatarLoader
import com.rezerv.app.util.Formatters
import com.rezerv.app.util.ImageThumbnailLoader
import java.util.Locale

class MessageAdapter(
    private val currentUserId: String,
    initialRecipientsCount: Int,
    private val isGroupChat: Boolean,
    private val onIncomingAvatarTap: (ChatMessage) -> Unit,
    private val onSenderNameTap: (ChatMessage) -> Unit,
    private val onIncomingMessageTap: (ChatMessage) -> Unit,
    private val onOwnMessageTap: (ChatMessage) -> Unit,
    private val onReplyPreviewTap: (ChatMessage) -> Unit,
    private val onMessageImageTap: (ChatMessage, Int, String) -> Unit,
    private val onMessageVideoTap: (ChatMessage) -> Unit
) : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(DiffCallback) {

    private var recipientsCount: Int = initialRecipientsCount.coerceAtLeast(1)
    private var mediaPlayer: MediaPlayer? = null
    private var activeMessageId: String? = null
    private var preparingMessageId: String? = null
    private var highlightedMessageId: String? = null
    private var clearHighlightRunnable: Runnable? = null
    private val pendingEntranceAnimationIds = linkedSetOf<String>()
    private val entranceInterpolator = FastOutSlowInInterpolator()
    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressUpdater = object : Runnable {
        override fun run() {
            val messageId = activeMessageId ?: return
            notifyMessageChanged(messageId)
            progressHandler.postDelayed(this, PLAYBACK_TICK_MS)
        }
    }

    override fun getItemViewType(position: Int): Int {
        return if (getItem(position).senderId == currentUserId) TYPE_OUTGOING else TYPE_INCOMING
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_OUTGOING) {
            OutgoingViewHolder(ItemMessageOutgoingBinding.inflate(inflater, parent, false))
        } else {
            IncomingViewHolder(ItemMessageIncomingBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        val playbackState = playbackStateFor(item)
        when (holder) {
            is IncomingViewHolder -> holder.bind(
                item = item,
                playback = playbackState,
                isGroupChat = isGroupChat,
                onPlayVoice = { toggleVoicePlayback(item) },
                onIncomingAvatarTap = onIncomingAvatarTap,
                onSenderNameTap = onSenderNameTap,
                onIncomingMessageTap = onIncomingMessageTap,
                onReplyPreviewTap = onReplyPreviewTap,
                onMessageImageTap = onMessageImageTap,
                onMessageVideoTap = onMessageVideoTap,
                isHighlighted = highlightedMessageId == item.id
            )

            is OutgoingViewHolder -> holder.bind(
                item = item,
                currentUserId = currentUserId,
                recipientsCount = recipientsCount,
                isGroupChat = isGroupChat,
                isHighlighted = highlightedMessageId == item.id,
                playback = playbackState,
                onPlayVoice = { toggleVoicePlayback(item) },
                onOwnMessageTap = onOwnMessageTap,
                onReplyPreviewTap = onReplyPreviewTap,
                onMessageImageTap = onMessageImageTap,
                onMessageVideoTap = onMessageVideoTap
            )
        }
        applyEntranceAnimationIfNeeded(holder, item.id)
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
        val index = currentList.indexOfFirst { it.id == messageId }
        if (index >= 0) notifyItemChanged(index)
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

    class IncomingViewHolder(
        private val binding: ItemMessageIncomingBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(
            item: ChatMessage,
            playback: VoicePlaybackState,
            isGroupChat: Boolean,
            onPlayVoice: () -> Unit,
            onIncomingAvatarTap: (ChatMessage) -> Unit,
            onSenderNameTap: (ChatMessage) -> Unit,
            onIncomingMessageTap: (ChatMessage) -> Unit,
            onReplyPreviewTap: (ChatMessage) -> Unit,
            onMessageImageTap: (ChatMessage, Int, String) -> Unit,
            onMessageVideoTap: (ChatMessage) -> Unit,
            isHighlighted: Boolean
        ) {
            binding.tvSender.text = item.senderName
            binding.messageBubble.setBackgroundResource(
                if (isHighlighted) {
                    com.rezerv.app.R.drawable.bg_message_incoming_highlight
                } else {
                    com.rezerv.app.R.drawable.bg_message_incoming
                }
            )

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

            val hasReply = !item.replyToMessageId.isNullOrBlank() && !item.replyToText.isNullOrBlank()
            binding.replyContainer.isVisible = hasReply
            if (hasReply) {
                binding.tvReplySender.text = item.replyToSenderName.orEmpty().ifBlank { item.senderName }
                binding.tvReplyText.text = item.replyToText.orEmpty()
                bindReplyImage(binding.ivReplyImage, item.replyToImageUrl)
                binding.tvReplyText.isVisible = shouldShowReplyText(item.replyToText, item.replyToImageUrl)
                binding.replyContainer.setOnClickListener { onReplyPreviewTap(item) }
            } else {
                clearReplyImage(binding.ivReplyImage)
                binding.tvReplyText.isVisible = true
                binding.replyContainer.setOnClickListener(null)
            }

            when (item.type) {
                MessageType.VOICE -> {
                    binding.tvMessage.isVisible = false
                    binding.ivImageMessage.isVisible = false
                    binding.ivImageMessage.tag = null
                    binding.ivImageMessage.setImageDrawable(null)
                    binding.ivImageMessage.setOnClickListener(null)
                    binding.photoAlbumGrid.isVisible = false
                    binding.photoAlbumGrid.removeAllViews()
                    binding.videoContainer.isVisible = false
                    binding.videoContainer.setOnClickListener(null)
                    binding.voiceContainer.isVisible = true
                    binding.btnPlayVoice.text = when {
                        playback.isPreparing -> "..."
                        playback.isPlaying -> "■"
                        else -> "▶"
                    }
                    bindVoiceProgress(
                        durationMs = playback.durationMs,
                        progressMs = playback.progressMs,
                        durationView = binding.tvVoiceDuration,
                        progressView = binding.progressVoice
                    )
                    val canPlayVoice = item.sendState == MessageSendState.SENT &&
                        item.voiceUrl?.trim().orEmpty().isNotBlank()
                    binding.btnPlayVoice.alpha = if (canPlayVoice) 1f else 0.56f
                    if (canPlayVoice) {
                        binding.btnPlayVoice.setOnClickListener { onPlayVoice() }
                        binding.voiceContainer.setOnClickListener { onPlayVoice() }
                    } else {
                        binding.btnPlayVoice.setOnClickListener(null)
                        binding.voiceContainer.setOnClickListener(null)
                    }
                }

                MessageType.IMAGE -> {
                    binding.voiceContainer.isVisible = false
                    binding.voiceContainer.setOnClickListener(null)
                    binding.videoContainer.isVisible = false
                    binding.videoContainer.setOnClickListener(null)
                    bindPhotoMessage(
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
                    bindVideoMessage(
                        container = binding.videoContainer,
                        durationView = binding.tvVideoDuration,
                        item = item,
                        onMessageVideoTap = if (item.sendState == MessageSendState.SENT) {
                            { onMessageVideoTap(item) }
                        } else {
                            null
                        }
                    )
                }

                MessageType.TEXT -> {
                    binding.tvMessage.isVisible = true
                    binding.ivImageMessage.isVisible = false
                    binding.ivImageMessage.tag = null
                    binding.ivImageMessage.setImageDrawable(null)
                    binding.photoAlbumGrid.isVisible = false
                    binding.photoAlbumGrid.removeAllViews()
                    binding.videoContainer.isVisible = false
                    binding.videoContainer.setOnClickListener(null)
                    binding.voiceContainer.isVisible = false
                    binding.voiceContainer.setOnClickListener(null)
                    binding.ivImageMessage.setOnClickListener(null)
                    binding.tvMessage.text = item.text
                }
            }

            val time = Formatters.formatTime(item.timestamp)
            binding.tvTime.text = if (item.edited) "$time (ред)" else time
            binding.root.setOnClickListener { onIncomingMessageTap(item) }
        }
    }

    class OutgoingViewHolder(
        private val binding: ItemMessageOutgoingBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(
            item: ChatMessage,
            currentUserId: String,
            recipientsCount: Int,
            isGroupChat: Boolean,
            isHighlighted: Boolean,
            playback: VoicePlaybackState,
            onPlayVoice: () -> Unit,
            onOwnMessageTap: (ChatMessage) -> Unit,
            onReplyPreviewTap: (ChatMessage) -> Unit,
            onMessageImageTap: (ChatMessage, Int, String) -> Unit,
            onMessageVideoTap: (ChatMessage) -> Unit
        ) {
            binding.messageBubble.setBackgroundResource(
                if (isHighlighted) {
                    com.rezerv.app.R.drawable.bg_message_outgoing_highlight
                } else {
                    com.rezerv.app.R.drawable.bg_message_outgoing
                }
            )
            val hasReply = !item.replyToMessageId.isNullOrBlank() && !item.replyToText.isNullOrBlank()
            binding.replyContainer.isVisible = hasReply
            if (hasReply) {
                binding.tvReplySender.text = item.replyToSenderName.orEmpty().ifBlank { "Reply" }
                binding.tvReplyText.text = item.replyToText.orEmpty()
                bindReplyImage(binding.ivReplyImage, item.replyToImageUrl)
                binding.tvReplyText.isVisible = shouldShowReplyText(item.replyToText, item.replyToImageUrl)
                binding.replyContainer.setOnClickListener { onReplyPreviewTap(item) }
            } else {
                clearReplyImage(binding.ivReplyImage)
                binding.tvReplyText.isVisible = true
                binding.replyContainer.setOnClickListener(null)
            }

            when (item.type) {
                MessageType.VOICE -> {
                    binding.tvMessage.isVisible = false
                    binding.ivImageMessage.isVisible = false
                    binding.ivImageMessage.tag = null
                    binding.ivImageMessage.setImageDrawable(null)
                    binding.ivImageMessage.setOnClickListener(null)
                    binding.photoAlbumGrid.isVisible = false
                    binding.photoAlbumGrid.removeAllViews()
                    binding.videoContainer.isVisible = false
                    binding.videoContainer.setOnClickListener(null)
                    binding.voiceContainer.isVisible = true
                    binding.btnPlayVoice.text = when {
                        playback.isPreparing -> "..."
                        playback.isPlaying -> "■"
                        else -> "▶"
                    }
                    bindVoiceProgress(
                        durationMs = playback.durationMs,
                        progressMs = playback.progressMs,
                        durationView = binding.tvVoiceDuration,
                        progressView = binding.progressVoice
                    )
                    binding.btnPlayVoice.setOnClickListener { onPlayVoice() }
                    binding.voiceContainer.setOnClickListener { onPlayVoice() }
                }

                MessageType.IMAGE -> {
                    binding.voiceContainer.isVisible = false
                    binding.voiceContainer.setOnClickListener(null)
                    binding.videoContainer.isVisible = false
                    binding.videoContainer.setOnClickListener(null)
                    bindPhotoMessage(
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
                    bindVideoMessage(
                        container = binding.videoContainer,
                        durationView = binding.tvVideoDuration,
                        item = item,
                        onMessageVideoTap = { onMessageVideoTap(item) }
                    )
                }

                MessageType.TEXT -> {
                    binding.tvMessage.isVisible = true
                    binding.ivImageMessage.isVisible = false
                    binding.ivImageMessage.tag = null
                    binding.ivImageMessage.setImageDrawable(null)
                    binding.photoAlbumGrid.isVisible = false
                    binding.photoAlbumGrid.removeAllViews()
                    binding.videoContainer.isVisible = false
                    binding.videoContainer.setOnClickListener(null)
                    binding.voiceContainer.isVisible = false
                    binding.voiceContainer.setOnClickListener(null)
                    binding.ivImageMessage.setOnClickListener(null)
                    binding.tvMessage.text = item.text
                }
            }

            val time = Formatters.formatTime(item.timestamp)
            binding.tvTime.text = if (item.edited) "$time (ред)" else time
            val status = resolveStatus(item, currentUserId, recipientsCount, isGroupChat)
            binding.tvStatus.text = status.text
            binding.tvStatus.setTextColor(status.color)
            binding.root.setOnClickListener { onOwnMessageTap(item) }
        }

        private fun resolveStatus(
            item: ChatMessage,
            currentUserId: String,
            recipientsCount: Int,
            isGroupChat: Boolean
        ): MessageStatusUi {
            when (item.sendState) {
                MessageSendState.SENDING -> {
                    return MessageStatusUi(
                        text = "...",
                        color = 0xFF93A994.toInt()
                    )
                }

                MessageSendState.FAILED -> {
                    return MessageStatusUi(
                        text = "!",
                        color = 0xFFFF6B6B.toInt()
                    )
                }

                MessageSendState.SENT -> Unit
            }

            val recipients = recipientsCount.coerceAtLeast(1)
            val deliveredCount = item.deliveredBy.asSequence().filter { it != currentUserId }.toSet().size
                .coerceIn(0, recipients)
            val readCount = item.readBy.asSequence().filter { it != currentUserId }.toSet().size
                .coerceIn(0, recipients)

            val glyph = when {
                readCount > 0 -> "✓✓"
                deliveredCount > 0 -> "✓✓"
                else -> "✓"
            }
            val color = if (readCount > 0) {
                0xFF89FF3AL.toInt()
            } else {
                0xFF93A994.toInt()
            }

            val text = if (isGroupChat) {
                "$glyph $readCount/$recipients"
            } else {
                glyph
            }
            return MessageStatusUi(text = text, color = color)
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
        const val PLAYBACK_TICK_MS = 250L

        fun bindVoiceProgress(
            durationMs: Int,
            progressMs: Int,
            durationView: android.widget.TextView,
            progressView: android.widget.ProgressBar
        ) {
            val safeDuration = durationMs.coerceAtLeast(1)
            val safeProgress = progressMs.coerceIn(0, safeDuration)
            progressView.max = safeDuration
            progressView.progress = safeProgress
            val shown = (safeDuration - safeProgress).coerceAtLeast(0)
            durationView.text = formatDuration(shown)
        }

        fun formatDuration(durationMs: Int): String {
            val totalSeconds = (durationMs / 1000).coerceAtLeast(0)
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return String.format(Locale.US, "%d:%02d", minutes, seconds)
        }

        fun bindPhotoMessage(
            imageView: ImageView,
            albumGrid: GridLayout,
            captionView: android.widget.TextView,
            item: ChatMessage,
            onMessageImageTap: ((Int, String) -> Unit)?
        ) {
            val imageUrls = resolvePhotoUrls(item)
            val hasCaption = hasVisibleCaption(item.text)
            captionView.text = if (hasCaption) item.text else ""
            captionView.isVisible = hasCaption

            if (imageUrls.isEmpty()) {
                imageView.setImageDrawable(null)
                imageView.isVisible = false
                imageView.setOnClickListener(null)
                albumGrid.isVisible = false
                albumGrid.removeAllViews()
                return
            }

            val photos = resolveAlbumPhotos(item, imageUrls)

            if (photos.size == 1) {
                val photo = photos.first()
                albumGrid.isVisible = false
                albumGrid.removeAllViews()
                bindSinglePhoto(
                    imageView = imageView,
                    imageUrl = photo.url,
                    sourceWidth = photo.width,
                    sourceHeight = photo.height,
                    onTap = onMessageImageTap?.let { tap ->
                        { tap(photo.originalIndex, photo.url) }
                    }
                )
                return
            }

            imageView.isVisible = false
            imageView.tag = null
            imageView.setImageDrawable(null)
            imageView.setOnClickListener(null)

            albumGrid.isVisible = true
            albumGrid.removeAllViews()
            albumGrid.useDefaultMargins = false
            val orderedPhotos = orderAlbumPhotosForMosaic(photos.take(10))
            val photoCount = orderedPhotos.size
            albumGrid.columnCount = ALBUM_GRID_COLUMNS
            val density = albumGrid.resources.displayMetrics.density
            val margin = albumThumbSpacingPx(density)
            val tiles = albumTileSpecs(orderedPhotos, density)
            for (index in 0 until photoCount) {
                val tile = tiles[index]
                val photo = orderedPhotos[index]
                val thumb = ImageView(albumGrid.context).apply {
                    layoutParams = GridLayout.LayoutParams().apply {
                        rowSpec = GridLayout.spec(tile.row, tile.rowSpan)
                        columnSpec = GridLayout.spec(tile.column, tile.columnSpan)
                        width = tile.widthPx
                        height = tile.heightPx
                        setMargins(0, 0, if (tile.endsRow) 0 else margin, if (tile.isLastRow) 0 else margin)
                    }
                    background = albumGrid.context.getDrawable(com.rezerv.app.R.drawable.bg_message_image)
                    clipToOutline = true
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
                thumb.tag = photo.url
                thumb.setOnClickListener(
                    if (onMessageImageTap != null) {
                        { onMessageImageTap(photo.originalIndex, photo.url) }
                    } else {
                        null
                    }
                )
                bindPhotoThumbnail(thumb, photo.url)
                albumGrid.addView(thumb)
            }
        }

        private fun albumTileSpecs(photos: List<AlbumPhoto>, density: Float): List<AlbumTileSpec> {
            val count = photos.size
            val width = albumWidthPx(density)
            val gap = albumThumbSpacingPx(density)
            val half = albumSpanWidthPx(width, gap, span = 3)
            val third = albumSpanWidthPx(width, gap, span = 2)
            val heroHeight = if (photos.firstOrNull()?.isPortrait == true) {
                albumPortraitHeroHeightPx(density)
            } else {
                albumHeroHeightPx(density)
            }
            val tallHalfHeight = albumTwoPhotoHeightPx(density)
            if (photos.firstOrNull()?.isPortrait == true && count >= 3) {
                return portraitAlbumTileSpecs(
                    count = count,
                    width = width,
                    gap = gap,
                    half = half,
                    third = third
                )
            }
            val rows = when (count) {
                2 -> listOf(
                    AlbumRow.TileSet(heightPx = tallHalfHeight, spans = listOf(3, 3))
                )
                3 -> listOf(
                    AlbumRow.Hero(heightPx = heroHeight),
                    AlbumRow.TileSet(heightPx = half, spans = listOf(3, 3))
                )
                4 -> listOf(
                    AlbumRow.TileSet(heightPx = half, spans = listOf(3, 3)),
                    AlbumRow.TileSet(heightPx = half, spans = listOf(3, 3))
                )
                5 -> listOf(
                    AlbumRow.Hero(heightPx = heroHeight),
                    AlbumRow.TileSet(heightPx = half, spans = listOf(3, 3)),
                    AlbumRow.TileSet(heightPx = half, spans = listOf(3, 3))
                )
                6 -> listOf(
                    AlbumRow.TileSet(heightPx = third, spans = listOf(2, 2, 2)),
                    AlbumRow.TileSet(heightPx = third, spans = listOf(2, 2, 2))
                )
                7 -> listOf(
                    AlbumRow.Hero(heightPx = heroHeight),
                    AlbumRow.TileSet(heightPx = third, spans = listOf(2, 2, 2)),
                    AlbumRow.TileSet(heightPx = third, spans = listOf(2, 2, 2))
                )
                8 -> listOf(
                    AlbumRow.TileSet(heightPx = half, spans = listOf(3, 3)),
                    AlbumRow.TileSet(heightPx = third, spans = listOf(2, 2, 2)),
                    AlbumRow.TileSet(heightPx = third, spans = listOf(2, 2, 2))
                )
                9 -> listOf(
                    AlbumRow.TileSet(heightPx = third, spans = listOf(2, 2, 2)),
                    AlbumRow.TileSet(heightPx = third, spans = listOf(2, 2, 2)),
                    AlbumRow.TileSet(heightPx = third, spans = listOf(2, 2, 2))
                )
                10 -> listOf(
                    AlbumRow.Hero(heightPx = heroHeight),
                    AlbumRow.TileSet(heightPx = third, spans = listOf(2, 2, 2)),
                    AlbumRow.TileSet(heightPx = third, spans = listOf(2, 2, 2)),
                    AlbumRow.TileSet(heightPx = third, spans = listOf(2, 2, 2))
                )
                else -> listOf(AlbumRow.Hero(heightPx = heroHeight))
            }
            val result = ArrayList<AlbumTileSpec>(count)
            rows.forEachIndexed rowLoop@{ rowIndex, row ->
                if (result.size >= count) return@rowLoop
                when (row) {
                    is AlbumRow.Hero -> {
                        result += AlbumTileSpec(
                            row = rowIndex,
                            column = 0,
                            rowSpan = 1,
                            columnSpan = ALBUM_GRID_COLUMNS,
                            widthPx = width,
                            heightPx = row.heightPx,
                            endsRow = true,
                            isLastRow = rowIndex == rows.lastIndex
                        )
                    }
                    is AlbumRow.TileSet -> {
                        var column = 0
                        row.spans.forEachIndexed spanLoop@{ tileIndex, span ->
                            if (result.size >= count) return@spanLoop
                            result += AlbumTileSpec(
                                row = rowIndex,
                                column = column,
                                rowSpan = 1,
                                columnSpan = span,
                                widthPx = albumSpanWidthPx(width, gap, span),
                                heightPx = row.heightPx,
                                endsRow = tileIndex == row.spans.lastIndex,
                                isLastRow = rowIndex == rows.lastIndex
                            )
                            column += span
                        }
                    }
                    is AlbumRow.PortraitLead -> Unit
                }
            }
            return result
        }

        private fun portraitAlbumTileSpecs(
            count: Int,
            width: Int,
            gap: Int,
            half: Int,
            third: Int
        ): List<AlbumTileSpec> {
            val rows = mutableListOf<AlbumRow>(
                AlbumRow.PortraitLead(largeHeightPx = half * 2 + gap, smallHeightPx = half)
            )
            var remaining = count - 3
            while (remaining > 0) {
                when {
                    remaining == 1 -> {
                        rows += AlbumRow.Hero(heightPx = albumWideTailHeightPx(half))
                        remaining -= 1
                    }
                    remaining == 2 || remaining == 4 -> {
                        rows += AlbumRow.TileSet(heightPx = half, spans = listOf(3, 3))
                        remaining -= 2
                    }
                    else -> {
                        rows += AlbumRow.TileSet(heightPx = third, spans = listOf(2, 2, 2))
                        remaining -= 3
                    }
                }
            }

            val result = ArrayList<AlbumTileSpec>(count)
            rows.forEachIndexed rowLoop@{ rowIndex, row ->
                if (result.size >= count) return@rowLoop
                when (row) {
                    is AlbumRow.PortraitLead -> {
                        result += AlbumTileSpec(
                            row = rowIndex,
                            column = 0,
                            rowSpan = 2,
                            columnSpan = 3,
                            widthPx = half,
                            heightPx = row.largeHeightPx,
                            endsRow = false,
                            isLastRow = rows.size == 1
                        )
                        result += AlbumTileSpec(
                            row = rowIndex,
                            column = 3,
                            rowSpan = 1,
                            columnSpan = 3,
                            widthPx = half,
                            heightPx = row.smallHeightPx,
                            endsRow = true,
                            isLastRow = false
                        )
                        result += AlbumTileSpec(
                            row = rowIndex + 1,
                            column = 3,
                            rowSpan = 1,
                            columnSpan = 3,
                            widthPx = half,
                            heightPx = row.smallHeightPx,
                            endsRow = true,
                            isLastRow = rows.size == 1
                        )
                    }
                    is AlbumRow.Hero -> {
                        result += AlbumTileSpec(
                            row = rowIndex + 1,
                            column = 0,
                            rowSpan = 1,
                            columnSpan = ALBUM_GRID_COLUMNS,
                            widthPx = width,
                            heightPx = row.heightPx,
                            endsRow = true,
                            isLastRow = rowIndex == rows.lastIndex
                        )
                    }
                    is AlbumRow.TileSet -> {
                        var column = 0
                        row.spans.forEachIndexed spanLoop@{ tileIndex, span ->
                            if (result.size >= count) return@spanLoop
                            result += AlbumTileSpec(
                                row = rowIndex + 1,
                                column = column,
                                rowSpan = 1,
                                columnSpan = span,
                                widthPx = albumSpanWidthPx(width, gap, span),
                                heightPx = row.heightPx,
                                endsRow = tileIndex == row.spans.lastIndex,
                                isLastRow = rowIndex == rows.lastIndex
                            )
                            column += span
                        }
                    }
                }
            }
            return result
        }

        private fun bindSinglePhoto(
            imageView: ImageView,
            imageUrl: String,
            sourceWidth: Int,
            sourceHeight: Int,
            onTap: (() -> Unit)?
        ) {
            if (imageUrl.isBlank()) {
                imageView.setImageDrawable(null)
                imageView.isVisible = false
                imageView.setOnClickListener(null)
                return
            }

            applyImageBounds(
                imageView = imageView,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight
            )
            imageView.tag = imageUrl
            imageView.isVisible = true
            imageView.setImageDrawable(null)
            imageView.setOnClickListener(
                if (onTap != null) {
                    { onTap() }
                } else {
                    null
                }
            )
            bindPhotoThumbnail(imageView, imageUrl)
        }

        private fun bindPhotoThumbnail(imageView: ImageView, imageUrl: String) {
            val safeUrl = imageUrl.trim()
            if (safeUrl.isBlank()) {
                imageView.setImageDrawable(null)
                return
            }
            ImageThumbnailLoader.bind(imageView, safeUrl)
        }

        private fun bindReplyImage(imageView: ImageView, imageUrl: String?) {
            val safeUrl = imageUrl?.trim().orEmpty()
            if (safeUrl.isBlank() || safeUrl.equals("null", ignoreCase = true)) {
                clearReplyImage(imageView)
                return
            }
            imageView.isVisible = true
            imageView.tag = safeUrl
            imageView.setImageDrawable(null)
            bindPhotoThumbnail(imageView, safeUrl)
        }

        private fun clearReplyImage(imageView: ImageView) {
            imageView.isVisible = false
            imageView.tag = null
            imageView.setImageDrawable(null)
        }

        private fun shouldShowReplyText(replyText: String?, replyImageUrl: String?): Boolean {
            val text = replyText?.trim().orEmpty()
            if (text.isBlank()) return false
            return replyImageUrl.isNullOrBlank() || text != PHOTO_FALLBACK_TEXT
        }

        private fun resolvePhotoUrls(item: ChatMessage): List<String> {
            val urls = item.imageUrls.asSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toList()
            if (urls.isNotEmpty()) return urls
            val fallback = item.imageUrl?.trim().orEmpty()
            return if (fallback.isNotBlank()) listOf(fallback) else emptyList()
        }

        private fun resolveAlbumPhotos(item: ChatMessage, imageUrls: List<String>): List<AlbumPhoto> {
            return imageUrls.mapIndexed { index, url ->
                val width = item.imageWidths.getOrNull(index)
                    ?: if (index == 0) item.imageWidth else 0
                val height = item.imageHeights.getOrNull(index)
                    ?: if (index == 0) item.imageHeight else 0
                AlbumPhoto(
                    url = url,
                    originalIndex = index,
                    width = width.coerceAtLeast(0),
                    height = height.coerceAtLeast(0)
                )
            }
        }

        private fun orderAlbumPhotosForMosaic(photos: List<AlbumPhoto>): List<AlbumPhoto> {
            if (photos.size < 3) return photos
            if (photos.any { !it.hasKnownSize }) return photos
            val portraitIndex = photos.indexOfFirst { it.isStrongPortrait }
            if (portraitIndex <= 0) return photos
            val reordered = photos.toMutableList()
            val portrait = reordered.removeAt(portraitIndex)
            reordered.add(0, portrait)
            return reordered
        }

        private fun hasVisibleCaption(text: String): Boolean {
            val trimmed = text.trim()
            return trimmed.isNotBlank() && trimmed != PHOTO_FALLBACK_TEXT
        }

        fun bindVideoMessage(
            container: android.view.View,
            durationView: android.widget.TextView,
            item: ChatMessage,
            onMessageVideoTap: (() -> Unit)?
        ) {
            val videoUrl = item.videoUrl?.trim().orEmpty()
            if (videoUrl.isBlank()) {
                container.isVisible = false
                container.setOnClickListener(null)
                return
            }
            durationView.text = formatDuration(item.videoDurationSec.coerceAtLeast(0) * 1000)
            container.isVisible = true
            container.setOnClickListener(
                if (onMessageVideoTap != null) {
                    { onMessageVideoTap() }
                } else {
                    null
                }
            )
        }

        private fun applyImageBounds(
            imageView: android.widget.ImageView,
            sourceWidth: Int,
            sourceHeight: Int
        ) {
            val density = imageView.resources.displayMetrics.density
            val maxWidth = (248f * density).toInt()
            val maxHeight = (332f * density).toInt()
            val minWidth = (120f * density).toInt()
            val minHeight = (92f * density).toInt()

            var width = sourceWidth.coerceAtLeast(0)
            var height = sourceHeight.coerceAtLeast(0)
            if (width <= 0 || height <= 0) {
                width = maxWidth
                height = (maxWidth * 0.72f).toInt()
            }

            val scale = minOf(
                maxWidth.toFloat() / width.toFloat(),
                maxHeight.toFloat() / height.toFloat(),
                1f
            )
            val targetWidth = (width * scale).toInt().coerceAtLeast(minWidth)
            val targetHeight = (height * scale).toInt().coerceAtLeast(minHeight)

            val params = imageView.layoutParams
            if (params.width != targetWidth || params.height != targetHeight) {
                params.width = targetWidth
                params.height = targetHeight
                imageView.layoutParams = params
            }
        }

        private fun albumWidthPx(density: Float): Int = (248f * density).toInt().coerceAtLeast(1)

        private fun albumHeroHeightPx(density: Float): Int = (164f * density).toInt().coerceAtLeast(1)

        private fun albumPortraitHeroHeightPx(density: Float): Int = (268f * density).toInt().coerceAtLeast(1)

        private fun albumTwoPhotoHeightPx(density: Float): Int = (168f * density).toInt().coerceAtLeast(1)

        private fun albumWideTailHeightPx(halfWidthPx: Int): Int = (halfWidthPx * 0.72f).toInt().coerceAtLeast(1)

        private fun albumThumbSpacingPx(density: Float): Int = (4f * density).toInt().coerceAtLeast(0)

        private fun albumSpanWidthPx(totalWidthPx: Int, gapPx: Int, span: Int): Int {
            val cell = (totalWidthPx - gapPx * (ALBUM_GRID_COLUMNS - 1)).toFloat() / ALBUM_GRID_COLUMNS.toFloat()
            return (cell * span + gapPx * (span - 1)).toInt().coerceAtLeast(1)
        }

        private const val ALBUM_GRID_COLUMNS = 6

        private const val PHOTO_FALLBACK_TEXT = "\uD83D\uDCF7 \u0424\u043E\u0442\u043E"
    }
}

private sealed class AlbumRow {
    data class Hero(val heightPx: Int) : AlbumRow()

    data class PortraitLead(
        val largeHeightPx: Int,
        val smallHeightPx: Int
    ) : AlbumRow()

    data class TileSet(
        val heightPx: Int,
        val spans: List<Int>
    ) : AlbumRow()
}

private data class AlbumPhoto(
    val url: String,
    val originalIndex: Int,
    val width: Int,
    val height: Int
) {
    val hasKnownSize: Boolean
        get() = width > 0 && height > 0

    val aspectRatio: Float
        get() = if (width > 0 && height > 0) width.toFloat() / height.toFloat() else 1f

    val isPortrait: Boolean
        get() = height > width && width > 0

    val isStrongPortrait: Boolean
        get() = aspectRatio <= 0.78f
}

private data class AlbumTileSpec(
    val row: Int,
    val column: Int,
    val rowSpan: Int,
    val columnSpan: Int,
    val widthPx: Int,
    val heightPx: Int,
    val endsRow: Boolean,
    val isLastRow: Boolean
)

data class VoicePlaybackState(
    val isPlaying: Boolean,
    val isPreparing: Boolean,
    val durationMs: Int,
    val progressMs: Int
)

private data class MessageStatusUi(
    val text: String,
    val color: Int
)
