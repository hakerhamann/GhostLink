package com.rezerv.app.chat

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.LinearLayout
import android.widget.Toast
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.addTextChangedListener
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.common.util.concurrent.ListenableFuture
import com.rezerv.app.AppContainer
import com.rezerv.app.R
import com.rezerv.app.auth.LoginActivity
import com.rezerv.app.data.model.ChatMessage
import com.rezerv.app.data.model.GroupInfo
import com.rezerv.app.data.model.MessageSendState
import com.rezerv.app.data.model.MessageType
import com.rezerv.app.data.model.UserProfile
import com.rezerv.app.data.repository.RealtimeSubscription
import com.rezerv.app.databinding.ActivityChatBinding
import com.rezerv.app.notifications.ActiveChatTracker
import com.rezerv.app.notifications.MessageNotificationHelper
import com.rezerv.app.chat.PhotoPreviewActivity
import com.rezerv.app.profile.AvatarPreviewActivity
import com.rezerv.app.profile.UserProfileActivity
import com.rezerv.app.ui.adapters.EmojiAdapter
import com.rezerv.app.ui.adapters.MessageAdapter
import com.rezerv.app.util.AvatarLoader
import com.rezerv.app.util.AvatarProcessor
import com.rezerv.app.util.ImageThumbnailLoader
import com.rezerv.app.util.PhotoMessageProcessor
import com.rezerv.app.storage.SessionStore
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private var messagesListener: RealtimeSubscription? = null
    private var currentUser: UserProfile? = null
    private var adapter: MessageAdapter? = null
    private lateinit var emojiAdapter: EmojiAdapter
    private var emojiCategories: List<TelegramEmojiCatalog.Category> = emptyList()
    private var lastKnownKeyboardHeightPx: Int = 0
    private var composerInsetReservePx: Int = 0
    private var pendingKeyboardShowTransition: Boolean = false
    private var pendingEmojiShowTransition: Boolean = false
    private var inputBarBaseBottomPaddingPx: Int = 0
    private var appliedInputBarInsetPx: Int = 0
    private var targetInputBarInsetPx: Int = 0

    private var latestMessages: List<ChatMessage> = emptyList()
    private val localOverlayMessages = mutableListOf<ChatMessage>()
    private val localToServerMessageIds = mutableMapOf<String, String>()
    private var localMessageSequence: Long = 0L
    private var hiddenMessageIds: Set<String> = emptySet()
    private var forceScrollToBottomOnNextUpdate: Boolean = false
    private var userManuallyScrolledAwayFromBottom: Boolean = false
    private var maxReadAckedTimestampMs: Long = 0L
    private var readMarkInFlightUpToTimestampMs: Long? = null
    private var pendingOpenAtFirstUnread: Boolean = false
    private var swipeToReplyHelper: ItemTouchHelper? = null
    private var replyTargetMessage: ChatMessage? = null
    private var cachedGroupInfo: GroupInfo? = null
    private var keepViewportStableOnUpdates: Boolean = false
    private var lockedViewportAnchor: ViewportAnchor? = null
    private var isApplyingViewportAnchor: Boolean = false
    private var currentRecordMode: RecordMode = RecordMode.VOICE
    private var pendingPermissionRecordingType: RecordingType? = null
    private var voiceButtonPressed: Boolean = false
    private var longPressTriggered: Boolean = false
    private var voiceTouchDownX: Float = 0f
    private var voiceTouchDownY: Float = 0f
    private val voiceButtonLongPressHandler = Handler(Looper.getMainLooper())
    private var voiceRecorder: MediaRecorder? = null
    private var voiceRecordFile: File? = null
    private var recordingStartedAtMs: Long = 0L
    private var isVoiceRecording: Boolean = false
    private var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var videoPreviewUseCase: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var videoRecording: Recording? = null
    private var videoRecordFile: File? = null
    private var videoCameraFacing: Int = CameraSelector.LENS_FACING_FRONT
    private var isVideoRecording: Boolean = false
    private var pendingVideoSendAfterStop: Boolean = false
    private var pendingCameraSwitchAfterFinalize: Boolean = false
    private var replySwipeTriggeredInGesture: Boolean = false
    private val recordingUiHandler = Handler(Looper.getMainLooper())
    private val loadingIndicatorHandler = Handler(Looper.getMainLooper())
    private val emojiTransitionHandler = Handler(Looper.getMainLooper())
    private var keyboardInsetAnimator: ValueAnimator? = null
    private var emojiPanelHeightAnimator: ValueAnimator? = null
    private var pendingLoadingIndicatorRunnable: Runnable? = null
    private var pendingEmojiOpenFallbackRunnable: Runnable? = null
    private var pendingKeyboardHideEmojiFallbackRunnable: Runnable? = null
    private val voiceLongPressRunnable = Runnable {
        if (!voiceButtonPressed || longPressTriggered || isAnyRecordingInProgress()) return@Runnable
        longPressTriggered = true
        when (currentRecordMode) {
            RecordMode.VOICE -> ensureAudioPermissionAndRecord()
            RecordMode.VIDEO -> ensureVideoPermissionsAndRecord()
        }
    }
    private val recordingTicker = object : Runnable {
        override fun run() {
            if (!isAnyRecordingInProgress()) return
            val elapsedSec = ((System.currentTimeMillis() - recordingStartedAtMs) / 1000L).toInt().coerceAtLeast(0)
            if (isVideoRecording && elapsedSec >= MAX_VIDEO_RECORD_DURATION_SEC) {
                stopVideoRecording(send = true)
                return
            }
            updateRecordingUi(recording = true, elapsedSec = elapsedSec)
            recordingUiHandler.postDelayed(this, 1000L)
        }
    }

    private val chatId: String by lazy {
        intent.getStringExtra(EXTRA_CHAT_ID).orEmpty()
    }

    private val chatTitle: String by lazy {
        intent.getStringExtra(EXTRA_CHAT_TITLE).orEmpty().ifBlank { getString(R.string.chat_fallback_title) }
    }

    private val initialRecipientsCount: Int by lazy {
        val members = intent.getIntExtra(EXTRA_CHAT_MEMBER_COUNT, 2).coerceAtLeast(2)
        (members - 1).coerceAtLeast(1)
    }
    private var currentRecipientsCount: Int = 1
    private val isGroupChat: Boolean by lazy {
        intent.getBooleanExtra(EXTRA_CHAT_IS_GROUP, false)
    }
    private val initialUnreadCount: Int by lazy {
        intent.getIntExtra(EXTRA_CHAT_UNREAD_COUNT, 0).coerceAtLeast(0)
    }
    private val chatPeerUid: String by lazy {
        intent.getStringExtra(EXTRA_CHAT_PEER_UID).orEmpty()
    }
    private val chatPeerLogin: String by lazy {
        intent.getStringExtra(EXTRA_CHAT_PEER_LOGIN).orEmpty()
    }
    private val chatPeerDisplayName: String by lazy {
        intent.getStringExtra(EXTRA_CHAT_PEER_DISPLAY_NAME).orEmpty()
    }
    private val chatAvatarUrl: String by lazy {
        intent.getStringExtra(EXTRA_CHAT_AVATAR_URL).orEmpty()
    }

    private var swipeStartX: Float = 0f
    private var swipeStartY: Float = 0f
    private var swipeBackTouchSlopPx: Float = 0f
    private var swipeBackArmed: Boolean = false
    private var swipeBackHandled: Boolean = false
    private var swipeBackDragging: Boolean = false
    private val selectedPhotoUris = mutableListOf<Uri>()
    private val removedEditedPhotoUrls = mutableSetOf<String>()
    private val pendingEditedMessages = mutableMapOf<String, PendingEditedMessage>()
    private var pendingPhotoSelectionTarget: PhotoSelectionTarget = PhotoSelectionTarget.COMPOSE
    private var inlineEditState: InlineEditState? = null

    private val pickGroupAvatar = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) uploadGroupAvatar(uri)
    }

    private val pickMessagePhotos = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            when (pendingPhotoSelectionTarget) {
                PhotoSelectionTarget.COMPOSE -> addPhotoDrafts(uris)
                PhotoSelectionTarget.EDIT -> applyEditedPhotoSelection(uris)
            }
        }
        pendingPhotoSelectionTarget = PhotoSelectionTarget.COMPOSE
    }

    private val recordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val pendingType = pendingPermissionRecordingType
        pendingPermissionRecordingType = null
        if (!granted) {
            Toast.makeText(this, "Нужно разрешение на микрофон", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        if (pendingType == RecordingType.VOICE && voiceButtonPressed) {
            startVoiceRecordingInternal()
        }
    }

    private val videoPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val pendingType = pendingPermissionRecordingType
        pendingPermissionRecordingType = null
        val cameraGranted = permissions[Manifest.permission.CAMERA] == true
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        if (!cameraGranted || !audioGranted) {
            Toast.makeText(this, "Нужны разрешения на камеру и микрофон", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        if (pendingType == RecordingType.VIDEO && voiceButtonPressed) {
            startVideoRecordingInternal()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (chatId.isBlank()) {
            finish()
            return
        }

        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        swipeBackTouchSlopPx = ViewConfiguration.get(this).scaledTouchSlop.toFloat().coerceAtLeast(1f)
        restoreLastKeyboardHeight()
        currentRecipientsCount = initialRecipientsCount
        pendingOpenAtFirstUnread = initialUnreadCount > 0

        val baseTopPadding = binding.topBar.paddingTop
        val baseInputBottomPadding = binding.inputBar.paddingBottom
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

        binding.tvChatTitle.text = chatTitle
        bindChatHeaderAvatar(
            displayName = chatPeerDisplayName.ifBlank { chatTitle },
            avatarUrl = chatAvatarUrl.ifBlank { null }
        )
        binding.tvChatTitle.setOnClickListener {
            if (!isGroupChat) {
                openUserProfile(
                    uid = chatPeerUid,
                    login = chatPeerLogin,
                    displayName = chatPeerDisplayName.ifBlank { chatTitle },
                    avatarUrl = chatAvatarUrl.ifBlank { null }
                )
            }
        }
        binding.chatAvatarContainer.setOnClickListener { openChatHeaderAvatarTarget() }
        binding.ivChatAvatar.setOnClickListener { openChatHeaderAvatarTarget() }
        binding.tvChatAvatarFallback.setOnClickListener { openChatHeaderAvatarTarget() }
        binding.btnBack.setOnClickListener { finishChatWithBackAnimation() }
        binding.btnChatMenu.setOnClickListener { anchor -> showChatMenu(anchor) }
        binding.btnSend.setOnClickListener { sendMessage() }
        binding.btnScrollToBottom.setOnClickListener { scrollToBottom(animated = true) }
        binding.btnAttach.setOnClickListener {
            if (isAnyRecordingInProgress()) {
                Toast.makeText(this, "Завершите запись", Toast.LENGTH_SHORT).show()
            } else if (isEditingTextMessage()) {
                Toast.makeText(this, "Для текста доступно только редактирование подписи", Toast.LENGTH_SHORT).show()
            } else {
                hideEmojiPanel(showKeyboard = false)
                pendingPhotoSelectionTarget = if (isEditingPhotoMessage()) {
                    PhotoSelectionTarget.EDIT
                } else {
                    PhotoSelectionTarget.COMPOSE
                }
                pickMessagePhotos.launch("image/*")
            }
        }
        binding.btnClearPhotoDrafts.setOnClickListener { clearPhotoDrafts() }
        binding.btnVoice.setOnClickListener { toggleRecordMode() }
        binding.btnVoice.setOnTouchListener { view, event -> handleVoiceButtonTouch(view, event) }
        binding.btnSwitchVideoCamera.setOnClickListener { switchVideoCamera() }
        binding.btnGroupAvatar.isVisible = false
        binding.btnGroupAvatar.setOnClickListener(null)
        binding.btnCancelReply.setOnClickListener { clearReplyTarget() }
        binding.btnCancelEdit.setOnClickListener { cancelInlineEdit() }
        updateRecordingUi(false, elapsedSec = 0)
        setupEmojiPanel()
        binding.etMessage.addTextChangedListener { updateComposerActionState() }
        binding.etMessage.addTextChangedListener { updateInlineEditUi() }
        updatePhotoDraftUi()
        updateComposerActionState()
        updateInlineEditUi()

        onBackPressedDispatcher.addCallback(this) {
            if (isEditingMessage()) {
                cancelInlineEdit()
                return@addCallback
            }
            if (binding.emojiContainer.isVisible) {
                hideEmojiPanel(showKeyboard = false)
            } else {
                finishChatWithBackAnimation()
            }
        }

        binding.recyclerMessages.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.recyclerMessages.setHasFixedSize(true)
        binding.recyclerMessages.itemAnimator = null
        binding.recyclerMessages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (isApplyingViewportAnchor) {
                    updateScrollToBottomButton()
                    return
                }
                if (isAtBottom()) {
                    userManuallyScrolledAwayFromBottom = false
                    keepViewportStableOnUpdates = false
                    lockedViewportAnchor = null
                } else {
                    if (dy < 0) {
                        userManuallyScrolledAwayFromBottom = true
                    } else if (!userManuallyScrolledAwayFromBottom) {
                        userManuallyScrolledAwayFromBottom = true
                    }
                    if (keepViewportStableOnUpdates) {
                        lockedViewportAnchor = captureViewportAnchor()
                    }
                }
                updateScrollToBottomButton()
                markVisibleIncomingAsReadIfNeeded()
            }
        })

        lifecycleScope.launch { initSession() }
    }

    override fun onDestroy() {
        cancelPendingMessagesLoadingIndicator()
        messagesListener?.remove()
        messagesListener = null
        swipeToReplyHelper?.attachToRecyclerView(null)
        swipeToReplyHelper = null
        adapter?.releaseVoicePlayback()
        recordingUiHandler.removeCallbacksAndMessages(null)
        voiceButtonLongPressHandler.removeCallbacksAndMessages(null)
        cancelPendingEmojiOpenFallback()
        cancelPendingKeyboardHideEmojiFallback()
        cancelEmojiPanelHeightAnimation()
        emojiTransitionHandler.removeCallbacksAndMessages(null)
        stopAnyActiveRecording(send = false)
        releaseVideoCapture()
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        ActiveChatTracker.setActiveChat(chatId)
        MessageNotificationHelper.cancelChatNotification(this, chatId)
        currentUser?.let { startMessagesObserver() }
    }

    override fun onStop() {
        persistCurrentScrollState()
        cancelPendingMessagesLoadingIndicator()
        binding.progress.isVisible = false
        if (!isFinishing) {
            resetSwipeBackPosition(immediate = true)
        }
        ActiveChatTracker.clear(chatId)
        messagesListener?.remove()
        messagesListener = null
        stopAnyActiveRecording(send = false)
        super.onStop()
    }

    override fun onPause() {
        persistCurrentScrollState()
        super.onPause()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!::binding.isInitialized) return super.dispatchTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeStartX = ev.x
                swipeStartY = ev.y
                swipeBackArmed = !isSwipeBackBlockedZone(ev) &&
                    (binding.root.translationX >= 0f)
                swipeBackHandled = false
                swipeBackDragging = false
                if (binding.root.translationX != 0f) {
                    resetSwipeBackPosition(immediate = true)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (swipeBackArmed) {
                    val dx = ev.x - swipeStartX
                    val dy = abs(ev.y - swipeStartY)
                    if (!swipeBackDragging) {
                        if (dx > swipeBackTouchSlopPx && dx > dy * 1.05f) {
                            swipeBackDragging = true
                            swipeBackHandled = true
                            pendingKeyboardShowTransition = false
                            pendingEmojiShowTransition = false
                            composerInsetReservePx = 0
                            binding.etMessage.clearFocus()
                            hideKeyboard()
                            ViewCompat.requestApplyInsets(binding.root)
                        } else if (dy > swipeBackTouchSlopPx && dy > dx) {
                            swipeBackArmed = false
                        }
                    }
                    if (swipeBackDragging) {
                        applySwipeBackOffset(dx.coerceAtLeast(0f))
                        return true
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                if (swipeBackDragging) {
                    val dx = (ev.x - swipeStartX).coerceAtLeast(0f)
                    val shouldFinish = shouldCompleteSwipeBack(dx)
                    if (shouldFinish) {
                        completeSwipeBack()
                    } else {
                        resetSwipeBackPosition(immediate = false)
                    }
                    swipeBackArmed = false
                    swipeBackHandled = false
                    swipeBackDragging = false
                    return true
                }
                swipeBackArmed = false
                swipeBackHandled = false
                swipeBackDragging = false
            }

            MotionEvent.ACTION_CANCEL -> {
                if (swipeBackDragging) {
                    resetSwipeBackPosition(immediate = false)
                    swipeBackArmed = false
                    swipeBackHandled = false
                    swipeBackDragging = false
                    return true
                }
                swipeBackArmed = false
                swipeBackHandled = false
                swipeBackDragging = false
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun shouldCompleteSwipeBack(offsetX: Float): Boolean {
        val width = binding.root.width.toFloat().coerceAtLeast(1f)
        return offsetX >= width * SWIPE_BACK_FINISH_FRACTION
    }

    private fun applySwipeBackOffset(offsetX: Float) {
        val width = binding.root.width.toFloat().coerceAtLeast(1f)
        val clamped = offsetX.coerceIn(0f, width)
        val progress = (clamped / width).coerceIn(0f, 1f)
        binding.root.translationX = clamped
        binding.root.alpha = 1f - (progress * 0.08f)
    }

    private fun resetSwipeBackPosition(immediate: Boolean) {
        binding.root.animate().cancel()
        if (immediate) {
            binding.root.translationX = 0f
            binding.root.alpha = 1f
            return
        }
        binding.root.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(160L)
            .setInterpolator(FastOutSlowInInterpolator())
            .start()
    }

    private fun completeSwipeBack() {
        val width = binding.root.width.toFloat().coerceAtLeast(1f)
        binding.root.animate().cancel()
        binding.root.animate()
            .translationX(width)
            .alpha(0.94f)
            .setDuration(170L)
            .setInterpolator(FastOutSlowInInterpolator())
            .withEndAction {
                finishChatBySwipe()
                overridePendingTransition(0, 0)
            }
            .start()
    }

    private fun isSwipeBackBlockedZone(event: MotionEvent): Boolean {
        if (!binding.emojiContainer.isVisible) return false
        return isPointInsideView(event, binding.emojiContainer)
    }

    private fun isPointInsideView(event: MotionEvent, view: View): Boolean {
        if (!view.isShown || view.width <= 0 || view.height <= 0) return false
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val left = location[0].toFloat()
        val top = location[1].toFloat()
        val right = left + view.width
        val bottom = top + view.height
        return event.rawX in left..right && event.rawY in top..bottom
    }

    private suspend fun initSession() {
        val localUser = AppContainer.sessionStore.currentUser()
        if (localUser != null) {
            initSessionForUser(localUser)
            lifecycleScope.launch {
                val refreshed = AppContainer.authRepository.currentUserProfile()
                if (refreshed == null) {
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            openLogin()
                        }
                    }
                } else {
                    currentUser = refreshed
                }
            }
            return
        }

        val user = AppContainer.authRepository.currentUserProfile()
        if (user == null) {
            openLogin()
            return
        }
        initSessionForUser(user)
    }

    private fun initSessionForUser(user: UserProfile) {
        currentUser = user
        maxReadAckedTimestampMs = 0L
        readMarkInFlightUpToTimestampMs = null
        hiddenMessageIds = AppContainer.sessionStore.hiddenMessageIds(chatId)
        val savedState = AppContainer.sessionStore.chatScrollState(chatId)
        if (savedState != null && !savedState.wasAtBottom) {
            keepViewportStableOnUpdates = true
            lockedViewportAnchor = ViewportAnchor(
                messageId = savedState.anchorMessageId,
                index = savedState.anchorIndex,
                offsetPx = savedState.anchorOffsetPx
            )
            pendingOpenAtFirstUnread = false
            forceScrollToBottomOnNextUpdate = false
            userManuallyScrolledAwayFromBottom = true
        } else {
            keepViewportStableOnUpdates = false
            lockedViewportAnchor = null
        }
        adapter = MessageAdapter(
            currentUserId = user.uid,
            initialRecipientsCount = currentRecipientsCount,
            isGroupChat = isGroupChat,
            onIncomingAvatarTap = ::openIncomingAvatarPreview,
            onSenderNameTap = ::openIncomingSenderProfile,
            onIncomingMessageTap = ::showIncomingMessageActions,
            onOwnMessageTap = ::showOwnMessageActions,
            onReplyPreviewTap = ::onReplyPreviewTap,
            onMessageImageTap = ::openPhotoMessagePreview,
            onMessageVideoTap = ::openVideoMessagePreview
        )
        binding.recyclerMessages.adapter = adapter
        setupSwipeToReplyIfNeeded()
        showCachedMessagesIfAvailable()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            startMessagesObserver()
        }
        refreshGroupInfoSilently()
    }

    private fun startMessagesObserver() {
        messagesListener?.remove()
        showCachedMessagesIfAvailable()
        scheduleMessagesLoadingIndicator()

        messagesListener = AppContainer.chatRepository.observeMessages(
            chatId = chatId,
            onUpdate = { messages ->
                runOnUiThread {
                    cancelPendingMessagesLoadingIndicator()
                    binding.progress.isVisible = false
                    latestMessages = messages
                    syncOverlayMessagesWithServer(messages)
                    pruneOverlayMessages(messages)
                    pruneEditedMessages(messages)
                    submitVisibleMessages(mergeMessagesForDisplay(messages))
                }
            },
            onError = { throwable ->
                runOnUiThread {
                    cancelPendingMessagesLoadingIndicator()
                    binding.progress.isVisible = false
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                        Toast.makeText(
                            this,
                            throwable.message ?: getString(R.string.messages_load_error),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )
    }

    private fun showCachedMessagesIfAvailable() {
        val repository = AppContainer.chatRepository
        if (!repository.hasCachedMessages(chatId)) return
        val cachedMessages = repository.getCachedMessages(chatId)
        latestMessages = cachedMessages
        syncOverlayMessagesWithServer(cachedMessages)
        pruneOverlayMessages(cachedMessages)
        submitVisibleMessages(mergeMessagesForDisplay(cachedMessages))
        binding.progress.isVisible = false
    }

    private fun scheduleMessagesLoadingIndicator() {
        cancelPendingMessagesLoadingIndicator()
        if (hasAnyMessagesToShow()) {
            binding.progress.isVisible = false
            return
        }
        val task = Runnable {
            pendingLoadingIndicatorRunnable = null
            if (!hasAnyMessagesToShow() && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                binding.progress.isVisible = true
            }
        }
        pendingLoadingIndicatorRunnable = task
        loadingIndicatorHandler.postDelayed(task, INITIAL_LOADING_INDICATOR_DELAY_MS)
    }

    private fun cancelPendingMessagesLoadingIndicator() {
        pendingLoadingIndicatorRunnable?.let { loadingIndicatorHandler.removeCallbacks(it) }
        pendingLoadingIndicatorRunnable = null
    }

    private fun hasAnyMessagesToShow(): Boolean {
        return (adapter?.itemCount ?: 0) > 0 ||
            latestMessages.isNotEmpty() ||
            localOverlayMessages.isNotEmpty()
    }

    private fun mergeMessagesForDisplay(serverMessages: List<ChatMessage> = latestMessages): List<ChatMessage> {
        val mergedBase = if (localOverlayMessages.isEmpty()) {
            serverMessages
        } else {
            val mappedServerIds = localToServerMessageIds.values
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toHashSet()
            val filteredServerMessages = if (mappedServerIds.isEmpty()) {
                serverMessages
            } else {
                serverMessages.filterNot { it.id in mappedServerIds }
            }
            val serverIds = filteredServerMessages.mapTo(hashSetOf()) { it.id }
            val overlay = localOverlayMessages
                .asSequence()
                .filter { it.id !in serverIds }
                .sortedBy { it.timestamp }
                .toList()
            if (overlay.isEmpty()) {
                filteredServerMessages
            } else if (filteredServerMessages.isEmpty()) {
                overlay
            } else {
                val merged = ArrayList<ChatMessage>(filteredServerMessages.size + overlay.size)
                var serverIndex = 0
                var overlayIndex = 0
                while (serverIndex < filteredServerMessages.size && overlayIndex < overlay.size) {
                    val serverMessage = filteredServerMessages[serverIndex]
                    val overlayMessage = overlay[overlayIndex]
                    val takeServerMessage = if (serverMessage.timestamp != overlayMessage.timestamp) {
                        serverMessage.timestamp <= overlayMessage.timestamp
                    } else {
                        true
                    }
                    if (takeServerMessage) {
                        merged += serverMessage
                        serverIndex += 1
                    } else {
                        merged += overlayMessage
                        overlayIndex += 1
                    }
                }
                while (serverIndex < filteredServerMessages.size) {
                    merged += filteredServerMessages[serverIndex++]
                }
                while (overlayIndex < overlay.size) {
                    merged += overlay[overlayIndex++]
                }
                merged
            }
        }
        return applyPendingEditedMessages(mergedBase)
    }

    private fun applyPendingEditedMessages(messages: List<ChatMessage>): List<ChatMessage> {
        if (pendingEditedMessages.isEmpty()) return messages
        return messages.map { current ->
            val pendingEntry = pendingEditedMessages.entries.firstOrNull { (_, pending) ->
                current.id == pending.replacement.id || current.id == pending.serverMessageId
            } ?: return@map current
            pendingEntry.value.replacement.copy(id = current.id, sendState = current.sendState)
        }
    }

    private fun pruneEditedMessages(serverMessages: List<ChatMessage>) {
        if (pendingEditedMessages.isEmpty()) return
        val serverById = serverMessages.associateBy { it.id }
        val iterator = pendingEditedMessages.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val pending = entry.value
            val serverMessage = serverById[pending.serverMessageId] ?: serverById[pending.replacement.id] ?: continue
            if (messagesMatchForEdit(serverMessage, pending.replacement)) {
                iterator.remove()
            }
        }
    }

    private fun messagesMatchForEdit(first: ChatMessage, second: ChatMessage): Boolean {
        return first.type == second.type &&
            first.text == second.text &&
            first.voiceUrl == second.voiceUrl &&
            first.voiceDurationSec == second.voiceDurationSec &&
            first.imageUrl == second.imageUrl &&
            first.imageUrls == second.imageUrls &&
            first.imageWidth == second.imageWidth &&
            first.imageHeight == second.imageHeight &&
            first.imageWidths == second.imageWidths &&
            first.imageHeights == second.imageHeights &&
            first.videoUrl == second.videoUrl &&
            first.videoDurationSec == second.videoDurationSec &&
            first.replyToMessageId == second.replyToMessageId &&
            first.replyToSenderName == second.replyToSenderName &&
            first.replyToText == second.replyToText &&
            first.replyToImageUrl == second.replyToImageUrl &&
            first.edited == second.edited
    }

    private fun pruneOverlayMessages(serverMessages: List<ChatMessage>) {
        if (localOverlayMessages.isEmpty()) return
        val serverIds = serverMessages.mapTo(hashSetOf()) { it.id }
        val iterator = localOverlayMessages.iterator()
        while (iterator.hasNext()) {
            val message = iterator.next()
            if (message.id in serverIds) {
                iterator.remove()
                localToServerMessageIds.remove(message.id)
            }
        }
    }

    private fun syncOverlayMessagesWithServer(serverMessages: List<ChatMessage>) {
        if (localOverlayMessages.isEmpty() || localToServerMessageIds.isEmpty()) return
        val serverById = serverMessages.associateBy { it.id }
        for (index in localOverlayMessages.indices) {
            val overlayMessage = localOverlayMessages[index]
            val mappedServerId = localToServerMessageIds[overlayMessage.id]?.trim().orEmpty()
            if (mappedServerId.isBlank()) continue
            val serverMessage = serverById[mappedServerId] ?: continue
            localOverlayMessages[index] = serverMessage.copy(
                id = overlayMessage.id,
                sendState = MessageSendState.SENT
            )
        }
    }

    private fun appendOverlayMessage(message: ChatMessage) {
        localOverlayMessages += message
        submitVisibleMessages(mergeMessagesForDisplay())
    }

    private fun appendOutgoingOverlayMessage(message: ChatMessage) {
        forceScrollToBottomOnNextUpdate = true
        userManuallyScrolledAwayFromBottom = false
        appendOverlayMessage(message)
    }

    private fun markOverlayMessageSent(localId: String, confirmedMessage: ChatMessage) {
        localToServerMessageIds[localId] = confirmedMessage.id
        val replacement = confirmedMessage.copy(id = localId, sendState = MessageSendState.SENT)
        val index = localOverlayMessages.indexOfFirst { it.id == localId }
        if (index >= 0) {
            localOverlayMessages[index] = replacement
        } else if (latestMessages.none { it.id == confirmedMessage.id }) {
            localOverlayMessages += replacement
        }
        submitVisibleMessages(mergeMessagesForDisplay())
    }

    private fun markOverlayMessageFailed(localId: String) {
        localToServerMessageIds.remove(localId)
        val index = localOverlayMessages.indexOfFirst { it.id == localId }
        if (index < 0) return
        localOverlayMessages[index] = localOverlayMessages[index].copy(sendState = MessageSendState.FAILED)
        submitVisibleMessages(mergeMessagesForDisplay())
    }

    private fun removeOverlayMessage(rawId: String?) {
        val normalizedId = rawId?.trim().orEmpty()
        if (normalizedId.isBlank() || localOverlayMessages.isEmpty()) return
        var removed = false
        val iterator = localOverlayMessages.iterator()
        while (iterator.hasNext()) {
            val overlayMessage = iterator.next()
            val mappedServerId = localToServerMessageIds[overlayMessage.id]?.trim().orEmpty()
            if (overlayMessage.id == normalizedId || (mappedServerId.isNotBlank() && mappedServerId == normalizedId)) {
                iterator.remove()
                localToServerMessageIds.remove(overlayMessage.id)
                removed = true
            }
        }
        if (removed) {
            submitVisibleMessages(mergeMessagesForDisplay())
        }
    }

    private fun resolveServerMessageId(rawId: String?): String? {
        val id = rawId?.trim().orEmpty()
        if (id.isBlank()) return null
        if (!id.startsWith("local:", ignoreCase = true)) return id
        return localToServerMessageIds[id]?.trim().orEmpty().ifBlank { null }
    }

    private fun sanitizeReplyMessageId(message: ChatMessage?): String? {
        return resolveServerMessageId(message?.id)
    }

    private fun nextLocalMessageId(timestampMs: Long): String {
        return "local:${timestampMs}:${localMessageSequence++}"
    }

    private fun buildOptimisticBaseMessage(
        user: UserProfile,
        type: MessageType,
        text: String,
        replyTarget: ChatMessage?,
        timestampMs: Long = System.currentTimeMillis()
    ): ChatMessage {
        val localId = nextLocalMessageId(timestampMs)
        return ChatMessage(
            id = localId,
            senderId = user.uid,
            senderName = user.displayName.ifBlank { user.login },
            senderAvatarUrl = user.avatarUrl,
            text = text,
            type = type,
            replyToMessageId = sanitizeReplyMessageId(replyTarget),
            replyToSenderName = replyTarget?.senderName,
            replyToText = replyTarget?.let(::replyPreviewText),
            replyToImageUrl = replyTarget?.let(::replyPreviewImageUrl),
            timestamp = timestampMs,
            deliveredBy = listOf(user.uid),
            readBy = listOf(user.uid),
            sendState = MessageSendState.SENDING
        )
    }

    private fun buildOptimisticTextMessage(
        user: UserProfile,
        text: String,
        replyTarget: ChatMessage?
    ): ChatMessage {
        return buildOptimisticBaseMessage(
            user = user,
            type = MessageType.TEXT,
            text = text,
            replyTarget = replyTarget
        )
    }

    private fun buildOptimisticVoiceMessage(
        user: UserProfile,
        durationSec: Int,
        replyTarget: ChatMessage?
    ): ChatMessage {
        return buildOptimisticBaseMessage(
            user = user,
            type = MessageType.VOICE,
            text = VOICE_PREVIEW_TEXT,
            replyTarget = replyTarget
        ).copy(
            voiceDurationSec = durationSec.coerceAtLeast(0)
        )
    }

    private fun buildOptimisticImageMessage(
        user: UserProfile,
        imageUrls: List<String>,
        imageWidths: List<Int> = emptyList(),
        imageHeights: List<Int> = emptyList(),
        caption: String,
        replyTarget: ChatMessage?
    ): ChatMessage {
        val message = buildOptimisticBaseMessage(
            user = user,
            type = MessageType.IMAGE,
            text = caption.ifBlank { PHOTO_PREVIEW_TEXT },
            replyTarget = replyTarget
        )
        val safeImageUrls = imageUrls.mapIndexed { index, value ->
            value.ifBlank { "pending://image/${message.id}/$index" }
        }
        return message.copy(
            imageUrl = safeImageUrls.firstOrNull() ?: "pending://image/${message.id}",
            imageUrls = safeImageUrls,
            imageWidth = imageWidths.firstOrNull()?.coerceAtLeast(0) ?: 0,
            imageHeight = imageHeights.firstOrNull()?.coerceAtLeast(0) ?: 0,
            imageWidths = imageWidths.map { it.coerceAtLeast(0) },
            imageHeights = imageHeights.map { it.coerceAtLeast(0) }
        )
    }

    private fun buildOptimisticVideoMessage(
        user: UserProfile,
        durationSec: Int,
        replyTarget: ChatMessage?
    ): ChatMessage {
        val message = buildOptimisticBaseMessage(
            user = user,
            type = MessageType.VIDEO,
            text = VIDEO_PREVIEW_TEXT,
            replyTarget = replyTarget
        )
        return message.copy(
            videoUrl = "pending://video/${message.id}",
            videoDurationSec = durationSec.coerceAtLeast(0)
        )
    }

    private fun submitVisibleMessages(messages: List<ChatMessage>) {
        val visibleMessages = messages.filterNot { hiddenMessageIds.contains(it.id) }
        val previousMessages = adapter?.currentList.orEmpty()
        val previousLastId = previousMessages.lastOrNull()?.id
        val wasAtBottomBeforeUpdate = isAtBottom()
        val shouldPreserveViewport = keepViewportStableOnUpdates && !forceScrollToBottomOnNextUpdate
        val preservedAnchor = if (shouldPreserveViewport) {
            captureViewportAnchor() ?: lockedViewportAnchor
        } else {
            null
        }
        AvatarLoader.prefetch(this, visibleMessages.map { it.senderAvatarUrl })
        val firstUnreadIndex = if (pendingOpenAtFirstUnread) findFirstUnreadIndex(visibleMessages) else -1
        val shouldScrollToBottom = !shouldPreserveViewport &&
            (shouldAutoScrollToBottom() || forceScrollToBottomOnNextUpdate) &&
            firstUnreadIndex < 0
        val appendedAtBottom = previousMessages.isNotEmpty() &&
            visibleMessages.size >= previousMessages.size &&
            visibleMessages.lastOrNull()?.id != null &&
            visibleMessages.lastOrNull()?.id != previousLastId &&
            wasAtBottomBeforeUpdate &&
            firstUnreadIndex < 0

        adapter?.submitList(visibleMessages) {
            if (preservedAnchor != null && visibleMessages.isNotEmpty()) {
                applySavedScrollPosition(
                    anchorMessageId = preservedAnchor.messageId,
                    fallbackIndex = preservedAnchor.index.coerceIn(0, visibleMessages.lastIndex),
                    anchorOffsetPx = preservedAnchor.offsetPx
                )
                if (pendingOpenAtFirstUnread && visibleMessages.isNotEmpty()) {
                    pendingOpenAtFirstUnread = false
                }
                forceScrollToBottomOnNextUpdate = false
                binding.recyclerMessages.post { markVisibleIncomingAsReadIfNeeded() }
                return@submitList
            }
            if (visibleMessages.isNotEmpty()) {
                when {
                    firstUnreadIndex >= 0 -> {
                        scrollToMessageIndex(firstUnreadIndex)
                        userManuallyScrolledAwayFromBottom = true
                    }

                    shouldScrollToBottom -> {
                        val animatedScroll = appendedAtBottom ||
                            (forceScrollToBottomOnNextUpdate && previousMessages.isNotEmpty())
                        scrollToBottom(animated = animatedScroll)
                    }
                }
            }
            if (pendingOpenAtFirstUnread && visibleMessages.isNotEmpty()) {
                pendingOpenAtFirstUnread = false
            }
            forceScrollToBottomOnNextUpdate = false
            updateScrollToBottomButton()
            binding.recyclerMessages.post { markVisibleIncomingAsReadIfNeeded() }
        }
    }

    private fun captureViewportAnchor(): ViewportAnchor? {
        val list = adapter?.currentList.orEmpty()
        if (list.isEmpty()) return null
        val layoutManager = binding.recyclerMessages.layoutManager as? LinearLayoutManager ?: return null
        val firstVisible = layoutManager.findFirstVisibleItemPosition()
        if (firstVisible < 0 || firstVisible > list.lastIndex) return null
        val anchorView = layoutManager.findViewByPosition(firstVisible)
        val anchorOffset = (anchorView?.top ?: binding.recyclerMessages.paddingTop) - binding.recyclerMessages.paddingTop
        return ViewportAnchor(
            messageId = list[firstVisible].id,
            index = firstVisible,
            offsetPx = anchorOffset
        )
    }

    private fun applySavedScrollPosition(
        anchorMessageId: String?,
        fallbackIndex: Int,
        anchorOffsetPx: Int
    ) {
        val layoutManager = binding.recyclerMessages.layoutManager as? LinearLayoutManager ?: return
        val maxIndex = (adapter?.currentList?.lastIndex ?: -1)
        if (maxIndex < 0) return

        val normalizedOffset = anchorOffsetPx.coerceIn(
            -binding.recyclerMessages.height.coerceAtLeast(1),
            binding.recyclerMessages.height.coerceAtLeast(1)
        )

        fun resolveTargetIndex(): Int {
            val list = adapter?.currentList.orEmpty()
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

        isApplyingViewportAnchor = true
        applyNow()
        binding.recyclerMessages.post {
            applyNow()
            val capturedAnchor = captureViewportAnchor()
            if (isAtBottom()) {
                keepViewportStableOnUpdates = false
                lockedViewportAnchor = null
                userManuallyScrolledAwayFromBottom = false
            } else {
                keepViewportStableOnUpdates = true
                lockedViewportAnchor = capturedAnchor ?: ViewportAnchor(
                    messageId = anchorMessageId,
                    index = fallbackIndex,
                    offsetPx = normalizedOffset
                )
                userManuallyScrolledAwayFromBottom = true
            }
            isApplyingViewportAnchor = false
            updateScrollToBottomButton()
        }
    }

    private fun findFirstUnreadIndex(messages: List<ChatMessage>): Int {
        val myUid = currentUser?.uid.orEmpty()
        if (myUid.isBlank()) return -1

        return messages.indexOfFirst { message ->
            message.senderId != myUid && message.readBy.none { readerId -> readerId == myUid }
        }
    }

    private fun markVisibleIncomingAsReadIfNeeded() {
        val user = currentUser ?: return
        val readUpToTimestampMs = latestVisibleUnreadIncomingTimestamp(myUid = user.uid)
        if (readUpToTimestampMs <= 0L) return
        if (readUpToTimestampMs <= maxReadAckedTimestampMs) return

        val inFlightTimestampMs = readMarkInFlightUpToTimestampMs
        if (inFlightTimestampMs != null && readUpToTimestampMs <= inFlightTimestampMs) return
        readMarkInFlightUpToTimestampMs = readUpToTimestampMs

        lifecycleScope.launch {
            runCatching {
                AppContainer.chatRepository.markChatRead(
                    chatId = chatId,
                    readUpToTimestampMs = readUpToTimestampMs
                )
            }.onSuccess {
                maxReadAckedTimestampMs = max(maxReadAckedTimestampMs, readUpToTimestampMs)
                if (readMarkInFlightUpToTimestampMs == readUpToTimestampMs) {
                    readMarkInFlightUpToTimestampMs = null
                }
                MessageNotificationHelper.cancelChatNotification(this@ChatActivity, chatId)
                markVisibleIncomingAsReadIfNeeded()
            }.onFailure {
                if (readMarkInFlightUpToTimestampMs == readUpToTimestampMs) {
                    readMarkInFlightUpToTimestampMs = null
                }
            }
        }
    }

    private fun latestVisibleUnreadIncomingTimestamp(myUid: String): Long {
        if (myUid.isBlank()) return 0L
        val messages = adapter?.currentList.orEmpty()
        if (messages.isEmpty()) return 0L

        val layoutManager = binding.recyclerMessages.layoutManager as? LinearLayoutManager ?: return 0L
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

    private fun scrollToMessageIndex(index: Int) {
        val layoutManager = binding.recyclerMessages.layoutManager as? LinearLayoutManager ?: return
        val lastIndex = adapter?.currentList?.lastIndex ?: return
        if (lastIndex < 0) return

        val safeIndex = index.coerceIn(0, lastIndex)
        val anchorIndex = (safeIndex - 1).coerceAtLeast(0)
        layoutManager.scrollToPositionWithOffset(anchorIndex, 0)
        updateScrollToBottomButton()
    }

    private fun shouldAutoScrollToBottom(): Boolean {
        if (isApplyingViewportAnchor) return false
        if (keepViewportStableOnUpdates) return false
        if (isAtBottom()) {
            userManuallyScrolledAwayFromBottom = false
            return true
        }
        return !userManuallyScrolledAwayFromBottom
    }

    private fun isAtBottom(): Boolean {
        val layoutManager = binding.recyclerMessages.layoutManager as? LinearLayoutManager ?: return true
        val total = adapter?.currentList?.size ?: 0
        if (total <= 1) return true

        val lastVisible = layoutManager.findLastVisibleItemPosition()
        if (lastVisible < 0) return true

        return lastVisible >= total - 1
    }

    private fun scrollToBottom(animated: Boolean) {
        val lastIndex = (adapter?.currentList?.lastIndex ?: -1)
        if (lastIndex < 0) return
        userManuallyScrolledAwayFromBottom = false
        if (animated) {
            binding.recyclerMessages.smoothScrollToPosition(lastIndex)
        } else {
            binding.recyclerMessages.scrollToPosition(lastIndex)
        }
        updateScrollToBottomButton()
    }

    private fun updateScrollToBottomButton() {
        val listSize = adapter?.currentList?.size ?: 0
        if (listSize <= 1) {
            binding.btnScrollToBottom.isVisible = false
            return
        }
        val layoutManager = binding.recyclerMessages.layoutManager as? LinearLayoutManager
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

    private fun persistCurrentScrollState() {
        val anchor = captureViewportAnchor() ?: lockedViewportAnchor ?: return
        val atBottom = if (keepViewportStableOnUpdates || isApplyingViewportAnchor) {
            false
        } else {
            isAtBottom()
        }

        AppContainer.sessionStore.saveChatScrollState(
            chatId = chatId,
            anchorMessageId = anchor.messageId,
            anchorIndex = anchor.index,
            anchorOffsetPx = anchor.offsetPx,
            wasAtBottom = atBottom
        )
    }

    private fun sendMessage() {
        val user = currentUser ?: return
        if (isAnyRecordingInProgress()) {
            Toast.makeText(this, "Finish recording first", Toast.LENGTH_SHORT).show()
            return
        }

        if (isEditingMessage()) {
            saveInlineEditedMessage()
            return
        }

        if (selectedPhotoUris.isNotEmpty()) {
            sendPhotoDrafts(user)
            return
        }

        val rawText = binding.etMessage.text?.toString().orEmpty().trim()
        if (rawText.isBlank()) return

        val replyTarget = replyTargetMessage
        val optimisticMessage = buildOptimisticTextMessage(
            user = user,
            text = rawText,
            replyTarget = replyTarget
        )
        appendOutgoingOverlayMessage(optimisticMessage)
        binding.etMessage.text?.clear()
        clearReplyTarget()

        lifecycleScope.launch {
            binding.btnSend.isEnabled = false
            runCatching {
                AppContainer.chatRepository.sendMessage(
                    chatId = chatId,
                    text = rawText,
                    replyToMessageId = sanitizeReplyMessageId(replyTarget)
                )
            }.onSuccess { confirmedMessage ->
                markOverlayMessageSent(optimisticMessage.id, confirmedMessage)
                MessageNotificationHelper.cancelChatNotification(this@ChatActivity, chatId)
            }.onFailure { throwable ->
                markOverlayMessageFailed(optimisticMessage.id)
                Toast.makeText(
                    this@ChatActivity,
                    throwable.message ?: getString(R.string.send_message_error),
                    Toast.LENGTH_SHORT
                ).show()
            }
            binding.btnSend.isEnabled = true
        }
    }
    private fun uploadGroupAvatar(uri: Uri) {
        if (!isGroupChat) return
        if (!isCurrentUserGroupCreator()) {
            Toast.makeText(
                this,
                "Менять аватар группы может только создатель",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        lifecycleScope.launch {
            binding.progress.isVisible = true
            val result = runCatching {
                val bytes = AvatarProcessor.compressForUpload(this@ChatActivity, uri)
                AppContainer.chatRepository.uploadGroupAvatar(chatId = chatId, imageBytes = bytes)
            }
            binding.progress.isVisible = false

            result.onSuccess {
                refreshGroupInfoSilently()
                Toast.makeText(
                    this@ChatActivity,
                    getString(R.string.avatar_saved),
                    Toast.LENGTH_SHORT
                ).show()
            }.onFailure { throwable ->
                Toast.makeText(
                    this@ChatActivity,
                    throwable.message ?: getString(R.string.avatar_save_error),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun isCurrentUserGroupCreator(info: GroupInfo? = cachedGroupInfo): Boolean {
        val creatorUid = info?.createdByUid?.trim().orEmpty()
        val myUid = currentUser?.uid?.trim().orEmpty()
        return creatorUid.isNotBlank() && myUid.isNotBlank() && creatorUid == myUid
    }

    private fun setupSwipeToReplyIfNeeded() {
        swipeToReplyHelper?.attachToRecyclerView(null)
        replySwipeTriggeredInGesture = false

        val callback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float = 1f

            override fun getSwipeEscapeVelocity(defaultValue: Float): Float = Float.MAX_VALUE

            override fun getSwipeVelocityThreshold(defaultValue: Float): Float = Float.MAX_VALUE

            override fun onChildDraw(
                c: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                if (actionState != ItemTouchHelper.ACTION_STATE_SWIPE) {
                    super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
                    return
                }
                val maxShift = viewHolder.itemView.width * SWIPE_REPLY_MAX_SHIFT_FRACTION
                val clampedDx = dX.coerceIn(-maxShift, 0f)
                viewHolder.itemView.translationX = clampedDx

                if (isCurrentlyActive) {
                    val triggerShift = viewHolder.itemView.width * SWIPE_REPLY_TRIGGER_FRACTION
                    if (!replySwipeTriggeredInGesture && -clampedDx >= triggerShift) {
                        val position = viewHolder.bindingAdapterPosition
                            .takeIf { it != RecyclerView.NO_POSITION }
                            ?: viewHolder.absoluteAdapterPosition
                        val message = adapter?.currentList?.getOrNull(position)
                        if (message != null) {
                            setReplyTarget(message)
                            replySwipeTriggeredInGesture = true
                        }
                    }
                } else {
                    replySwipeTriggeredInGesture = false
                    viewHolder.itemView.animate().cancel()
                    viewHolder.itemView.animate()
                        .translationX(0f)
                        .setDuration(90L)
                        .start()
                }
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                viewHolder.itemView.translationX = 0f
                replySwipeTriggeredInGesture = false
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                replySwipeTriggeredInGesture = false
                viewHolder.itemView.animate().cancel()
                viewHolder.itemView.animate()
                    .translationX(0f)
                    .setDuration(90L)
                    .start()
            }
        }

        swipeToReplyHelper = ItemTouchHelper(callback).also { helper ->
            helper.attachToRecyclerView(binding.recyclerMessages)
        }
    }

    private fun setupEmojiPanel() {
        emojiAdapter = EmojiAdapter { emoji -> insertEmoji(emoji) }
        binding.recyclerEmoji.layoutManager = GridLayoutManager(this, EMOJI_GRID_SPAN_COUNT)
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
        emojiCategories = TelegramEmojiCatalog.load(this)
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

    private fun hasDraftText(): Boolean {
        return binding.etMessage.text?.toString()?.trim().orEmpty().isNotEmpty()
    }

    private fun hasDraftPhotos(): Boolean {
        return selectedPhotoUris.isNotEmpty()
    }

    private fun isEditingMessage(): Boolean {
        return inlineEditState != null
    }

    private fun isEditingPhotoMessage(): Boolean {
        return inlineEditState?.message?.type == MessageType.IMAGE
    }

    private fun isEditingTextMessage(): Boolean {
        return inlineEditState?.message?.type == MessageType.TEXT
    }

    private fun updateInlineEditUi() {
        val editState = inlineEditState
        binding.editContainer.isVisible = editState != null && editState.message.type != MessageType.IMAGE
        if (editState == null) {
            binding.tvEditTitle.text = ""
            binding.tvEditText.text = ""
            binding.btnCancelEdit.isEnabled = false
            return
        }

        binding.btnCancelEdit.isEnabled = true
        binding.tvEditTitle.text = when (editState.message.type) {
            MessageType.IMAGE -> "Редактирование фото"
            MessageType.VOICE -> "Редактирование голосового"
            MessageType.VIDEO -> "Редактирование видео"
            MessageType.TEXT -> "Редактирование сообщения"
        }
        binding.tvEditText.text = when (editState.message.type) {
            MessageType.IMAGE -> {
                val caption = editState.message.text.replace('\n', ' ')
                    .trim()
                    .takeIf { it.isNotBlank() && it != PHOTO_PREVIEW_TEXT }
                caption ?: "Фото: ${resolveMessagePhotoUrls(editState.message).size}"
            }

            MessageType.VOICE -> {
                editState.message.text.replace('\n', ' ').trim().ifBlank { VOICE_PREVIEW_TEXT }
            }

            MessageType.VIDEO -> {
                editState.message.text.replace('\n', ' ').trim().ifBlank { VIDEO_PREVIEW_TEXT }
            }

            MessageType.TEXT -> {
                editState.message.text.replace('\n', ' ').trim().ifBlank { "Текст сообщения" }
            }
        }
    }

    private fun updateComposerActionState() {
        val showSend = (isEditingMessage() || hasDraftText() || hasDraftPhotos()) && !isAnyRecordingInProgress()
        binding.btnSend.isVisible = showSend
        binding.btnVoice.isVisible = !showSend
        binding.btnAttach.isVisible = !isAnyRecordingInProgress() && (!isEditingMessage() || isEditingPhotoMessage())
    }

    private fun addPhotoDrafts(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val existingUris = selectedPhotoUris.mapTo(hashSetOf()) { it.toString() }
        var added = 0
        for (uri in uris) {
            val editedExistingCount = if (isEditingPhotoMessage()) {
                resolveMessagePhotoUrls(inlineEditState!!.message).count { it !in removedEditedPhotoUrls }
            } else {
                0
            }
            if (selectedPhotoUris.size + editedExistingCount >= MAX_PHOTO_DRAFTS) break
            val key = uri.toString().trim()
            if (key.isBlank() || key in existingUris) continue
            selectedPhotoUris += uri
            existingUris += key
            added += 1
        }
        if (added == 0) return
        val totalDrafts = if (isEditingPhotoMessage()) currentEditedPhotoSources().size else selectedPhotoUris.size
        if (totalDrafts >= MAX_PHOTO_DRAFTS && uris.size > added) {
            Toast.makeText(this, "Можно выбрать до 10 фото", Toast.LENGTH_SHORT).show()
        }
        updatePhotoDraftUi()
        updateComposerActionState()
    }

    private fun clearPhotoDrafts() {
        if (isEditingPhotoMessage()) {
            cancelInlineEdit()
            return
        }
        if (selectedPhotoUris.isEmpty()) return
        selectedPhotoUris.clear()
        updatePhotoDraftUi()
        updateComposerActionState()
    }

    private fun updatePhotoDraftUi() {
        val editState = inlineEditState
        val previewItems = when {
            editState?.message?.type == MessageType.IMAGE -> {
                val existing = resolveMessagePhotoUrls(editState.message)
                    .filterNot { it in removedEditedPhotoUrls }
                    .map { PhotoDraftPreview(source = it, existingUrl = it, selectedUriIndex = null) }
                val selected = selectedPhotoUris.mapIndexed { index, uri ->
                    PhotoDraftPreview(source = uri.toString(), existingUrl = null, selectedUriIndex = index)
                }
                existing + selected
            }
            selectedPhotoUris.isNotEmpty() -> selectedPhotoUris.mapIndexed { index, uri ->
                PhotoDraftPreview(source = uri.toString(), existingUrl = null, selectedUriIndex = index)
            }
            else -> emptyList()
        }
        val hasDrafts = previewItems.isNotEmpty() || editState?.message?.type == MessageType.IMAGE
        binding.photoDraftContainer.isVisible = hasDrafts
        binding.photoDraftStrip.removeAllViews()
        if (!hasDrafts) return

        binding.tvPhotoDraftCount.text = when {
            editState?.message?.type == MessageType.IMAGE -> "Фото: ${previewItems.size}"
            selectedPhotoUris.isNotEmpty() -> "Выбрано фото: ${selectedPhotoUris.size}"
            else -> ""
        }
        binding.btnClearPhotoDrafts.isVisible = selectedPhotoUris.isNotEmpty() || editState?.message?.type == MessageType.IMAGE
        previewItems.forEachIndexed { index, preview ->
            val thumbnail = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(dpToPx(56f), dpToPx(56f))
                background = getDrawable(R.drawable.bg_message_image)
                clipToOutline = true
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = "photo_draft"
                bindPreviewPhoto(this, preview.source)
            }
            val removeButton = TextView(this).apply {
                layoutParams = FrameLayout.LayoutParams(dpToPx(22f), dpToPx(22f)).apply {
                    gravity = android.view.Gravity.TOP or android.view.Gravity.END
                }
                background = getDrawable(R.drawable.bg_video_play_button)
                gravity = android.view.Gravity.CENTER
                text = "\u2715"
                setTextColor(Color.WHITE)
                textSize = 11f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                contentDescription = "remove_photo_draft"
                setOnClickListener { removePhotoDraftPreview(preview) }
            }
            val holder = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(62f), dpToPx(62f)).apply {
                    marginEnd = if (index == previewItems.lastIndex) 0 else dpToPx(4f)
                }
                addView(thumbnail)
                addView(removeButton)
            }
            binding.photoDraftStrip.addView(holder)
        }
    }

    private fun removePhotoDraftPreview(preview: PhotoDraftPreview) {
        val selectedIndex = preview.selectedUriIndex
        if (selectedIndex != null) {
            if (selectedIndex in selectedPhotoUris.indices) {
                selectedPhotoUris.removeAt(selectedIndex)
            }
        } else {
            preview.existingUrl?.let { removedEditedPhotoUrls += it }
        }
        updatePhotoDraftUi()
        updateComposerActionState()
    }

    private fun currentEditedPhotoSources(): List<String> {
        val message = inlineEditState?.message ?: return emptyList()
        if (message.type != MessageType.IMAGE) return emptyList()
        return resolveMessagePhotoUrls(message)
            .filterNot { it in removedEditedPhotoUrls } +
            selectedPhotoUris.map(Uri::toString)
    }

    private fun bindPreviewPhoto(imageView: ImageView, source: String) {
        val safeSource = source.trim()
        if (safeSource.isBlank()) {
            imageView.setImageDrawable(null)
            return
        }
        ImageThumbnailLoader.bind(imageView, safeSource, dpToPx(112f))
    }

    private fun isEmojiPanelActiveOrPending(): Boolean {
        return binding.emojiContainer.isVisible || pendingEmojiShowTransition
    }

    private fun syncEmojiToggleIcon() {
        val emojiActiveOrPending =
            (binding.emojiContainer.isVisible || pendingEmojiShowTransition) && !pendingKeyboardShowTransition
        val target = getString(
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
        if (isEmojiPanelActiveOrPending()) {
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

    private fun hideEmojiPanel(showKeyboard: Boolean) {
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
        emojiTransitionHandler.postDelayed(runnable, EMOJI_OPEN_FALLBACK_DELAY_MS)
    }

    private fun cancelPendingEmojiOpenFallback() {
        pendingEmojiOpenFallbackRunnable?.let { emojiTransitionHandler.removeCallbacks(it) }
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
        emojiTransitionHandler.postDelayed(runnable, KEYBOARD_HIDE_EMOJI_FALLBACK_DELAY_MS)
    }

    private fun cancelPendingKeyboardHideEmojiFallback() {
        pendingKeyboardHideEmojiFallbackRunnable?.let { emojiTransitionHandler.removeCallbacks(it) }
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

        val delta = abs(target - appliedInputBarInsetPx)
        if (delta <= dpToPx(KEYBOARD_INSET_SMALL_STEP_DP)) {
            // For already-smooth frame-by-frame inset updates, follow them directly.
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
        val fallback = (resources.displayMetrics.density * DEFAULT_EMOJI_PANEL_HEIGHT_DP).toInt()
        return fallback.coerceAtLeast((resources.displayMetrics.density * 220f).toInt())
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

    private fun dpToPx(dp: Float): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun restoreLastKeyboardHeight() {
        val prefs = getSharedPreferences(UI_PREFS_NAME, MODE_PRIVATE)
        lastKnownKeyboardHeightPx = prefs.getInt(PREF_LAST_KEYBOARD_HEIGHT_PX, 0)
    }

    private fun persistKeyboardHeight(heightPx: Int) {
        getSharedPreferences(UI_PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putInt(PREF_LAST_KEYBOARD_HEIGHT_PX, heightPx)
            .apply()
    }

    private fun insertEmoji(emoji: String) {
        val editable = binding.etMessage.text ?: return
        val start = binding.etMessage.selectionStart.coerceAtLeast(0)
        val end = binding.etMessage.selectionEnd.coerceAtLeast(0)
        val from = min(start, end)
        val to = max(start, end)
        editable.replace(from, to, emoji)
    }

    private fun hideKeyboard() {
        val manager = getSystemService(InputMethodManager::class.java) ?: return
        manager.hideSoftInputFromWindow(binding.etMessage.windowToken, 0)
    }

    private fun showKeyboard() {
        val manager = getSystemService(InputMethodManager::class.java) ?: return
        binding.etMessage.post {
            manager.showSoftInput(binding.etMessage, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun handleVoiceButtonTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                voiceButtonPressed = true
                longPressTriggered = false
                voiceTouchDownX = event.x
                voiceTouchDownY = event.y
                voiceButtonLongPressHandler.removeCallbacks(voiceLongPressRunnable)
                voiceButtonLongPressHandler.postDelayed(
                    voiceLongPressRunnable,
                    ViewConfiguration.getLongPressTimeout().toLong()
                )
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!longPressTriggered) {
                    val slop = ViewConfiguration.get(this).scaledTouchSlop
                    val movedTooMuch =
                        abs(event.x - voiceTouchDownX) > slop || abs(event.y - voiceTouchDownY) > slop
                    if (movedTooMuch) {
                        voiceButtonPressed = false
                        voiceButtonLongPressHandler.removeCallbacks(voiceLongPressRunnable)
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val wasLongPress = longPressTriggered
                voiceButtonPressed = false
                longPressTriggered = false
                voiceButtonLongPressHandler.removeCallbacks(voiceLongPressRunnable)
                if (wasLongPress) {
                    stopAnyActiveRecording(send = true)
                } else if (!isAnyRecordingInProgress()) {
                    view.performClick()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                val wasLongPress = longPressTriggered
                voiceButtonPressed = false
                longPressTriggered = false
                voiceButtonLongPressHandler.removeCallbacks(voiceLongPressRunnable)
                if (wasLongPress) {
                    stopAnyActiveRecording(send = false)
                }
                return true
            }
        }
        return false
    }

    private fun isAnyRecordingInProgress(): Boolean =
        isVoiceRecording || isVideoRecording || voiceRecorder != null || videoRecording != null

    private fun toggleRecordMode() {
        if (isAnyRecordingInProgress()) return
        currentRecordMode = when (currentRecordMode) {
            RecordMode.VOICE -> RecordMode.VIDEO
            RecordMode.VIDEO -> RecordMode.VOICE
        }
        if (currentRecordMode == RecordMode.VOICE) {
            releaseVideoCapture()
        }
        updateRecordingUi(recording = false, elapsedSec = 0)
        val modeToast = if (currentRecordMode == RecordMode.VOICE) {
            "Режим: голосовое"
        } else {
            "Режим: видеосообщение"
        }
        Toast.makeText(this, modeToast, Toast.LENGTH_SHORT).show()
    }

    private fun switchVideoCamera() {
        if (currentRecordMode != RecordMode.VIDEO) return
        if (isVideoRecording || videoRecording != null) {
            pendingCameraSwitchAfterFinalize = true
            stopVideoRecording(send = false)
            return
        }
        if (!switchVideoCameraNow()) {
            ensureVideoCapture(
                onReady = { _ ->
                    if (!switchVideoCameraNow()) {
                        Toast.makeText(this, "Не удалось переключить камеру", Toast.LENGTH_SHORT).show()
                    }
                },
                onError = {
                    Toast.makeText(this, "Не удалось переключить камеру", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    private fun switchVideoCameraNow(): Boolean {
        val provider = cameraProvider ?: return false
        val preview = videoPreviewUseCase ?: return false
        val capture = videoCapture ?: return false
        val nextFacing = if (videoCameraFacing == CameraSelector.LENS_FACING_FRONT) {
            CameraSelector.LENS_FACING_BACK
        } else {
            CameraSelector.LENS_FACING_FRONT
        }
        val selector = CameraSelector.Builder().requireLensFacing(nextFacing).build()
        return runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(this, selector, preview, capture)
            videoCameraFacing = nextFacing
            true
        }.getOrElse {
            false
        }
    }

    private fun ensureAudioPermissionAndRecord() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            startVoiceRecordingInternal()
        } else {
            pendingPermissionRecordingType = RecordingType.VOICE
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun ensureVideoPermissionsAndRecord() {
        val cameraGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        val audioGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (cameraGranted && audioGranted) {
            startVideoRecordingInternal()
        } else {
            pendingPermissionRecordingType = RecordingType.VIDEO
            videoPermissionsLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
                )
            )
        }
    }

    private fun stopAnyActiveRecording(send: Boolean) {
        when {
            isVoiceRecording || voiceRecorder != null -> stopVoiceRecording(send)
            isVideoRecording || videoRecording != null -> stopVideoRecording(send)
        }
    }

    private fun startVoiceRecordingInternal() {
        if (isAnyRecordingInProgress() || !voiceButtonPressed) return
        hideEmojiPanel(showKeyboard = false)

        val tempFile = File(cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        val result = runCatching {
            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64_000)
                setAudioSamplingRate(22_050)
                setOutputFile(tempFile.absolutePath)
                prepare()
                start()
            }
        }

        result.onSuccess {
            voiceRecorder = recorder
            voiceRecordFile = tempFile
            recordingStartedAtMs = System.currentTimeMillis()
            isVoiceRecording = true
            recordingUiHandler.removeCallbacks(recordingTicker)
            recordingUiHandler.post(recordingTicker)
            updateRecordingUi(recording = true, elapsedSec = 0)
        }.onFailure {
            runCatching { recorder.release() }
            runCatching { tempFile.delete() }
            Toast.makeText(this, "Не удалось начать запись голоса", Toast.LENGTH_SHORT).show()
            updateRecordingUi(recording = false, elapsedSec = 0)
        }
    }

    private fun stopVoiceRecording(send: Boolean) {
        val recorder = voiceRecorder
        val file = voiceRecordFile
        if (recorder == null || file == null) {
            isVoiceRecording = false
            voiceRecorder = null
            voiceRecordFile = null
            recordingStartedAtMs = 0L
            updateRecordingUi(recording = false, elapsedSec = 0)
            return
        }

        val elapsedSec = ((System.currentTimeMillis() - recordingStartedAtMs) / 1000L).toInt().coerceAtLeast(0)
        isVoiceRecording = false
        recordingUiHandler.removeCallbacks(recordingTicker)
        voiceRecorder = null
        voiceRecordFile = null
        recordingStartedAtMs = 0L

        runCatching { recorder.stop() }
        runCatching { recorder.release() }
        updateRecordingUi(recording = false, elapsedSec = 0)

        if (!send) {
            runCatching { file.delete() }
            return
        }

        if (!file.exists() || file.length() <= 0L) {
            runCatching { file.delete() }
            Toast.makeText(this, "Не удалось сохранить голосовое сообщение", Toast.LENGTH_SHORT).show()
            return
        }

        uploadAndSendVoice(file, elapsedSec.coerceAtMost(MAX_VOICE_RECORD_DURATION_SEC))
    }

    @SuppressLint("MissingPermission")
    private fun startVideoRecordingInternal() {
        if (isAnyRecordingInProgress() || !voiceButtonPressed) return
        hideEmojiPanel(showKeyboard = false)
        binding.videoRecordingContainer.isVisible = true

        ensureVideoCapture(
            onReady = { capture ->
                val tempFile = File(cacheDir, "video_${System.currentTimeMillis()}.mp4")
                val outputOptions = FileOutputOptions.Builder(tempFile).build()
                pendingVideoSendAfterStop = true
                videoRecordFile = tempFile

                videoRecording = capture.output
                    .prepareRecording(this, outputOptions)
                    .withAudioEnabled()
                    .start(ContextCompat.getMainExecutor(this)) { event ->
                        when (event) {
                            is VideoRecordEvent.Start -> {
                                recordingStartedAtMs = System.currentTimeMillis()
                                isVideoRecording = true
                                recordingUiHandler.removeCallbacks(recordingTicker)
                                recordingUiHandler.post(recordingTicker)
                                updateRecordingUi(recording = true, elapsedSec = 0)
                            }

                            is VideoRecordEvent.Finalize -> {
                                handleVideoFinalize(event)
                            }
                        }
                    }
            },
            onError = {
                binding.videoRecordingContainer.isVisible = false
                Toast.makeText(this, "Не удалось запустить видеозапись", Toast.LENGTH_SHORT).show()
                updateRecordingUi(recording = false, elapsedSec = 0)
            }
        )
    }

    private fun stopVideoRecording(send: Boolean) {
        pendingVideoSendAfterStop = send
        val recording = videoRecording
        if (recording == null) {
            isVideoRecording = false
            if (!send) {
                runCatching { videoRecordFile?.delete() }
                videoRecordFile = null
            }
            recordingStartedAtMs = 0L
            updateRecordingUi(recording = false, elapsedSec = 0)
            if (pendingCameraSwitchAfterFinalize) {
                pendingCameraSwitchAfterFinalize = false
                switchVideoCameraNow()
            }
            return
        }
        recording.stop()
    }

    private fun handleVideoFinalize(event: VideoRecordEvent.Finalize) {
        val file = videoRecordFile
        val shouldSend = pendingVideoSendAfterStop
        val durationSec = ((System.currentTimeMillis() - recordingStartedAtMs) / 1000L).toInt().coerceAtLeast(0)

        isVideoRecording = false
        recordingUiHandler.removeCallbacks(recordingTicker)
        videoRecording = null
        videoRecordFile = null
        recordingStartedAtMs = 0L
        updateRecordingUi(recording = false, elapsedSec = 0)

        if (pendingCameraSwitchAfterFinalize) {
            pendingCameraSwitchAfterFinalize = false
            runCatching { file?.delete() }
            if (!switchVideoCameraNow()) {
                Toast.makeText(this, "Не удалось переключить камеру", Toast.LENGTH_SHORT).show()
            }
            if (voiceButtonPressed) {
                startVideoRecordingInternal()
            }
            return
        }

        if (event.hasError()) {
            runCatching { file?.delete() }
            Toast.makeText(this, "Ошибка видеозаписи", Toast.LENGTH_SHORT).show()
            return
        }

        if (!shouldSend) {
            runCatching { file?.delete() }
            return
        }

        if (file == null || !file.exists() || file.length() <= 0L) {
            runCatching { file?.delete() }
            Toast.makeText(this, "Не удалось сохранить видеосообщение", Toast.LENGTH_SHORT).show()
            return
        }

        uploadAndSendVideo(file, durationSec.coerceAtMost(MAX_VIDEO_RECORD_DURATION_SEC))
    }

    private fun ensureVideoCapture(
        onReady: (VideoCapture<Recorder>) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val existingCapture = videoCapture
        if (existingCapture != null) {
            onReady(existingCapture)
            return
        }

        val future = cameraProviderFuture ?: ProcessCameraProvider.getInstance(this).also {
            cameraProviderFuture = it
        }

        future.addListener(
            {
                val provider = runCatching { future.get() }.getOrElse { throwable ->
                    onError(throwable)
                    return@addListener
                }
                runCatching {
                    val preview = videoPreviewUseCase ?: Preview.Builder().build().apply {
                        setSurfaceProvider(binding.videoRecordingPreview.surfaceProvider)
                    }
                    val recorder = Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.SD))
                        .build()
                    val capture = VideoCapture.withOutput(recorder)
                    bindVideoUseCases(provider, preview, capture)
                    cameraProvider = provider
                    videoPreviewUseCase = preview
                    videoCapture = capture
                    capture
                }.onSuccess(onReady).onFailure(onError)
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    private fun bindVideoUseCases(
        provider: ProcessCameraProvider,
        preview: Preview,
        capture: VideoCapture<Recorder>
    ) {
        val preferredSelector = CameraSelector.Builder()
            .requireLensFacing(videoCameraFacing)
            .build()
        try {
            provider.unbindAll()
            provider.bindToLifecycle(this, preferredSelector, preview, capture)
            return
        } catch (_: Throwable) {
        }

        val fallbackFacing = if (videoCameraFacing == CameraSelector.LENS_FACING_FRONT) {
            CameraSelector.LENS_FACING_BACK
        } else {
            CameraSelector.LENS_FACING_FRONT
        }
        val fallbackSelector = CameraSelector.Builder()
            .requireLensFacing(fallbackFacing)
            .build()
        provider.unbindAll()
        provider.bindToLifecycle(this, fallbackSelector, preview, capture)
        videoCameraFacing = fallbackFacing
    }

    private fun releaseVideoCapture() {
        videoRecording?.stop()
        videoRecording = null
        pendingCameraSwitchAfterFinalize = false
        runCatching { cameraProvider?.unbindAll() }
        videoPreviewUseCase = null
        videoCapture = null
        binding.videoRecordingContainer.isVisible = false
    }

    private fun uploadAndSendVoice(file: File, durationSec: Int) {
        val user = currentUser ?: run {
            runCatching { file.delete() }
            return
        }
        val replyTarget = replyTargetMessage
        val optimisticMessage = buildOptimisticVoiceMessage(
            user = user,
            durationSec = durationSec,
            replyTarget = replyTarget
        )
        appendOutgoingOverlayMessage(optimisticMessage)
        clearReplyTarget()

        lifecycleScope.launch {
            binding.btnVoice.isEnabled = false
            binding.btnSend.isEnabled = false
            binding.progress.isVisible = true

            runCatching {
                val voiceUrl = AppContainer.chatRepository.uploadVoice(
                    chatId = chatId,
                    voiceBytes = file.readBytes(),
                    fileName = file.name
                )
                AppContainer.chatRepository.sendVoiceMessage(
                    chatId = chatId,
                    voiceUrl = voiceUrl,
                    durationSec = durationSec,
                    fallbackText = VOICE_PREVIEW_TEXT,
                    replyToMessageId = sanitizeReplyMessageId(replyTarget)
                )
            }.onSuccess { confirmedMessage ->
                markOverlayMessageSent(optimisticMessage.id, confirmedMessage)
                MessageNotificationHelper.cancelChatNotification(this@ChatActivity, chatId)
            }.onFailure { throwable ->
                markOverlayMessageFailed(optimisticMessage.id)
                Toast.makeText(
                    this@ChatActivity,
                    throwable.message ?: getString(R.string.send_message_error),
                    Toast.LENGTH_SHORT
                ).show()
            }

            runCatching { file.delete() }
            binding.progress.isVisible = false
            binding.btnVoice.isEnabled = true
            binding.btnSend.isEnabled = true
        }
    }

    private fun saveInlineEditedMessage() {
        val editState = inlineEditState ?: return
        val message = editState.message
        val newText = binding.etMessage.text?.toString().orEmpty().trim()
        val replacementPhotoSources = if (message.type == MessageType.IMAGE) {
            currentEditedPhotoSources().takeIf { sources ->
                sources != resolveMessagePhotoUrls(message)
            }
        } else {
            null
        }
        if (message.type == MessageType.IMAGE && currentEditedPhotoSources().isEmpty()) {
            Toast.makeText(this, "Оставьте хотя бы одно фото", Toast.LENGTH_SHORT).show()
            return
        }
        if (message.type == MessageType.TEXT && newText.isBlank()) {
            Toast.makeText(this, getString(R.string.edit_message_error), Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            binding.btnSend.isEnabled = false
            binding.btnAttach.isEnabled = false
            runCatching {
                editMessageWithOptimisticPreview(
                    message = message,
                    newText = newText,
                    replacementPhotoSources = replacementPhotoSources
                )
            }.onSuccess {
                finishInlineEdit()
            }.onFailure { throwable ->
                Toast.makeText(
                    this@ChatActivity,
                    throwable.message ?: getString(R.string.edit_message_error),
                    Toast.LENGTH_SHORT
                ).show()
            }
            binding.btnSend.isEnabled = true
            binding.btnAttach.isEnabled = true
        }
    }

    private fun sendPhotoDrafts(user: UserProfile) {
        val replyTarget = replyTargetMessage
        val draftUris = selectedPhotoUris.toList()
        if (draftUris.isEmpty()) return

        val rawCaption = binding.etMessage.text?.toString().orEmpty().trim()

        lifecycleScope.launch {
            val draftSizes = draftUris.map { uri ->
                PhotoMessageProcessor.readDisplaySize(this@ChatActivity, uri)
            }
            val optimisticMessage = buildOptimisticImageMessage(
                user = user,
                imageUrls = draftUris.map(Uri::toString),
                imageWidths = draftSizes.map { it?.width ?: 0 },
                imageHeights = draftSizes.map { it?.height ?: 0 },
                caption = rawCaption,
                replyTarget = replyTarget
            )
            appendOutgoingOverlayMessage(optimisticMessage)
            clearReplyTarget()
            clearPhotoDrafts()
            binding.etMessage.text?.clear()

            val preparedPhotos = runCatching {
                draftUris.map { uri ->
                    PhotoMessageProcessor.prepareForUpload(this@ChatActivity, uri).let { prepared ->
                        PhotoDraftUpload(
                            bytes = prepared.bytes,
                            width = prepared.width,
                            height = prepared.height,
                            fileName = prepared.fileName
                        )
                    }
                }
            }.getOrElse { throwable ->
                markOverlayMessageFailed(optimisticMessage.id)
                Toast.makeText(
                    this@ChatActivity,
                    throwable.message ?: getString(R.string.send_message_error),
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            val uploadResult = runCatching {
                val uploadedUrls = ArrayList<String>(preparedPhotos.size)
                for (photo in preparedPhotos) {
                    val photoUrl = AppContainer.chatRepository.uploadPhoto(
                        chatId = chatId,
                        imageBytes = photo.bytes,
                        fileName = photo.fileName
                    )
                    uploadedUrls += photoUrl
                }
                val firstPhoto = preparedPhotos.first()
                AppContainer.chatRepository.sendPhotoMessage(
                    chatId = chatId,
                    photoUrls = uploadedUrls,
                    width = firstPhoto.width,
                    height = firstPhoto.height,
                    widths = preparedPhotos.map { it.width },
                    heights = preparedPhotos.map { it.height },
                    caption = rawCaption,
                    fallbackText = PHOTO_PREVIEW_TEXT,
                    replyToMessageId = sanitizeReplyMessageId(replyTarget)
                )
            }

            uploadResult.onSuccess { confirmedMessage ->
                markOverlayMessageSent(optimisticMessage.id, confirmedMessage)
                MessageNotificationHelper.cancelChatNotification(this@ChatActivity, chatId)
            }.onFailure { throwable ->
                markOverlayMessageFailed(optimisticMessage.id)
                Toast.makeText(
                    this@ChatActivity,
                    throwable.message ?: getString(R.string.send_message_error),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun uploadAndSendVideo(file: File, durationSec: Int) {
        val user = currentUser ?: run {
            runCatching { file.delete() }
            return
        }
        val replyTarget = replyTargetMessage
        val optimisticMessage = buildOptimisticVideoMessage(
            user = user,
            durationSec = durationSec,
            replyTarget = replyTarget
        )
        appendOutgoingOverlayMessage(optimisticMessage)
        clearReplyTarget()

        lifecycleScope.launch {
            binding.btnVoice.isEnabled = false
            binding.btnSend.isEnabled = false
            binding.progress.isVisible = true

            runCatching {
                val videoUrl = AppContainer.chatRepository.uploadVideo(
                    chatId = chatId,
                    videoBytes = file.readBytes(),
                    fileName = file.name
                )
                AppContainer.chatRepository.sendVideoMessage(
                    chatId = chatId,
                    videoUrl = videoUrl,
                    durationSec = durationSec,
                    fallbackText = VIDEO_PREVIEW_TEXT,
                    replyToMessageId = sanitizeReplyMessageId(replyTarget)
                )
            }.onSuccess { confirmedMessage ->
                markOverlayMessageSent(optimisticMessage.id, confirmedMessage)
                MessageNotificationHelper.cancelChatNotification(this@ChatActivity, chatId)
            }.onFailure { throwable ->
                markOverlayMessageFailed(optimisticMessage.id)
                Toast.makeText(
                    this@ChatActivity,
                    throwable.message ?: getString(R.string.send_message_error),
                    Toast.LENGTH_SHORT
                ).show()
            }

            runCatching { file.delete() }
            binding.progress.isVisible = false
            binding.btnVoice.isEnabled = true
            binding.btnSend.isEnabled = true
        }
    }

    private fun uploadAndSendPhoto(uri: Uri) {
        val user = currentUser ?: return
        val replyTarget = replyTargetMessage
        val rawCaption = binding.etMessage.text?.toString().orEmpty().trim()
        val optimisticMessage = buildOptimisticImageMessage(
            user = user,
            imageUrls = listOf(uri.toString()),
            caption = rawCaption,
            replyTarget = replyTarget
        )
        appendOutgoingOverlayMessage(optimisticMessage)
        clearReplyTarget()

        lifecycleScope.launch {
            runCatching {
                val prepared = PhotoMessageProcessor.prepareForUpload(this@ChatActivity, uri)
                val photoUrl = AppContainer.chatRepository.uploadPhoto(
                    chatId = chatId,
                    imageBytes = prepared.bytes,
                    fileName = prepared.fileName
                )
                AppContainer.chatRepository.sendPhotoMessage(
                    chatId = chatId,
                    photoUrls = listOf(photoUrl),
                    width = prepared.width,
                    height = prepared.height,
                    widths = listOf(prepared.width),
                    heights = listOf(prepared.height),
                    caption = rawCaption,
                    fallbackText = PHOTO_PREVIEW_TEXT,
                    replyToMessageId = sanitizeReplyMessageId(replyTarget)
                )
            }.onSuccess { confirmedMessage ->
                markOverlayMessageSent(optimisticMessage.id, confirmedMessage)
                MessageNotificationHelper.cancelChatNotification(this@ChatActivity, chatId)
            }.onFailure { throwable ->
                markOverlayMessageFailed(optimisticMessage.id)
                Toast.makeText(
                    this@ChatActivity,
                    throwable.message ?: getString(R.string.send_message_error),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun updateRecordingUi(recording: Boolean, elapsedSec: Int) {
        if (recording) {
            val label = if (isVideoRecording) {
                "Идет запись видео (${elapsedSec}s/${MAX_VIDEO_RECORD_DURATION_SEC}s)"
            } else {
                "Идет запись голосового"
            }
            binding.tvRecordingStatus.isVisible = true
            binding.tvRecordingStatus.text = label
            binding.btnVoice.text = "■"
            binding.videoRecordingContainer.isVisible = isVideoRecording
            binding.btnSwitchVideoCamera.isVisible = isVideoRecording
            binding.btnSend.isEnabled = false
            binding.btnEmoji.isEnabled = false
            binding.btnAttach.isEnabled = false
            binding.etMessage.isEnabled = false
        } else {
            binding.tvRecordingStatus.isVisible = false
            binding.videoRecordingContainer.isVisible = false
            binding.btnSwitchVideoCamera.isVisible = false
            binding.btnVoice.text = if (currentRecordMode == RecordMode.VOICE) {
                "\uD83C\uDFA4"
            } else {
                "\uD83C\uDFA5"
            }
            binding.btnSend.isEnabled = true
            binding.btnEmoji.isEnabled = true
            binding.btnAttach.isEnabled = true
            binding.etMessage.isEnabled = true
        }
        updateComposerActionState()
    }

    private fun showIncomingMessageActions(message: ChatMessage) {
        val options = arrayOf(
            getString(R.string.reply_message),
            getString(R.string.delete_for_me)
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.message_actions))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> setReplyTarget(message)
                    1 -> deleteMessageForMe(message)
                }
            }
            .show()
    }

    private fun showOwnMessageActions(message: ChatMessage) {
        if (message.sendState != MessageSendState.SENT) {
            Toast.makeText(this, "Сообщение еще отправляется", Toast.LENGTH_SHORT).show()
            return
        }
        if (message.type != MessageType.TEXT && message.type != MessageType.IMAGE) {
            showDeleteOwnMessageDialog(message)
            return
        }

        val options = arrayOf(
            getString(R.string.edit_message),
            getString(R.string.delete_message)
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.message_actions))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showEditMessageDialog(message)
                    1 -> showDeleteOwnMessageDialog(message)
                }
            }
            .show()
    }

    private fun showDeleteOwnMessageDialog(message: ChatMessage) {
        val options = arrayOf(
            getString(R.string.delete_for_everyone),
            getString(R.string.delete_for_me)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.delete_message))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> deleteMessageForEveryone(message)
                    1 -> deleteMessageForMe(message)
                }
            }
            .show()
    }

    private fun collectMessageIdentityIds(message: ChatMessage, serverMessageId: String? = resolveServerMessageId(message.id)): Set<String> {
        val ids = linkedSetOf<String>()
        val localId = message.id.trim()
        if (localId.isNotBlank()) {
            ids += localId
        }
        val resolvedServer = serverMessageId?.trim().orEmpty()
        if (resolvedServer.isNotBlank()) {
            ids += resolvedServer
        }
        return ids
    }

    private fun applyHiddenIdsToUi(ids: Set<String>, hidden: Boolean) {
        if (ids.isEmpty()) return
        hiddenMessageIds = if (hidden) {
            hiddenMessageIds + ids
        } else {
            hiddenMessageIds - ids
        }
        submitVisibleMessages(mergeMessagesForDisplay())
    }

    private fun animateMessageEvaporation(message: ChatMessage, onFinished: () -> Unit) {
        val list = adapter?.currentList.orEmpty()
        if (list.isEmpty()) {
            onFinished()
            return
        }
        val messageServerId = resolveServerMessageId(message.id)
        val targetIndex = list.indexOfFirst { current ->
            current.id == message.id ||
                (!messageServerId.isNullOrBlank() && resolveServerMessageId(current.id) == messageServerId)
        }
        if (targetIndex < 0) {
            onFinished()
            return
        }
        val holder = binding.recyclerMessages.findViewHolderForAdapterPosition(targetIndex)
        if (holder == null) {
            onFinished()
            return
        }
        val target = holder.itemView.findViewById<View>(R.id.messageBubble) ?: holder.itemView
        val shiftY = -(target.height.coerceAtLeast(holder.itemView.height).toFloat() * 0.18f)
        target.animate().cancel()
        target.setHasTransientState(true)
        target.animate()
            .alpha(0f)
            .scaleX(0.86f)
            .scaleY(0.86f)
            .translationY(shiftY)
            .setDuration(190L)
            .setInterpolator(FastOutSlowInInterpolator())
            .withEndAction {
                target.alpha = 1f
                target.scaleX = 1f
                target.scaleY = 1f
                target.translationY = 0f
                target.setHasTransientState(false)
                onFinished()
            }
            .start()
    }

    private fun deleteMessageForEveryone(message: ChatMessage) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.delete_message))
            .setMessage(getString(R.string.delete_message_confirm))
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.delete_short)) { _, _ ->
                val serverMessageId = resolveServerMessageId(message.id)
                if (serverMessageId == null) {
                    Toast.makeText(
                        this@ChatActivity,
                        "Сообщение еще не синхронизировано",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }
                val idsToHide = collectMessageIdentityIds(message, serverMessageId)
                animateMessageEvaporation(message) {
                    applyHiddenIdsToUi(idsToHide, hidden = true)
                    lifecycleScope.launch {
                        runCatching {
                            AppContainer.chatRepository.deleteMessage(chatId, serverMessageId)
                            AppContainer.sessionStore.removeHiddenMessage(chatId, message.id)
                            if (serverMessageId != message.id) {
                                AppContainer.sessionStore.removeHiddenMessage(chatId, serverMessageId)
                            }
                            removeOverlayMessage(message.id)
                            if (serverMessageId != message.id) {
                                removeOverlayMessage(serverMessageId)
                            }
                        }.onFailure { throwable ->
                            applyHiddenIdsToUi(idsToHide, hidden = false)
                            Toast.makeText(
                                this@ChatActivity,
                                throwable.message ?: getString(R.string.delete_message_error),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
            .show()
    }

    private fun deleteMessageForMe(message: ChatMessage) {
        val serverMessageId = resolveServerMessageId(message.id)
        val idsToHide = collectMessageIdentityIds(message, serverMessageId)
        animateMessageEvaporation(message) {
            idsToHide.forEach { AppContainer.sessionStore.hideMessage(chatId, it) }
            hiddenMessageIds = AppContainer.sessionStore.hiddenMessageIds(chatId)
            val replyTargetId = replyTargetMessage?.id
            val replyServerMessageId = resolveServerMessageId(replyTargetId)
            if (replyTargetId == message.id || (serverMessageId != null && replyServerMessageId == serverMessageId)) {
                clearReplyTarget()
            }
            submitVisibleMessages(mergeMessagesForDisplay())
            Toast.makeText(this, getString(R.string.message_deleted_for_me), Toast.LENGTH_SHORT).show()
        }
    }

    private fun setReplyTarget(message: ChatMessage) {
        if (isEditingMessage()) {
            cancelInlineEdit()
        }
        replyTargetMessage = message
        binding.replyContainer.isVisible = true
        binding.tvReplyTitle.text = getString(R.string.replying_to, message.senderName)
        binding.tvReplyText.text = replyPreviewText(message)
        bindReplyTargetImage(replyPreviewImageUrl(message))
        if (!isEmojiPanelActiveOrPending()) {
            binding.etMessage.requestFocus()
            showKeyboard()
        }
    }

    private fun clearReplyTarget() {
        replyTargetMessage = null
        binding.replyContainer.isVisible = false
        binding.tvReplyTitle.text = ""
        binding.tvReplyText.text = ""
        clearReplyTargetImage()
    }

    private fun onReplyPreviewTap(message: ChatMessage) {
        val targetId = message.replyToMessageId ?: return
        val currentList = adapter?.currentList.orEmpty()
        val targetIndex = currentList.indexOfFirst {
            it.id == targetId || resolveServerMessageId(it.id) == targetId
        }
        if (targetIndex >= 0) {
            val targetMessageId = currentList[targetIndex].id
            binding.recyclerMessages.smoothScrollToPosition(targetIndex)
            adapter?.highlightMessage(targetMessageId)
            binding.recyclerMessages.postDelayed(
                {
                    val refreshedList = adapter?.currentList.orEmpty()
                    val refreshIndex = refreshedList.indexOfFirst {
                        it.id == targetId || resolveServerMessageId(it.id) == targetId
                    }
                    if (refreshIndex >= 0) {
                        val refreshTargetId = refreshedList[refreshIndex].id
                        binding.recyclerMessages.smoothScrollToPosition(refreshIndex)
                        adapter?.highlightMessage(refreshTargetId)
                    }
                },
                180L
            )
        } else {
            Toast.makeText(this, "Source message not found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun replyPreviewText(message: ChatMessage): String {
        return when (message.type) {
            MessageType.VOICE -> VOICE_PREVIEW_TEXT
            MessageType.IMAGE -> message.text.normalizeReplyPreviewText()
                .takeIf { it.isNotBlank() && it != PHOTO_PREVIEW_TEXT }
                ?: PHOTO_PREVIEW_TEXT
            MessageType.VIDEO -> VIDEO_PREVIEW_TEXT
            MessageType.TEXT -> message.text.normalizeReplyPreviewText().ifBlank { "..." }
        }
    }

    private fun String.normalizeReplyPreviewText(): String {
        return replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
    }

    private fun replyPreviewImageUrl(message: ChatMessage): String? {
        if (message.type != MessageType.IMAGE) return null
        return resolveMessagePhotoUrls(message).firstOrNull()
    }

    private fun bindReplyTargetImage(imageUrl: String?) {
        val safeUrl = imageUrl?.trim().orEmpty()
        if (safeUrl.isBlank()) {
            clearReplyTargetImage()
            binding.tvReplyText.isVisible = true
            return
        }
        binding.tvReplyText.isVisible = shouldShowReplyTargetText()
        binding.ivReplyImage.isVisible = true
        ImageThumbnailLoader.bind(binding.ivReplyImage, safeUrl, dpToPx(84f))
    }

    private fun clearReplyTargetImage() {
        binding.ivReplyImage.isVisible = false
        binding.ivReplyImage.tag = null
        binding.ivReplyImage.setImageDrawable(null)
        binding.tvReplyText.isVisible = true
    }

    private fun shouldShowReplyTargetText(): Boolean {
        val text = binding.tvReplyText.text?.toString()?.trim().orEmpty()
        return text.isNotBlank() && text != PHOTO_PREVIEW_TEXT
    }
    private fun showChatMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        if (isGroupChat) {
            popup.menu.add(0, MENU_GROUP_INFO, 0, getString(R.string.chat_menu_group_info))
            popup.menu.add(0, MENU_GROUP_ADD_USER, 1, getString(R.string.chat_menu_add_user))
            popup.menu.add(0, MENU_GROUP_REMOVE_USER, 2, "Исключить участника")
            popup.menu.add(0, MENU_GROUP_LEAVE, 3, "Покинуть группу")
        } else {
            popup.menu.add(0, MENU_CONTACT_INFO, 0, getString(R.string.chat_menu_contact_info))
            popup.menu.add(0, MENU_SHARE_CONTACT, 1, getString(R.string.chat_menu_share_contact))
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_GROUP_INFO -> {
                    showGroupInfo()
                    true
                }

                MENU_GROUP_ADD_USER -> {
                    showAddUserToGroupDialog()
                    true
                }

                MENU_GROUP_REMOVE_USER -> {
                    showRemoveUserFromGroupDialog()
                    true
                }

                MENU_GROUP_LEAVE -> {
                    confirmLeaveGroup()
                    true
                }

                MENU_CONTACT_INFO -> {
                    showContactInfo()
                    true
                }

                MENU_SHARE_CONTACT -> {
                    shareContact()
                    true
                }

                else -> false
            }
        }
        popup.show()
    }

    private fun showGroupInfo() {
        lifecycleScope.launch {
            binding.progress.isVisible = true
            val result = runCatching {
                AppContainer.chatRepository.getGroupInfo(chatId)
            }
            binding.progress.isVisible = false

            result.onSuccess { info ->
                applyGroupInfo(info)
                showGroupInfoDialog(info)
            }.onFailure { throwable ->
                Toast.makeText(
                    this@ChatActivity,
                    throwable.message ?: getString(R.string.messages_load_error),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun showGroupInfoDialog(info: GroupInfo) {
        val view = layoutInflater.inflate(R.layout.dialog_group_info, null)

        val ivAvatar = view.findViewById<android.widget.ImageView>(R.id.ivGroupInfoAvatar)
        val tvAvatarFallback = view.findViewById<android.widget.TextView>(R.id.tvGroupInfoAvatarFallback)
        val tvTitle = view.findViewById<android.widget.TextView>(R.id.tvGroupInfoTitle)
        val tvDescription = view.findViewById<android.widget.TextView>(R.id.tvGroupInfoDescription)
        val tvMembers = view.findViewById<android.widget.TextView>(R.id.tvGroupInfoMembers)

        AvatarLoader.bind(
            imageView = ivAvatar,
            fallbackView = tvAvatarFallback,
            displayName = info.title,
            avatarUrl = info.avatarUrl
        )
        ivAvatar.setOnClickListener {
            startActivity(AvatarPreviewActivity.newIntent(this, info.title, info.avatarUrl))
        }
        tvAvatarFallback.setOnClickListener {
            startActivity(AvatarPreviewActivity.newIntent(this, info.title, info.avatarUrl))
        }
        tvTitle.text = info.title
        tvDescription.text = info.description.ifBlank { getString(R.string.group_info_description_empty) }
        tvMembers.text = if (info.members.isEmpty()) {
            getString(R.string.group_info_members_empty)
        } else {
            info.members.joinToString(separator = "\n") { member ->
                val creatorSuffix = if (!info.createdByUid.isNullOrBlank() && info.createdByUid == member.uid) {
                    " [создатель]"
                } else {
                    ""
                }
                "\u2022 ${member.displayName} (@${member.login})$creatorSuffix"
            }
        }

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.group_info_title))
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)

        if (isCurrentUserGroupCreator(info)) {
            builder.setNeutralButton(getString(R.string.change_avatar)) { _, _ ->
                pickGroupAvatar.launch("image/*")
            }
        }

        builder.show()
    }

    private fun showAddUserToGroupDialog() {
        lifecycleScope.launch {
            binding.progress.isVisible = true

            val infoResult = runCatching {
                AppContainer.chatRepository.getGroupInfo(chatId)
            }
            val usersResult = runCatching {
                AppContainer.chatRepository.listUsers()
            }

            binding.progress.isVisible = false

            val groupInfo = infoResult.getOrElse { throwable ->
                Toast.makeText(
                    this@ChatActivity,
                    throwable.message ?: getString(R.string.users_load_error),
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            applyGroupInfo(groupInfo)
            val allUsers = usersResult.getOrElse { throwable ->
                Toast.makeText(
                    this@ChatActivity,
                    throwable.message ?: getString(R.string.users_load_error),
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            val memberLogins = groupInfo.members.map { it.login.lowercase() }.toSet()
            val candidates = allUsers.filter { user ->
                !memberLogins.contains(user.login.lowercase())
            }

            if (candidates.isEmpty()) {
                Toast.makeText(
                    this@ChatActivity,
                    getString(R.string.group_add_user_empty),
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            val labels = candidates.map { "${it.displayName} (@${it.login})" }.toTypedArray()
            MaterialAlertDialogBuilder(this@ChatActivity)
                .setTitle(getString(R.string.group_add_user_title))
                .setItems(labels) { _, which ->
                    val selected = candidates.getOrNull(which) ?: return@setItems
                    lifecycleScope.launch {
                        binding.progress.isVisible = true
                        val addResult = runCatching {
                            AppContainer.chatRepository.addUserToGroup(chatId, selected.login)
                        }
                        binding.progress.isVisible = false

                        addResult.onSuccess {
                            applyGroupInfo(it)
                            Toast.makeText(
                                this@ChatActivity,
                                getString(R.string.group_add_user_success),
                                Toast.LENGTH_SHORT
                            ).show()
                        }.onFailure { throwable ->
                            Toast.makeText(
                                this@ChatActivity,
                                throwable.message ?: getString(R.string.chat_create_error),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }
    }

    private fun showRemoveUserFromGroupDialog() {
        if (!isGroupChat) return

        lifecycleScope.launch {
            binding.progress.isVisible = true
            val infoResult = runCatching {
                AppContainer.chatRepository.getGroupInfo(chatId)
            }
            binding.progress.isVisible = false

            val info = infoResult.getOrElse { throwable ->
                Toast.makeText(
                    this@ChatActivity,
                    throwable.message ?: getString(R.string.users_load_error),
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            applyGroupInfo(info)

            val myUid = currentUser?.uid.orEmpty()
            if (info.createdByUid.isNullOrBlank() || info.createdByUid != myUid) {
                Toast.makeText(
                    this@ChatActivity,
                    "Исключать участников может только создатель группы",
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            val removableUsers = info.members.filter { it.uid != myUid }
            if (removableUsers.isEmpty()) {
                Toast.makeText(
                    this@ChatActivity,
                    "В группе нет участников для исключения",
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            val labels = removableUsers.map { "${it.displayName} (@${it.login})" }.toTypedArray()
            MaterialAlertDialogBuilder(this@ChatActivity)
                .setTitle("Исключить участника")
                .setItems(labels) { _, which ->
                    val selected = removableUsers.getOrNull(which) ?: return@setItems
                    MaterialAlertDialogBuilder(this@ChatActivity)
                        .setTitle("Исключить участника")
                        .setMessage("Исключить ${selected.displayName} из группы?")
                        .setNegativeButton(getString(R.string.cancel), null)
                        .setPositiveButton(getString(R.string.delete_short)) { _, _ ->
                            lifecycleScope.launch {
                                binding.progress.isVisible = true
                                val removeResult = runCatching {
                                    AppContainer.chatRepository.removeUserFromGroup(chatId, selected.login)
                                }
                                binding.progress.isVisible = false

                                removeResult.onSuccess { updated ->
                                    applyGroupInfo(updated)
                                    Toast.makeText(
                                        this@ChatActivity,
                                        "Участник исключен",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }.onFailure { throwable ->
                                    Toast.makeText(
                                        this@ChatActivity,
                                        throwable.message ?: getString(R.string.chat_create_error),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                        .show()
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }
    }

    private fun confirmLeaveGroup() {
        if (!isGroupChat) return
        MaterialAlertDialogBuilder(this)
            .setTitle("Покинуть группу")
            .setMessage("Вы уверены, что хотите покинуть эту группу?")
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton("Выйти") { _, _ ->
                lifecycleScope.launch {
                    binding.progress.isVisible = true
                    val result = runCatching {
                        AppContainer.chatRepository.leaveGroup(chatId)
                    }
                    binding.progress.isVisible = false

                    result.onSuccess {
                        Toast.makeText(
                            this@ChatActivity,
                            "Вы покинули группу",
                            Toast.LENGTH_SHORT
                        ).show()
                        finish()
                    }.onFailure { throwable ->
                        Toast.makeText(
                            this@ChatActivity,
                            throwable.message ?: getString(R.string.chat_create_error),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .show()
    }

    private fun showContactInfo() {
        val contactName = if (chatPeerDisplayName.isNotBlank()) chatPeerDisplayName else chatTitle
        val lines = mutableListOf<String>()
        lines += getString(R.string.chat_contact_info_name, contactName)
        if (isGroupChat) {
            lines += getString(R.string.chat_contact_info_group)
            lines += getString(R.string.chat_contact_info_members, currentRecipientsCount + 1)
        } else if (chatPeerLogin.isNotBlank()) {
            lines += getString(R.string.chat_contact_info_login, chatPeerLogin)
        }
        if (chatPeerUid.isNotBlank()) {
            lines += "UID: $chatPeerUid"
        }
        lines += getString(R.string.chat_contact_info_id, chatId)

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.chat_contact_info_title))
            .setMessage(lines.joinToString(separator = "\n"))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun shareContact() {
        val contactName = if (chatPeerDisplayName.isNotBlank()) chatPeerDisplayName else chatTitle
        val text = if (isGroupChat) {
            getString(
                R.string.chat_share_contact_group_template,
                "\n",
                chatTitle,
                "\n${getString(R.string.chat_contact_info_id, chatId)}"
            )
        } else {
            val loginLine = if (chatPeerLogin.isBlank()) {
                ""
            } else {
                getString(R.string.chat_share_contact_login_line, "\n", chatPeerLogin)
            }
            getString(
                R.string.chat_share_contact_template,
                "\n$contactName",
                loginLine
            )
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.chat_share_contact_chooser)))
    }

    private fun finishChatBySwipe() {
        if (binding.emojiContainer.isVisible) {
            hideEmojiPanel(showKeyboard = false)
        }
        finish()
    }

    private fun finishChatWithBackAnimation() {
        if (binding.emojiContainer.isVisible) {
            hideEmojiPanel(showKeyboard = false)
        }
        finish()
        overridePendingTransition(R.anim.chat_stay, R.anim.chat_slide_out_right)
    }

    private fun showEditMessageDialog(message: ChatMessage) {
        beginInlineMessageEdit(message)
    }

    private fun beginInlineMessageEdit(message: ChatMessage) {
        val serverMessageId = resolveServerMessageId(message.id).orEmpty().ifBlank { message.id }
        inlineEditState = InlineEditState(
            message = message,
            serverMessageId = serverMessageId
        )
        clearReplyTarget()
        selectedPhotoUris.clear()
        removedEditedPhotoUrls.clear()
        if (binding.emojiContainer.isVisible) {
            hideEmojiPanel(showKeyboard = true)
        }

        binding.etMessage.setText(
            message.text.takeIf {
                it.isNotBlank() && !(message.type == MessageType.IMAGE && it.trim() == PHOTO_PREVIEW_TEXT)
            }.orEmpty()
        )
        binding.etMessage.setSelection(binding.etMessage.text?.length ?: 0)
        updateInlineEditUi()
        updatePhotoDraftUi()
        updateComposerActionState()
        binding.etMessage.requestFocus()
        showKeyboard()
    }

    private fun cancelInlineEdit() {
        if (!isEditingMessage()) return
        inlineEditState = null
        selectedPhotoUris.clear()
        removedEditedPhotoUrls.clear()
        pendingPhotoSelectionTarget = PhotoSelectionTarget.COMPOSE
        binding.etMessage.text?.clear()
        updateInlineEditUi()
        updatePhotoDraftUi()
        updateComposerActionState()
    }

    private fun finishInlineEdit() {
        inlineEditState = null
        selectedPhotoUris.clear()
        removedEditedPhotoUrls.clear()
        pendingPhotoSelectionTarget = PhotoSelectionTarget.COMPOSE
        binding.etMessage.text?.clear()
        updateInlineEditUi()
        updatePhotoDraftUi()
        updateComposerActionState()
    }

    private fun applyEditedPhotoSelection(uris: List<Uri>) {
        if (uris.isEmpty()) return
        addPhotoDrafts(uris)
        updatePhotoDraftUi()
        updateComposerActionState()
    }

    private suspend fun editMessageWithOptimisticPreview(
        message: ChatMessage,
        newText: String,
        replacementPhotoSources: List<String>? = null
    ) {
        val serverMessageId = resolveServerMessageId(message.id)?.trim().orEmpty()
        if (serverMessageId.isBlank()) {
            throw IllegalStateException("Сообщение еще не синхронизировано")
        }

        val normalizedText = newText.trim()
        if (message.type == MessageType.TEXT && normalizedText.isBlank()) {
            throw IllegalArgumentException("Текст сообщения не может быть пустым")
        }

        val optimisticMessage = buildOptimisticEditedMessage(
            message = message,
            newText = normalizedText,
            replacementPhotoSources = replacementPhotoSources
        )
        pendingEditedMessages[message.id] = PendingEditedMessage(
            serverMessageId = serverMessageId,
            replacement = optimisticMessage
        )
        submitVisibleMessages(mergeMessagesForDisplay())

        try {
            val confirmedMessage = if (message.type == MessageType.IMAGE && !replacementPhotoSources.isNullOrEmpty()) {
                val editedPhotos = replacementPhotoSources.map { source ->
                    val uri = runCatching { Uri.parse(source) }.getOrNull()
                    if (uri != null && (uri.scheme == "content" || uri.scheme == "file")) {
                        PhotoMessageProcessor.prepareForUpload(this@ChatActivity, uri).let { prepared ->
                            PreparedEditedPhoto(
                                url = null,
                                upload = PhotoDraftUpload(
                                    bytes = prepared.bytes,
                                    width = prepared.width,
                                    height = prepared.height,
                                    fileName = prepared.fileName
                                )
                            )
                        }
                    } else {
                        PreparedEditedPhoto(url = source, upload = null)
                    }
                }
                val photoUrls = ArrayList<String>(editedPhotos.size)
                val widths = ArrayList<Int>(editedPhotos.size)
                val heights = ArrayList<Int>(editedPhotos.size)
                for (photo in editedPhotos) {
                    val upload = photo.upload
                    if (upload != null) {
                        val photoUrl = AppContainer.chatRepository.uploadPhoto(
                            chatId = chatId,
                            imageBytes = upload.bytes,
                            fileName = upload.fileName
                        )
                        photoUrls += photoUrl
                        widths += upload.width
                        heights += upload.height
                    } else {
                        photoUrls += photo.url.orEmpty()
                        widths += 0
                        heights += 0
                    }
                }
                AppContainer.chatRepository.editMessage(
                    chatId = chatId,
                    messageId = serverMessageId,
                    text = normalizedText,
                    imageUrls = photoUrls,
                    imageWidths = widths,
                    imageHeights = heights
                )
            } else {
                AppContainer.chatRepository.editMessage(
                    chatId = chatId,
                    messageId = serverMessageId,
                    text = normalizedText
                )
            }

            pendingEditedMessages[message.id] = PendingEditedMessage(
                serverMessageId = serverMessageId,
                replacement = confirmedMessage.copy(id = message.id)
            )
            submitVisibleMessages(mergeMessagesForDisplay())
        } catch (throwable: Throwable) {
            pendingEditedMessages.remove(message.id)
            submitVisibleMessages(mergeMessagesForDisplay())
            throw throwable
        }
    }

    private fun buildOptimisticEditedMessage(
        message: ChatMessage,
        newText: String,
        replacementPhotoSources: List<String>?
    ): ChatMessage {
        return when (message.type) {
            MessageType.IMAGE -> {
                val photoUrls = replacementPhotoSources
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?.takeIf { it.isNotEmpty() }
                    ?: resolveMessagePhotoUrls(message)
                message.copy(
                    text = newText,
                    imageUrl = photoUrls.firstOrNull(),
                    imageUrls = photoUrls,
                    imageWidths = message.imageWidths,
                    imageHeights = message.imageHeights,
                    edited = message.edited
                )
            }
            else -> message.copy(
                text = newText,
                edited = message.edited
            )
        }
    }

    private fun refreshGroupInfoSilently() {
        if (!isGroupChat) return
        lifecycleScope.launch {
            runCatching { AppContainer.chatRepository.getGroupInfo(chatId) }
                .onSuccess { applyGroupInfo(it) }
        }
    }

    private fun applyGroupInfo(info: GroupInfo) {
        cachedGroupInfo = info
        binding.tvChatTitle.text = info.title.ifBlank { chatTitle }
        bindChatHeaderAvatar(
            displayName = info.title.ifBlank { chatTitle },
            avatarUrl = info.avatarUrl
        )
        val nextRecipientsCount = (info.members.size - 1).coerceAtLeast(1)
        if (nextRecipientsCount == currentRecipientsCount) return
        currentRecipientsCount = nextRecipientsCount
        adapter?.updateRecipientsCount(currentRecipientsCount)
    }

    private fun bindChatHeaderAvatar(displayName: String, avatarUrl: String?) {
        AvatarLoader.bind(
            imageView = binding.ivChatAvatar,
            fallbackView = binding.tvChatAvatarFallback,
            displayName = displayName.ifBlank { chatTitle },
            avatarUrl = avatarUrl
        )
    }

    private fun openChatHeaderAvatarTarget() {
        if (isGroupChat) {
            showGroupInfo()
            return
        }
        openUserProfile(
            uid = chatPeerUid,
            login = chatPeerLogin,
            displayName = chatPeerDisplayName.ifBlank { chatTitle },
            avatarUrl = chatAvatarUrl.ifBlank { null }
        )
    }

    private fun openIncomingAvatarPreview(message: ChatMessage) {
        val member = cachedGroupInfo?.members?.firstOrNull { it.uid == message.senderId }
        val displayName = member?.displayName?.ifBlank { message.senderName } ?: message.senderName
        val avatarUrl = member?.avatarUrl ?: message.senderAvatarUrl
        startActivity(AvatarPreviewActivity.newIntent(this, displayName, avatarUrl))
    }

    private fun resolveMessagePhotoUrls(message: ChatMessage): List<String> {
        val photoUrls = message.imageUrls.asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toMutableList()
        if (photoUrls.isEmpty()) {
            message.imageUrl?.trim().orEmpty().takeIf { it.isNotBlank() }?.let(photoUrls::add)
        }
        return photoUrls
    }

    private fun openPhotoMessagePreview(message: ChatMessage, selectedPhotoIndex: Int, selectedPhotoUrl: String) {
        val photoUrls = resolveMessagePhotoUrls(message).toMutableList()
        if (photoUrls.isEmpty()) return
        val targetIndex = if (selectedPhotoIndex in photoUrls.indices) {
            selectedPhotoIndex
        } else {
            photoUrls.indexOf(selectedPhotoUrl.trim()).takeIf { it >= 0 } ?: 0
        }
        startActivity(
            PhotoPreviewActivity.newIntent(
                context = this,
                photoUrls = photoUrls,
                startIndex = targetIndex
            )
        )
    }

    private fun openVideoMessagePreview(message: ChatMessage) {
        val videoUrl = message.videoUrl?.trim().orEmpty()
        if (videoUrl.isBlank()) return
        startActivity(VideoMessagePreviewActivity.newIntent(this, videoUrl))
    }

    private fun openIncomingSenderProfile(message: ChatMessage) {
        if (!isGroupChat) {
            openUserProfile(
                uid = chatPeerUid,
                login = chatPeerLogin,
                displayName = chatPeerDisplayName.ifBlank { chatTitle },
                avatarUrl = chatAvatarUrl.ifBlank { null }
            )
            return
        }

        val member = cachedGroupInfo?.members?.firstOrNull { it.uid == message.senderId }
        openUserProfile(
            uid = member?.uid.orEmpty().ifBlank { message.senderId },
            login = member?.login.orEmpty(),
            displayName = member?.displayName?.ifBlank { message.senderName } ?: message.senderName,
            avatarUrl = member?.avatarUrl ?: message.senderAvatarUrl
        )
    }

    private fun openUserProfile(uid: String, login: String, displayName: String, avatarUrl: String?) {
        startActivity(
            UserProfileActivity.newIntent(
                context = this,
                uid = uid,
                login = login,
                displayName = displayName.ifBlank { "\u041f\u043e\u043b\u044c\u0437\u043e\u0432\u0430\u0442\u0435\u043b\u044c" },
                avatarUrl = avatarUrl
            )
        )
    }

    private fun openLogin() {
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private enum class RecordMode {
        VOICE,
        VIDEO
    }

    private enum class RecordingType {
        VOICE,
        VIDEO
    }

    private enum class PhotoSelectionTarget {
        COMPOSE,
        EDIT
    }

    companion object {
        const val EXTRA_CHAT_ID = "extra_chat_id"
        const val EXTRA_CHAT_TITLE = "extra_chat_title"
        const val EXTRA_CHAT_MEMBER_COUNT = "extra_chat_member_count"
        const val EXTRA_CHAT_IS_GROUP = "extra_chat_is_group"
        const val EXTRA_CHAT_UNREAD_COUNT = "extra_chat_unread_count"
        const val EXTRA_CHAT_PEER_UID = "extra_chat_peer_uid"
        const val EXTRA_CHAT_PEER_LOGIN = "extra_chat_peer_login"
        const val EXTRA_CHAT_PEER_DISPLAY_NAME = "extra_chat_peer_display_name"
        const val EXTRA_CHAT_AVATAR_URL = "extra_chat_avatar_url"

        private const val MENU_CONTACT_INFO = 1001
        private const val MENU_SHARE_CONTACT = 1002
        private const val MENU_GROUP_INFO = 1003
        private const val MENU_GROUP_ADD_USER = 1004
        private const val MENU_GROUP_REMOVE_USER = 1005
        private const val MENU_GROUP_LEAVE = 1006
        private const val VOICE_PREVIEW_TEXT = "\uD83C\uDFA4 Voice message"
        private const val PHOTO_PREVIEW_TEXT = "\uD83D\uDCF7 \u0424\u043E\u0442\u043E"
        private const val VIDEO_PREVIEW_TEXT = "\uD83C\uDFA5 \u0412\u0438\u0434\u0435\u043E"
        private const val MAX_PHOTO_DRAFTS = 10
        private const val MAX_VOICE_RECORD_DURATION_SEC = 600
        private const val MAX_VIDEO_RECORD_DURATION_SEC = 60
        private const val SWIPE_REPLY_MAX_SHIFT_FRACTION = 0.36f
        private const val SWIPE_REPLY_TRIGGER_FRACTION = 0.22f
        private const val SWIPE_BACK_FINISH_FRACTION = 0.32f
        private const val INITIAL_LOADING_INDICATOR_DELAY_MS = 450L
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

    private data class ViewportAnchor(
        val messageId: String?,
        val index: Int,
        val offsetPx: Int
    )

    private data class PendingEditedMessage(
        val serverMessageId: String,
        val replacement: ChatMessage
    )

    private data class InlineEditState(
        val message: ChatMessage,
        val serverMessageId: String
    )

    private data class PhotoDraftPreview(
        val source: String,
        val existingUrl: String?,
        val selectedUriIndex: Int?
    )
}

private data class PhotoDraftUpload(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val fileName: String
)

private data class PreparedEditedPhoto(
    val url: String?,
    val upload: PhotoDraftUpload?
)


