package com.rezerv.app.ui.adapters

import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import com.rezerv.app.data.model.ChatMessage
import com.rezerv.app.data.model.MessageSendState
import java.util.Locale

internal object MessageVoiceBinder {
    fun bind(
        container: View,
        playButton: TextView,
        durationView: TextView,
        progressView: ProgressBar,
        item: ChatMessage,
        playback: VoicePlaybackState,
        canPlay: Boolean = item.sendState == MessageSendState.SENT &&
            item.voiceUrl?.trim().orEmpty().isNotBlank(),
        onPlayVoice: () -> Unit
    ) {
        container.isVisible = true
        playButton.text = when {
            playback.isPreparing -> "..."
            playback.isPlaying -> "■"
            else -> "▶"
        }
        bindVoiceProgress(
            durationMs = playback.durationMs,
            progressMs = playback.progressMs,
            durationView = durationView,
            progressView = progressView
        )
        playButton.alpha = if (canPlay) 1f else 0.56f
        if (canPlay) {
            playButton.setOnClickListener { onPlayVoice() }
            container.setOnClickListener { onPlayVoice() }
        } else {
            playButton.setOnClickListener(null)
            container.setOnClickListener(null)
        }
    }

    private fun bindVoiceProgress(
        durationMs: Int,
        progressMs: Int,
        durationView: TextView,
        progressView: ProgressBar
    ) {
        val safeDuration = durationMs.coerceAtLeast(1)
        val safeProgress = progressMs.coerceIn(0, safeDuration)
        progressView.max = safeDuration
        progressView.progress = safeProgress
        val shown = (safeDuration - safeProgress).coerceAtLeast(0)
        durationView.text = formatDuration(shown)
    }
}

internal fun formatMessageDuration(durationMs: Int): String {
    val totalSeconds = (durationMs / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}

internal data class VoicePlaybackState(
    val isPlaying: Boolean,
    val isPreparing: Boolean,
    val durationMs: Int,
    val progressMs: Int
)

private fun formatDuration(durationMs: Int): String = formatMessageDuration(durationMs)
