package com.rezerv.app.ui.adapters

import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import com.rezerv.app.data.model.ChatMessage

internal object MessageVideoBinder {
    fun bind(
        container: View,
        durationView: TextView,
        item: ChatMessage,
        onMessageVideoTap: (() -> Unit)?
    ) {
        val videoUrl = item.videoUrl?.trim().orEmpty()
        if (videoUrl.isBlank()) {
            container.isVisible = false
            container.setOnClickListener(null)
            return
        }
        durationView.text = formatMessageDuration(item.videoDurationSec.coerceAtLeast(0) * 1000)
        container.isVisible = true
        container.setOnClickListener(
            if (onMessageVideoTap != null) {
                { onMessageVideoTap() }
            } else {
                null
            }
        )
    }
}
