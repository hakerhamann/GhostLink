package com.rezerv.app.ui.adapters

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
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
import com.rezerv.app.util.AvatarLoader
import com.rezerv.app.util.Formatters
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
    private val onMessageImageTap: (ChatMessage) -> Unit,
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
            onMessageImageTap: (ChatMessage) -> Unit,
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
                binding.replyContainer.setOnClickListener { onReplyPreviewTap(item) }
            } else {
                binding.replyContainer.setOnClickListener(null)
            }

            when (item.type) {
                MessageType.VOICE -> {
                    binding.tvMessage.isVisible = false
                    binding.ivImageMessage.isVisible = false
                    binding.ivImageMessage.tag = null
                    binding.ivImageMessage.setImageDrawable(null)
                    binding.ivImageMessage.setOnClickListener(null)
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
                    binding.tvMessage.isVisible = false
                    binding.voiceContainer.isVisible = false
                    binding.voiceContainer.setOnClickListener(null)
                    binding.videoContainer.isVisible = false
                    binding.videoContainer.setOnClickListener(null)
                    bindImageMessage(
                        imageView = binding.ivImageMessage,
                        item = item,
                        onMessageImageTap = if (item.sendState == MessageSendState.SENT) {
                            { onMessageImageTap(item) }
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
            onMessageImageTap: (ChatMessage) -> Unit,
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
                binding.replyContainer.setOnClickListener { onReplyPreviewTap(item) }
            } else {
                binding.replyContainer.setOnClickListener(null)
            }

            when (item.type) {
                MessageType.VOICE -> {
                    binding.tvMessage.isVisible = false
                    binding.ivImageMessage.isVisible = false
                    binding.ivImageMessage.tag = null
                    binding.ivImageMessage.setImageDrawable(null)
                    binding.ivImageMessage.setOnClickListener(null)
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
                    binding.tvMessage.isVisible = false
                    binding.voiceContainer.isVisible = false
                    binding.voiceContainer.setOnClickListener(null)
                    binding.videoContainer.isVisible = false
                    binding.videoContainer.setOnClickListener(null)
                    bindImageMessage(
                        imageView = binding.ivImageMessage,
                        item = item,
                        onMessageImageTap = { onMessageImageTap(item) }
                    )
                }

                MessageType.VIDEO -> {
                    binding.tvMessage.isVisible = false
                    binding.ivImageMessage.isVisible = false
                    binding.ivImageMessage.tag = null
                    binding.ivImageMessage.setImageDrawable(null)
                    binding.ivImageMessage.setOnClickListener(null)
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

        fun bindImageMessage(
            imageView: android.widget.ImageView,
            item: ChatMessage,
            onMessageImageTap: (() -> Unit)?
        ) {
            val imageUrl = item.imageUrl?.trim().orEmpty()
            if (imageUrl.isBlank()) {
                imageView.setImageDrawable(null)
                imageView.isVisible = false
                imageView.setOnClickListener(null)
                return
            }

            applyImageBounds(
                imageView = imageView,
                sourceWidth = item.imageWidth,
                sourceHeight = item.imageHeight
            )
            imageView.tag = imageUrl
            imageView.isVisible = true
            imageView.setImageDrawable(null)
            imageView.setOnClickListener(
                if (onMessageImageTap != null) {
                    { onMessageImageTap() }
                } else {
                    null
                }
            )

            AvatarLoader.loadFullSize(imageView.context, imageUrl) { bitmap ->
                if (imageView.tag != imageUrl) return@loadFullSize
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap)
                }
            }
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
    }
}

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
