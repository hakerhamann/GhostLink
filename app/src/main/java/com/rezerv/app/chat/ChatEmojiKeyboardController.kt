package com.rezerv.app.chat

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.rezerv.app.R
import com.rezerv.app.databinding.ActivityChatBinding
import com.rezerv.app.ui.adapters.EmojiAdapter

internal class ChatEmojiKeyboardController(
    private val activity: AppCompatActivity,
    private val binding: ActivityChatBinding,
    private val transitionHandler: android.os.Handler
) {
    private lateinit var emojiAdapter: EmojiAdapter
    private var emojiCategories: List<TelegramEmojiCatalog.Category> = emptyList()
    private var lastKnownKeyboardHeightPx: Int = 0
    private var composerInsetReservePx: Int = 0
    private var pendingKeyboardShowTransition: Boolean = false
    private var pendingEmojiShowTransition: Boolean = false
    private var inputBarBaseBottomPaddingPx: Int = 0
    private var appliedInputBarInsetPx: Int = 0
    private var targetInputBarInsetPx: Int = 0
    private var keyboardInsetAnimator: ValueAnimator? = null
    private var emojiPanelHeightAnimator: ValueAnimator? = null
    private var pendingEmojiOpenFallbackRunnable: Runnable? = null
    private var pendingKeyboardHideEmojiFallbackRunnable: Runnable? = null

    fun restoreLastKeyboardHeight() {
        val prefs = activity.getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)
        lastKnownKeyboardHeightPx = prefs.getInt(PREF_LAST_KEYBOARD_HEIGHT_PX, 0)
    }

    fun installWindowInsets(baseTopPadding: Int, baseInputBottomPadding: Int) {
        inputBarBaseBottomPaddingPx = baseInputBottomPadding
        appliedInputBarInsetPx = 0
        targetInputBarInsetPx = 0
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val keyboardHeight = (ime.bottom - bars.bottom).coerceAtLeast(0)
            val keyboardVisible = keyboardHeight > 0
            val keyboardTrackedVisible = keyboardHeight >= MIN_KEYBOARD_HEIGHT_TRACK_PX
            if (keyboardTrackedVisible && keyboardHeight != lastKnownKeyboardHeightPx) {
                lastKnownKeyboardHeightPx = keyboardHeight
                persistKeyboardHeight(keyboardHeight)
                applyEmojiPanelHeight()
            }
            if (pendingKeyboardShowTransition) {
                if (binding.emojiContainer.isVisible && composerInsetReservePx > 0) {
                    val collapseHeight = (composerInsetReservePx - keyboardHeight)
                        .coerceIn(0, composerInsetReservePx)
                    applyEmojiPanelHeight(collapseHeight)
                }
                val completionThresholdPx = (composerInsetReservePx - dpToPx(KEYBOARD_TRANSITION_COMPLETION_GAP_DP))
                    .coerceAtLeast(MIN_KEYBOARD_HEIGHT_TRACK_PX)
                if (keyboardVisible && keyboardHeight >= completionThresholdPx) {
                    finalizePendingKeyboardShowTransition()
                }
            }
            if (pendingEmojiShowTransition) {
                if (!keyboardVisible) {
                    val currentPanelHeight = (binding.emojiContainer.layoutParams?.height ?: 0).coerceAtLeast(0)
                    val targetPanelHeight = resolveEmojiPanelHeightPx()
                    pendingEmojiShowTransition = false
                    composerInsetReservePx = 0
                    if (currentPanelHeight != targetPanelHeight) {
                        animateEmojiPanelHeight(
                            fromHeight = currentPanelHeight,
                            toHeight = targetPanelHeight
                        )
                    } else {
                        applyEmojiPanelHeight(targetPanelHeight)
                    }
                    binding.emojiContainer.isVisible = true
                    cancelPendingEmojiOpenFallback()
                } else if (composerInsetReservePx > 0) {
                    val expandHeight = (composerInsetReservePx - keyboardHeight)
                        .coerceIn(0, composerInsetReservePx)
                    applyEmojiPanelHeight(expandHeight)
                }
            }
            if (
                keyboardVisible &&
                binding.emojiContainer.isVisible &&
                binding.etMessage.hasFocus() &&
                !pendingEmojiShowTransition &&
                !pendingKeyboardShowTransition
            ) {
                binding.emojiContainer.isVisible = false
                pendingKeyboardShowTransition = false
                composerInsetReservePx = 0
                applyEmojiPanelHeight()
            }
            syncEmojiToggleIcon()
            binding.topBar.updatePadding(top = baseTopPadding + bars.top)
            val bottomInset = when {
                pendingKeyboardShowTransition -> bars.bottom + keyboardHeight
                keyboardVisible -> bars.bottom + keyboardHeight
                binding.emojiContainer.isVisible -> bars.bottom
                composerInsetReservePx > 0 -> bars.bottom + composerInsetReservePx
                else -> bars.bottom
            }
            val smoothKeyboardOnly =
                !pendingKeyboardShowTransition &&
                    !pendingEmojiShowTransition &&
                    composerInsetReservePx == 0
            applyInputBarBottomInset(bottomInset, animate = smoothKeyboardOnly)
            insets
        }
    }

    fun setup() {
        emojiAdapter = EmojiAdapter { emoji -> insertEmoji(emoji) }
        binding.recyclerEmoji.layoutManager = GridLayoutManager(activity, EMOJI_GRID_SPAN_COUNT)
        binding.recyclerEmoji.adapter = emojiAdapter
        binding.recyclerEmoji.setHasFixedSize(true)
        binding.recyclerEmoji.overScrollMode = View.OVER_SCROLL_NEVER
        if (binding.recyclerEmoji.itemDecorationCount == 0) {
            binding.recyclerEmoji.addItemDecoration(
                EmojiGridSpacingDecoration(
                    spanCount = EMOJI_GRID_SPAN_COUNT,
                    horizontalSpacingPx = dpToPx(EMOJI_GRID_HORIZONTAL_SPACING_DP),
                    verticalSpacingPx = dpToPx(EMOJI_GRID_VERTICAL_SPACING_DP)
                )
            )
        }
        emojiCategories = TelegramEmojiCatalog.load(activity)
        applyEmojiPanelHeight()

        binding.emojiTabs.removeAllTabs()
        emojiCategories.forEachIndexed { index, category ->
            binding.emojiTabs.addTab(binding.emojiTabs.newTab().setText(category.icon), index == 0)
        }
        applyEmojiSection(0)

        binding.emojiTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                applyEmojiSection(tab.position)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        binding.btnEmoji.setOnClickListener { toggleEmojiPanel() }
        binding.etMessage.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN && binding.emojiContainer.isVisible) {
                hideEmojiPanel(showKeyboard = true)
            }
            false
        }
        binding.etMessage.setOnFocusChangeListener { _, hasFocus ->
            val keyboardVisible = liveKeyboardHeightPx() > 0
            if (hasFocus && keyboardVisible && binding.emojiContainer.isVisible && !pendingKeyboardShowTransition) {
                hideEmojiPanel(showKeyboard = false)
            }
        }
        binding.etMessage.setOnClickListener {
            if (binding.emojiContainer.isVisible) {
                hideEmojiPanel(showKeyboard = true)
            }
        }
    }

    fun isActiveOrPending(): Boolean {
        return binding.emojiContainer.isVisible || pendingEmojiShowTransition
    }

    fun hideEmojiPanel(showKeyboard: Boolean) {
        if (!binding.emojiContainer.isVisible && !showKeyboard) return
        cancelPendingEmojiOpenFallback()
        cancelEmojiPanelHeightAnimation()
        if (showKeyboard) {
            val reserveHeight = (binding.emojiContainer.layoutParams?.height ?: 0)
                .takeIf { it > 0 }
                ?: resolveEmojiPanelHeightPx()
            pendingEmojiShowTransition = false
            pendingKeyboardShowTransition = true
            composerInsetReservePx = reserveHeight
            binding.emojiContainer.isVisible = true
            applyEmojiPanelHeight(reserveHeight)
            syncEmojiToggleIcon()
            ViewCompat.requestApplyInsets(binding.root)
            binding.etMessage.requestFocus()
            showKeyboard()
            schedulePendingKeyboardHideEmojiFallback()
            return
        }

        pendingEmojiShowTransition = false
        pendingKeyboardShowTransition = false
        composerInsetReservePx = 0
        cancelPendingKeyboardHideEmojiFallback()
        val currentHeight = (binding.emojiContainer.layoutParams?.height ?: 0)
            .takeIf { it > 0 }
            ?: resolveEmojiPanelHeightPx()
        animateEmojiPanelHeight(
            fromHeight = currentHeight,
            toHeight = 0,
            onEnd = {
                binding.emojiContainer.isVisible = false
                applyEmojiPanelHeight()
                syncEmojiToggleIcon()
                ViewCompat.requestApplyInsets(binding.root)
            }
        )
        syncEmojiToggleIcon()
        ViewCompat.requestApplyInsets(binding.root)
    }

    fun showKeyboard() {
        val manager = activity.getSystemService(InputMethodManager::class.java) ?: return
        binding.etMessage.post {
            manager.showSoftInput(binding.etMessage, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun hideKeyboard() {
        val manager = activity.getSystemService(InputMethodManager::class.java) ?: return
        manager.hideSoftInputFromWindow(binding.etMessage.windowToken, 0)
    }

    fun resetForSwipeBack() {
        pendingKeyboardShowTransition = false
        pendingEmojiShowTransition = false
        composerInsetReservePx = 0
        binding.etMessage.clearFocus()
        hideKeyboard()
        ViewCompat.requestApplyInsets(binding.root)
    }

    fun destroy() {
        cancelPendingEmojiOpenFallback()
        cancelPendingKeyboardHideEmojiFallback()
        cancelEmojiPanelHeightAnimation()
        cancelKeyboardInsetAnimation()
        transitionHandler.removeCallbacksAndMessages(null)
    }

    private fun syncEmojiToggleIcon() {
        val emojiActiveOrPending =
            (binding.emojiContainer.isVisible || pendingEmojiShowTransition) && !pendingKeyboardShowTransition
        val target = activity.getString(
            if (emojiActiveOrPending) {
                R.string.emoji_keyboard
            } else {
                R.string.emoji_open
            }
        )
        if (binding.btnEmoji.text != target) {
            binding.btnEmoji.text = target
        }
    }

    private fun applyEmojiSection(index: Int) {
        val categories = emojiCategories
        if (categories.isEmpty()) {
            emojiAdapter.submit(emptyList())
            return
        }
        val safeIndex = index.coerceIn(0, categories.lastIndex)
        emojiAdapter.submit(categories[safeIndex].emojis)
    }

    private fun toggleEmojiPanel() {
        if (isActiveOrPending()) {
            hideEmojiPanel(showKeyboard = true)
        } else {
            showEmojiPanel()
        }
    }

    private fun showEmojiPanel() {
        refreshKeyboardHeightFromInsets()
        val targetHeight = resolveEmojiPanelHeightPx()
        cancelPendingEmojiOpenFallback()
        cancelPendingKeyboardHideEmojiFallback()
        cancelEmojiPanelHeightAnimation()
        pendingKeyboardShowTransition = false
        pendingEmojiShowTransition = true
        composerInsetReservePx = targetHeight
        val liveKeyboardHeight = liveKeyboardHeightPx()
        val initialPanelHeight = if (liveKeyboardHeight > 0) {
            (targetHeight - liveKeyboardHeight).coerceIn(0, targetHeight)
        } else {
            0
        }
        applyEmojiPanelHeight(initialPanelHeight)
        binding.emojiContainer.isVisible = true
        syncEmojiToggleIcon()
        binding.etMessage.clearFocus()
        ViewCompat.requestApplyInsets(binding.root)

        if (liveKeyboardHeight <= 0) {
            pendingEmojiShowTransition = false
            composerInsetReservePx = 0
            animateEmojiPanelHeight(
                fromHeight = initialPanelHeight,
                toHeight = targetHeight
            )
            syncEmojiToggleIcon()
            ViewCompat.requestApplyInsets(binding.root)
            return
        }
        schedulePendingEmojiOpenFallback(targetHeight)
        hideKeyboard()
    }

    private fun schedulePendingEmojiOpenFallback(targetHeight: Int) {
        cancelPendingEmojiOpenFallback()
        val runnable = Runnable {
            if (!pendingEmojiShowTransition) return@Runnable
            pendingEmojiShowTransition = false
            composerInsetReservePx = 0
            applyEmojiPanelHeight(targetHeight)
            binding.emojiContainer.isVisible = true
            syncEmojiToggleIcon()
            ViewCompat.requestApplyInsets(binding.root)
        }
        pendingEmojiOpenFallbackRunnable = runnable
        transitionHandler.postDelayed(runnable, EMOJI_OPEN_FALLBACK_DELAY_MS)
    }

    private fun cancelPendingEmojiOpenFallback() {
        pendingEmojiOpenFallbackRunnable?.let { transitionHandler.removeCallbacks(it) }
        pendingEmojiOpenFallbackRunnable = null
    }

    private fun schedulePendingKeyboardHideEmojiFallback() {
        cancelPendingKeyboardHideEmojiFallback()
        val runnable = Runnable {
            if (!pendingKeyboardShowTransition) return@Runnable
            finalizePendingKeyboardShowTransition()
            ViewCompat.requestApplyInsets(binding.root)
        }
        pendingKeyboardHideEmojiFallbackRunnable = runnable
        transitionHandler.postDelayed(runnable, KEYBOARD_HIDE_EMOJI_FALLBACK_DELAY_MS)
    }

    private fun cancelPendingKeyboardHideEmojiFallback() {
        pendingKeyboardHideEmojiFallbackRunnable?.let { transitionHandler.removeCallbacks(it) }
        pendingKeyboardHideEmojiFallbackRunnable = null
    }

    private fun finalizePendingKeyboardShowTransition() {
        pendingKeyboardShowTransition = false
        composerInsetReservePx = 0
        binding.emojiContainer.isVisible = false
        applyEmojiPanelHeight()
        cancelPendingKeyboardHideEmojiFallback()
    }

    private fun cancelKeyboardInsetAnimation() {
        keyboardInsetAnimator?.cancel()
        keyboardInsetAnimator = null
    }

    private fun applyInputBarBottomInset(targetInsetPx: Int, animate: Boolean) {
        val target = targetInsetPx.coerceAtLeast(0)
        targetInputBarInsetPx = target

        if (!animate) {
            cancelKeyboardInsetAnimation()
            appliedInputBarInsetPx = target
            binding.inputBar.updatePadding(bottom = inputBarBaseBottomPaddingPx + target)
            return
        }

        val delta = kotlin.math.abs(target - appliedInputBarInsetPx)
        if (delta <= dpToPx(KEYBOARD_INSET_SMALL_STEP_DP)) {
            cancelKeyboardInsetAnimation()
            appliedInputBarInsetPx = target
            binding.inputBar.updatePadding(bottom = inputBarBaseBottomPaddingPx + target)
            return
        }

        if (delta < dpToPx(KEYBOARD_INSET_ANIMATION_TRIGGER_DP)) {
            appliedInputBarInsetPx = target
            binding.inputBar.updatePadding(bottom = inputBarBaseBottomPaddingPx + target)
            return
        }

        val from = appliedInputBarInsetPx
        cancelKeyboardInsetAnimation()
        keyboardInsetAnimator = ValueAnimator.ofInt(from, target).apply {
            duration = KEYBOARD_INSET_ANIMATION_MS
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener { animator ->
                val value = (animator.animatedValue as Int).coerceAtLeast(0)
                appliedInputBarInsetPx = value
                binding.inputBar.updatePadding(bottom = inputBarBaseBottomPaddingPx + value)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (keyboardInsetAnimator === animation) {
                        keyboardInsetAnimator = null
                    }
                }

                override fun onAnimationCancel(animation: Animator) {
                    if (keyboardInsetAnimator === animation) {
                        keyboardInsetAnimator = null
                    }
                }
            })
            start()
        }
    }

    private fun cancelEmojiPanelHeightAnimation() {
        emojiPanelHeightAnimator?.cancel()
        emojiPanelHeightAnimator = null
    }

    private fun animateEmojiPanelHeight(
        fromHeight: Int,
        toHeight: Int,
        onEnd: (() -> Unit)? = null
    ) {
        val safeFrom = fromHeight.coerceAtLeast(0)
        val safeTo = toHeight.coerceAtLeast(0)
        if (safeFrom == safeTo) {
            applyEmojiPanelHeight(safeTo)
            onEnd?.invoke()
            return
        }
        cancelEmojiPanelHeightAnimation()
        emojiPanelHeightAnimator = ValueAnimator.ofInt(safeFrom, safeTo).apply {
            duration = EMOJI_PANEL_HEIGHT_ANIMATION_MS
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener { animator ->
                applyEmojiPanelHeight((animator.animatedValue as Int).coerceAtLeast(0))
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (emojiPanelHeightAnimator === animation) {
                        emojiPanelHeightAnimator = null
                    }
                    onEnd?.invoke()
                }

                override fun onAnimationCancel(animation: Animator) {
                    if (emojiPanelHeightAnimator === animation) {
                        emojiPanelHeightAnimator = null
                    }
                }
            })
            start()
        }
    }

    private fun applyEmojiPanelHeight(targetHeightOverride: Int? = null) {
        val targetHeight = targetHeightOverride ?: resolveEmojiPanelHeightPx()
        val params = binding.emojiContainer.layoutParams
        if (params.height != targetHeight) {
            params.height = targetHeight
            binding.emojiContainer.layoutParams = params
        }
    }

    private fun resolveEmojiPanelHeightPx(): Int {
        val liveImeHeight = liveKeyboardHeightPx()
        if (liveImeHeight >= MIN_KEYBOARD_HEIGHT_TRACK_PX) return liveImeHeight
        if (lastKnownKeyboardHeightPx > 0) return lastKnownKeyboardHeightPx
        val fallback = (activity.resources.displayMetrics.density * DEFAULT_EMOJI_PANEL_HEIGHT_DP).toInt()
        return fallback.coerceAtLeast((activity.resources.displayMetrics.density * 220f).toInt())
    }

    private fun refreshKeyboardHeightFromInsets() {
        val liveImeHeight = liveKeyboardHeightPx()
        if (liveImeHeight < MIN_KEYBOARD_HEIGHT_TRACK_PX) return
        if (liveImeHeight == lastKnownKeyboardHeightPx) return
        lastKnownKeyboardHeightPx = liveImeHeight
        persistKeyboardHeight(liveImeHeight)
    }

    private fun liveKeyboardHeightPx(): Int {
        val insets = ViewCompat.getRootWindowInsets(binding.root) ?: return 0
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        return (ime.bottom - bars.bottom).coerceAtLeast(0)
    }

    private fun persistKeyboardHeight(heightPx: Int) {
        activity.getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(PREF_LAST_KEYBOARD_HEIGHT_PX, heightPx)
            .apply()
    }

    private fun insertEmoji(emoji: String) {
        val editable = binding.etMessage.text ?: return
        val start = binding.etMessage.selectionStart.coerceAtLeast(0)
        val end = binding.etMessage.selectionEnd.coerceAtLeast(0)
        val from = kotlin.math.min(start, end)
        val to = kotlin.math.max(start, end)
        editable.replace(from, to, emoji)
    }

    private fun dpToPx(dp: Float): Int {
        return (dp * activity.resources.displayMetrics.density).toInt()
    }

    private class EmojiGridSpacingDecoration(
        private val spanCount: Int,
        private val horizontalSpacingPx: Int,
        private val verticalSpacingPx: Int
    ) : RecyclerView.ItemDecoration() {

        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            val position = parent.getChildAdapterPosition(view)
            if (position == RecyclerView.NO_POSITION) return
            val column = position % spanCount
            outRect.left = horizontalSpacingPx * column / spanCount
            outRect.right = horizontalSpacingPx - (horizontalSpacingPx * (column + 1) / spanCount)
            outRect.top = if (position < spanCount) 0 else verticalSpacingPx
            outRect.bottom = 0
        }
    }

    private companion object {
        private const val DEFAULT_EMOJI_PANEL_HEIGHT_DP = 248f
        private const val MIN_KEYBOARD_HEIGHT_TRACK_PX = 120
        private const val KEYBOARD_TRANSITION_COMPLETION_GAP_DP = 12f
        private const val KEYBOARD_INSET_SMALL_STEP_DP = 2f
        private const val KEYBOARD_INSET_ANIMATION_TRIGGER_DP = 8f
        private const val KEYBOARD_INSET_ANIMATION_MS = 180L
        private const val EMOJI_GRID_SPAN_COUNT = 8
        private const val EMOJI_GRID_HORIZONTAL_SPACING_DP = 6f
        private const val EMOJI_GRID_VERTICAL_SPACING_DP = 4f
        private const val EMOJI_OPEN_FALLBACK_DELAY_MS = 220L
        private const val KEYBOARD_HIDE_EMOJI_FALLBACK_DELAY_MS = 280L
        private const val EMOJI_PANEL_HEIGHT_ANIMATION_MS = 180L
        private const val UI_PREFS_NAME = "chat_ui_state"
        private const val PREF_LAST_KEYBOARD_HEIGHT_PX = "pref_last_keyboard_height_px"
    }
}
