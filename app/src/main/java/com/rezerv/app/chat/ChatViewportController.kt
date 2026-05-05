package com.rezerv.app.chat

import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rezerv.app.data.model.ChatMessage
import com.rezerv.app.databinding.ActivityChatBinding
import com.rezerv.app.storage.SessionStore
import kotlin.math.max
import kotlin.math.min

internal class ChatViewportController(
    private val binding: ActivityChatBinding,
    private val sessionStore: SessionStore,
    private val chatId: String,
    private val messagesProvider: () -> List<ChatMessage>
) {
    fun captureAnchor(): ViewportAnchor? {
        val list = messagesProvider()
        if (list.isEmpty()) return null
        val layoutManager = layoutManager() ?: return null
        val firstVisible = layoutManager.findFirstVisibleItemPosition()
        if (firstVisible < 0 || firstVisible > list.lastIndex) return null
        val anchorView = layoutManager.findViewByPosition(firstVisible)
        val anchorOffset = (anchorView?.top ?: binding.recyclerMessages.paddingTop) -
            binding.recyclerMessages.paddingTop
        return ViewportAnchor(
            messageId = list[firstVisible].id,
            index = firstVisible,
            offsetPx = anchorOffset
        )
    }

    fun applySavedScrollPosition(
        anchorMessageId: String?,
        fallbackIndex: Int,
        anchorOffsetPx: Int,
        onApplied: (capturedAnchor: ViewportAnchor?, atBottom: Boolean, normalizedOffset: Int) -> Unit
    ) {
        val layoutManager = layoutManager() ?: return
        val maxIndex = messagesProvider().lastIndex
        if (maxIndex < 0) return

        val normalizedOffset = anchorOffsetPx.coerceIn(
            -binding.recyclerMessages.height.coerceAtLeast(1),
            binding.recyclerMessages.height.coerceAtLeast(1)
        )

        fun resolveTargetIndex(): Int {
            val list = messagesProvider()
            val indexById = anchorMessageId?.let { messageId ->
                list.indexOfFirst { it.id == messageId }
            } ?: -1
            val target = if (indexById >= 0) indexById else fallbackIndex
            return target.coerceIn(0, maxIndex)
        }

        fun applyNow() {
            val targetIndex = resolveTargetIndex()
            layoutManager.scrollToPositionWithOffset(targetIndex, normalizedOffset)
        }

        applyNow()
        binding.recyclerMessages.post {
            applyNow()
            onApplied(captureAnchor(), isAtBottom(), normalizedOffset)
        }
    }

    fun firstUnreadIndex(messages: List<ChatMessage>, myUid: String): Int {
        if (myUid.isBlank()) return -1
        return messages.indexOfFirst { message ->
            message.senderId != myUid && message.readBy.none { readerId -> readerId == myUid }
        }
    }

    fun latestVisibleUnreadIncomingTimestamp(myUid: String): Long {
        if (myUid.isBlank()) return 0L
        val messages = messagesProvider()
        if (messages.isEmpty()) return 0L

        val layoutManager = layoutManager() ?: return 0L
        val firstVisible = layoutManager.findFirstVisibleItemPosition()
        val lastVisible = layoutManager.findLastVisibleItemPosition()
        if (firstVisible == RecyclerView.NO_POSITION || lastVisible == RecyclerView.NO_POSITION) {
            return 0L
        }

        val start = min(firstVisible, lastVisible).coerceAtLeast(0)
        val end = max(firstVisible, lastVisible).coerceAtMost(messages.lastIndex)
        if (start > end) return 0L

        var latestTimestampMs = 0L
        for (index in start..end) {
            val message = messages[index]
            if (message.senderId == myUid) continue
            if (message.readBy.any { readerId -> readerId == myUid }) continue
            if (message.timestamp > latestTimestampMs) {
                latestTimestampMs = message.timestamp
            }
        }
        return latestTimestampMs
    }

    fun scrollToMessageIndex(index: Int) {
        val layoutManager = layoutManager() ?: return
        val lastIndex = messagesProvider().lastIndex
        if (lastIndex < 0) return

        val safeIndex = index.coerceIn(0, lastIndex)
        val anchorIndex = (safeIndex - 1).coerceAtLeast(0)
        layoutManager.scrollToPositionWithOffset(anchorIndex, 0)
        updateScrollToBottomButton()
    }

    fun isAtBottom(): Boolean {
        val layoutManager = layoutManager() ?: return true
        val total = messagesProvider().size
        if (total <= 1) return true

        val lastVisible = layoutManager.findLastVisibleItemPosition()
        if (lastVisible < 0) return true

        return lastVisible >= total - 1
    }

    fun scrollToBottom(animated: Boolean) {
        val lastIndex = messagesProvider().lastIndex
        if (lastIndex < 0) return
        if (animated) {
            binding.recyclerMessages.smoothScrollToPosition(lastIndex)
        } else {
            binding.recyclerMessages.scrollToPosition(lastIndex)
        }
        updateScrollToBottomButton()
    }

    fun updateScrollToBottomButton() {
        val listSize = messagesProvider().size
        if (listSize <= 1) {
            binding.btnScrollToBottom.isVisible = false
            return
        }
        val layoutManager = layoutManager()
        if (layoutManager == null) {
            binding.btnScrollToBottom.isVisible = false
            return
        }
        val lastVisible = layoutManager.findLastVisibleItemPosition()
        if (lastVisible < 0) {
            binding.btnScrollToBottom.isVisible = false
            return
        }
        val distanceToBottom = (listSize - 1) - lastVisible
        binding.btnScrollToBottom.isVisible = distanceToBottom > 0
    }

    fun persistCurrentScrollState(anchor: ViewportAnchor, atBottom: Boolean) {
        sessionStore.saveChatScrollState(
            chatId = chatId,
            anchorMessageId = anchor.messageId,
            anchorIndex = anchor.index,
            anchorOffsetPx = anchor.offsetPx,
            wasAtBottom = atBottom
        )
    }

    private fun layoutManager(): LinearLayoutManager? {
        return binding.recyclerMessages.layoutManager as? LinearLayoutManager
    }
}

internal data class ViewportAnchor(
    val messageId: String?,
    val index: Int,
    val offsetPx: Int
)
