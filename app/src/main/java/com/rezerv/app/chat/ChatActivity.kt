package com.rezerv.app.chat

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.EditText
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
import com.rezerv.app.ui.adapters.MessageAdapter
import com.rezerv.app.ui.adapters.RoundVideoCache
import com.rezerv.app.ui.dialog.ActionSheetDialog
import com.rezerv.app.util.AvatarLoader
import com.rezerv.app.util.AvatarProcessor
import com.rezerv.app.util.ImageThumbnailLoader
import com.rezerv.app.util.PhotoMessageProcessor
import com.rezerv.app.storage.SessionStore
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private var messagesListener: RealtimeSubscription? = null
    private var currentUser: UserProfile? = null
    private var adapter: MessageAdapter? = null
    private lateinit var emojiKeyboardController: ChatEmojiKeyboardController
    private val videoUploadJobsByLocalId = mutableMapOf<String, Job>()
    private val videoUploadFilesByLocalId = mutableMapOf<String, File>()
    private lateinit var viewportController: ChatViewportController
    private lateinit var replyController: ChatReplyController
    private lateinit var optimisticMessageStore: ChatOptimisticMessageStore
    private lateinit var inlineEditController: ChatInlineEditController
    private lateinit var recordingController: ChatRecordingController

    private var latestMessages: List<ChatMessage> = emptyList()
    private var hiddenMessageIds: Set<String> = emptySet()
    private var forceScrollToBottomOnNextUpdate: Boolean = false
    private var userManuallyScrolledAwayFromBottom: Boolean = false
    private var maxReadAckedTimestampMs: Long = 0L
    private var readMarkInFlightUpToTimestampMs: Long? = null
    private var pendingOpenAtFirstUnread: Boolean = false
    private var swipeToReplyHelper: ItemTouchHelper? = null
    private var cachedGroupInfo: GroupInfo? = null
    private var keepViewportStableOnUpdates: Boolean = false
    private var lockedViewportAnchor: ViewportAnchor? = null
    private var isApplyingViewportAnchor: Boolean = false
    private var replySwipeTriggeredInGesture: Boolean = false
    private val loadingIndicatorHandler = Handler(Looper.getMainLooper())
    private val emojiTransitionHandler = Handler(Looper.getMainLooper())
    private var pendingLoadingIndicatorRunnable: Runnable? = null

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
    private var pendingPhotoSelectionTarget: PhotoSelectionTarget = PhotoSelectionTarget.COMPOSE

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
        recordingController.handleAudioPermissionResult(granted)
    }

    private val videoPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] == true
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        recordingController.handleVideoPermissionsResult(cameraGranted, audioGranted)
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
        emojiKeyboardController = ChatEmojiKeyboardController(
            activity = this,
            binding = binding,
            transitionHandler = emojiTransitionHandler
        )
        viewportController = ChatViewportController(
            binding = binding,
            sessionStore = AppContainer.sessionStore,
            chatId = chatId,
            messagesProvider = { adapter?.currentList.orEmpty() }
        )
        replyController = ChatReplyController(
            context = this,
            binding = binding,
            adapterProvider = { adapter },
            messagesProvider = { adapter?.currentList.orEmpty() },
            isEditingMessage = ::isEditingMessage,
            cancelInlineEdit = ::cancelInlineEdit,
            isEmojiPanelActiveOrPending = ::isEmojiPanelActiveOrPending,
            showKeyboard = ::showKeyboard,
            resolveServerMessageId = ::resolveServerMessageId,
            resolveMessagePhotoUrls = ::resolveMessagePhotoUrls,
            dpToPx = ::dpToPx
        )
        optimisticMessageStore = ChatOptimisticMessageStore(
            replyPreviewText = ::replyPreviewText,
            replyPreviewImageUrl = ::replyPreviewImageUrl,
            resolveMessagePhotoUrls = ::resolveMessagePhotoUrls
        )
        inlineEditController = ChatInlineEditController(
            binding = binding,
            selectedPhotoUris = selectedPhotoUris,
            resolveMessagePhotoUrls = ::resolveMessagePhotoUrls,
            clearReplyTarget = ::clearReplyTarget,
            hideEmojiPanelForKeyboard = { hideEmojiPanel(showKeyboard = true) },
            showKeyboard = ::showKeyboard,
            updatePhotoDraftUi = ::updatePhotoDraftUi,
            updateComposerActionState = ::updateComposerActionState
        )
        recordingController = ChatRecordingController(
            activity = this,
            binding = binding,
            requestAudioPermission = { recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
            requestVideoPermissions = {
                videoPermissionsLauncher.launch(
                    arrayOf(
                        Manifest.permission.CAMERA,
                        Manifest.permission.RECORD_AUDIO
                    )
                )
            },
            hideEmojiPanel = { hideEmojiPanel(showKeyboard = false) },
            updateComposerActionState = ::updateComposerActionState,
            sendVoice = ::uploadAndSendVoice,
            sendVideo = ::uploadAndSendVideo
        )
        restoreLastKeyboardHeight()
        currentRecipientsCount = initialRecipientsCount
        pendingOpenAtFirstUnread = initialUnreadCount > 0

        val baseTopPadding = binding.topBar.paddingTop
        val baseInputBottomPadding = binding.inputBar.paddingBottom
        emojiKeyboardController.installWindowInsets(baseTopPadding, baseInputBottomPadding)

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
        binding.btnChatMenu.setOnClickListener { showChatMenu(binding.btnChatMenu) }
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
        binding.btnVideoFlash.setOnClickListener { toggleVideoFlash() }
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
        adapter?.releasePlayback()
        emojiKeyboardController.destroy()
        if (::recordingController.isInitialized) {
            recordingController.release()
        }
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
        adapter?.releasePlayback()
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
                            emojiKeyboardController.resetForSwipeBack()
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
            onReplyToMessage = ::setReplyTarget,
            onMessageImageTap = ::openPhotoMessagePreview,
            onCancelVideoUpload = ::onCancelVideoUpload
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
            optimisticMessageStore.hasOverlayMessages()
    }

    private fun mergeMessagesForDisplay(serverMessages: List<ChatMessage> = latestMessages): List<ChatMessage> {
        return optimisticMessageStore.mergeMessagesForDisplay(serverMessages)
    }

    private fun pruneEditedMessages(serverMessages: List<ChatMessage>) {
        optimisticMessageStore.pruneEditedMessages(serverMessages)
    }

    private fun pruneOverlayMessages(serverMessages: List<ChatMessage>) {
        optimisticMessageStore.pruneOverlayMessages(serverMessages)
    }

    private fun syncOverlayMessagesWithServer(serverMessages: List<ChatMessage>) {
        optimisticMessageStore.syncOverlayMessagesWithServer(serverMessages)
    }

    private fun appendOverlayMessage(message: ChatMessage) {
        optimisticMessageStore.appendOverlayMessage(message)
        submitVisibleMessages(mergeMessagesForDisplay())
    }

    private fun appendOutgoingOverlayMessage(message: ChatMessage) {
        forceScrollToBottomOnNextUpdate = true
        userManuallyScrolledAwayFromBottom = false
        appendOverlayMessage(message)
    }

    private fun markOverlayMessageSent(localId: String, confirmedMessage: ChatMessage) {
        optimisticMessageStore.markOverlayMessageSent(localId, confirmedMessage, latestMessages)
        submitVisibleMessages(mergeMessagesForDisplay())
    }

    private fun markOverlayMessageFailed(localId: String) {
        optimisticMessageStore.markOverlayMessageFailed(localId)
        submitVisibleMessages(mergeMessagesForDisplay())
    }

    private fun updateOverlayUploadProgress(localId: String, progress: Float?) {
        if (!optimisticMessageStore.updateOverlayUploadProgress(localId, progress)) return
        submitVisibleMessages(mergeMessagesForDisplay())
    }

    private fun removeOverlayMessage(rawId: String?) {
        if (optimisticMessageStore.removeOverlayMessage(rawId)) {
            submitVisibleMessages(mergeMessagesForDisplay())
        }
    }

    private fun onCancelVideoUpload(localId: String) {
        videoUploadJobsByLocalId.remove(localId)?.cancel()
        videoUploadFilesByLocalId.remove(localId)?.let { file ->
            runCatching { file.delete() }
        }
        removeOverlayMessage(localId)
    }

    private fun resolveServerMessageId(rawId: String?): String? {
        return optimisticMessageStore.resolveServerMessageId(rawId)
    }

    private fun sanitizeReplyMessageId(message: ChatMessage?): String? {
        return resolveServerMessageId(message?.id)
    }

    private fun buildOptimisticTextMessage(
        user: UserProfile,
        text: String,
        replyTarget: ChatMessage?
    ): ChatMessage {
        return optimisticMessageStore.buildOptimisticTextMessage(user, text, replyTarget)
    }

    private fun buildOptimisticVoiceMessage(
        user: UserProfile,
        durationSec: Int,
        replyTarget: ChatMessage?
    ): ChatMessage {
        return optimisticMessageStore.buildOptimisticVoiceMessage(user, durationSec, replyTarget)
    }

    private fun buildOptimisticImageMessage(
        user: UserProfile,
        imageUrls: List<String>,
        imageWidths: List<Int> = emptyList(),
        imageHeights: List<Int> = emptyList(),
        caption: String,
        replyTarget: ChatMessage?
    ): ChatMessage {
        return optimisticMessageStore.buildOptimisticImageMessage(
            user = user,
            imageUrls = imageUrls,
            imageWidths = imageWidths,
            imageHeights = imageHeights,
            caption = caption,
            replyTarget = replyTarget
        )
    }

    private fun buildOptimisticVideoMessage(
        user: UserProfile,
        durationSec: Int,
        replyTarget: ChatMessage?
    ): ChatMessage {
        return optimisticMessageStore.buildOptimisticVideoMessage(user, durationSec, replyTarget)
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
        return viewportController.captureAnchor()
    }

    private fun applySavedScrollPosition(
        anchorMessageId: String?,
        fallbackIndex: Int,
        anchorOffsetPx: Int
    ) {
        isApplyingViewportAnchor = true
        viewportController.applySavedScrollPosition(
            anchorMessageId = anchorMessageId,
            fallbackIndex = fallbackIndex,
            anchorOffsetPx = anchorOffsetPx
        ) { capturedAnchor, atBottom, normalizedOffset ->
            if (atBottom) {
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
        return viewportController.firstUnreadIndex(messages, myUid)
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
        return viewportController.latestVisibleUnreadIncomingTimestamp(myUid)
    }

    private fun scrollToMessageIndex(index: Int) {
        viewportController.scrollToMessageIndex(index)
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
        return viewportController.isAtBottom()
    }

    private fun scrollToBottom(animated: Boolean) {
        userManuallyScrolledAwayFromBottom = false
        viewportController.scrollToBottom(animated)
    }

    private fun updateScrollToBottomButton() {
        viewportController.updateScrollToBottomButton()
    }

    private fun persistCurrentScrollState() {
        val anchor = captureViewportAnchor() ?: lockedViewportAnchor ?: return
        val atBottom = if (keepViewportStableOnUpdates || isApplyingViewportAnchor) {
            false
        } else {
            isAtBottom()
        }

        viewportController.persistCurrentScrollState(anchor, atBottom)
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

        val replyTarget = currentReplyTarget()
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
        emojiKeyboardController.setup()
    }

    private fun hasDraftText(): Boolean {
        return binding.etMessage.text?.toString()?.trim().orEmpty().isNotEmpty()
    }

    private fun hasDraftPhotos(): Boolean {
        return selectedPhotoUris.isNotEmpty()
    }

    private fun isEditingMessage(): Boolean {
        return inlineEditController.isEditingMessage()
    }

    private fun isEditingPhotoMessage(): Boolean {
        return inlineEditController.isEditingPhotoMessage()
    }

    private fun isEditingTextMessage(): Boolean {
        return inlineEditController.isEditingTextMessage()
    }

    private fun updateInlineEditUi() {
        inlineEditController.updateInlineEditUi()
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
                inlineEditController.existingPhotoCountForLimit()
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
        val editState = inlineEditController.currentState
        val previewItems = when {
            editState?.message?.type == MessageType.IMAGE -> {
                val existing = inlineEditController.existingPhotoPreviews()
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
            preview.existingUrl?.let { inlineEditController.removeExistingPhoto(it) }
        }
        updatePhotoDraftUi()
        updateComposerActionState()
    }

    private fun currentEditedPhotoSources(): List<String> {
        return inlineEditController.currentEditedPhotoSources()
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
        return emojiKeyboardController.isActiveOrPending()
    }

    private fun hideEmojiPanel(showKeyboard: Boolean) {
        emojiKeyboardController.hideEmojiPanel(showKeyboard)
    }

    private fun dpToPx(dp: Float): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun restoreLastKeyboardHeight() {
        emojiKeyboardController.restoreLastKeyboardHeight()
    }

    private fun hideKeyboard() {
        emojiKeyboardController.hideKeyboard()
    }

    private fun showKeyboard() {
        emojiKeyboardController.showKeyboard()
    }

    private fun handleVoiceButtonTouch(view: View, event: MotionEvent): Boolean {
        return recordingController.handleVoiceButtonTouch(view, event)
    }

    private fun isAnyRecordingInProgress(): Boolean =
        recordingController.isAnyRecordingInProgress()

    private fun toggleRecordMode() {
        recordingController.toggleRecordMode()
    }

    private fun switchVideoCamera() {
        recordingController.switchVideoCamera()
    }

    private fun toggleVideoFlash() {
        recordingController.toggleVideoFlash()
    }

    private fun stopAnyActiveRecording(send: Boolean) {
        recordingController.stopAnyActiveRecording(send)
    }

    private fun uploadAndSendVoice(file: File, durationSec: Int) {
        val user = currentUser ?: run {
            runCatching { file.delete() }
            return
        }
        val replyTarget = currentReplyTarget()
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
        val editState = inlineEditController.currentState ?: return
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
        val replyTarget = currentReplyTarget()
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
        if (!file.exists() || file.length() <= 0L) {
            Log.e("VideoUpload", "Invalid video file: exists=${file.exists()} length=${file.length()}")
            Toast.makeText(this, getString(R.string.send_message_error), Toast.LENGTH_SHORT).show()
            runCatching { file.delete() }
            return
        }
        val replyTarget = currentReplyTarget()
        val thumbnailFile = generateLocalVideoThumbnail(file)
        val optimisticMessage = buildOptimisticVideoMessage(
            user = user,
            durationSec = durationSec,
            replyTarget = replyTarget
        ).copy(
            localVideoPath = file.absolutePath,
            videoThumbnailUrl = thumbnailFile?.absolutePath
        )
        appendOutgoingOverlayMessage(optimisticMessage)
        videoUploadFilesByLocalId[optimisticMessage.id] = file
        clearReplyTarget()
        Log.i("VideoUpload", "optimistic visible localId=${optimisticMessage.id} file=${file.absolutePath} size=${file.length()}")

        val uploadJob = lifecycleScope.launch {
            var cacheReady = false
            var uploadSucceeded = false
            var thumbnailUploaded = false
            runCatching {
                val uploadStart = System.currentTimeMillis()
                val thumbnailUrl = thumbnailFile?.let { thumb ->
                    runCatching {
                        AppContainer.chatRepository.uploadPhoto(
                            chatId = chatId,
                            imageBytes = thumb.readBytes(),
                            fileName = thumb.name
                        )
                    }.onFailure {
                        Log.w("VideoUpload", "thumbnail upload skipped: ${it.message}")
                    }.getOrNull()?.also { thumbnailUploaded = true }
                }
                val videoUrl = AppContainer.chatRepository.uploadVideoFile(
                    chatId = chatId,
                    file = file,
                    fileName = file.name,
                    onProgress = { progress ->
                        lifecycleScope.launch {
                            updateOverlayUploadProgress(optimisticMessage.id, progress)
                        }
                    }
                )
                Log.i("VideoUpload", "upload repository end ms=${System.currentTimeMillis() - uploadStart} url=$videoUrl")
                updateOverlayUploadProgress(optimisticMessage.id, 1f)
                cacheReady = RoundVideoCache.putFileForUrl(this@ChatActivity, videoUrl, file) != null
                uploadSucceeded = true
                val sendStart = System.currentTimeMillis()
                Log.i("VideoUpload", "sendVideoMessage start chatId=$chatId url=$videoUrl durationSec=$durationSec")
                AppContainer.chatRepository.sendVideoMessage(
                    chatId = chatId,
                    videoUrl = videoUrl,
                    videoThumbnailUrl = thumbnailUrl,
                    durationSec = durationSec,
                    fallbackText = VIDEO_PREVIEW_TEXT,
                    replyToMessageId = sanitizeReplyMessageId(replyTarget)
                ).also {
                    Log.i("VideoUpload", "sendVideoMessage end ms=${System.currentTimeMillis() - sendStart} id=${it.id}")
                }
            }.onSuccess { confirmedMessage ->
                markOverlayMessageSent(
                    optimisticMessage.id,
                    confirmedMessage.copy(
                        localVideoPath = if (cacheReady) null else file.absolutePath,
                        videoThumbnailUrl = confirmedMessage.videoThumbnailUrl ?: thumbnailFile?.absolutePath
                    )
                )
                MessageNotificationHelper.cancelChatNotification(this@ChatActivity, chatId)
            }.onFailure { throwable ->
                if (throwable is CancellationException) {
                    removeOverlayMessage(optimisticMessage.id)
                    return@onFailure
                }
                Log.e(
                    "VideoUpload",
                    "Video upload failed: ${throwable::class.java.simpleName}: ${throwable.message}",
                    throwable
                )
                markOverlayMessageFailed(optimisticMessage.id)
                Toast.makeText(
                    this@ChatActivity,
                    throwable.message ?: getString(R.string.send_message_error),
                    Toast.LENGTH_SHORT
                ).show()
            }

            videoUploadJobsByLocalId.remove(optimisticMessage.id)
            videoUploadFilesByLocalId.remove(optimisticMessage.id)
            if (cacheReady || (uploadSucceeded && !file.exists())) {
                runCatching { file.delete() }
            }
            if (thumbnailFile != null && thumbnailUploaded) {
                runCatching { thumbnailFile.delete() }
            }
        }
        videoUploadJobsByLocalId[optimisticMessage.id] = uploadJob
    }

    private fun generateLocalVideoThumbnail(file: File): File? {
        return runCatching {
            val bitmap = MediaMetadataRetriever().useFrame(file) ?: return@runCatching null
            val output = File(cacheDir, "video_thumb_${System.currentTimeMillis()}.jpg")
            ByteArrayOutputStream().use { bytes ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 82, bytes)
                output.writeBytes(bytes.toByteArray())
            }
            bitmap.recycle()
            output.takeIf { it.exists() && it.length() > 0L }
        }.onFailure {
            Log.w("VideoUpload", "thumbnail generate failed: ${it.message}")
        }.getOrNull()
    }

    private fun MediaMetadataRetriever.useFrame(file: File): Bitmap? {
        return try {
            setDataSource(file.absolutePath)
            getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } finally {
            release()
        }
    }

    private fun uploadAndSendPhoto(uri: Uri) {
        val user = currentUser ?: return
        val replyTarget = currentReplyTarget()
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
        recordingController.updateRecordingUi(recording, elapsedSec)
    }

    private fun showIncomingMessageActions(message: ChatMessage, rawX: Float, rawY: Float) {
        ActionSheetDialog.showAtPoint(
            this,
            listOf(
                ActionSheetDialog.Action(getString(R.string.reply_message)) { setReplyTarget(message) },
                ActionSheetDialog.Action(getString(R.string.delete_for_me), destructive = true) { deleteMessageForMe(message) }
            ),
            rawX,
            rawY
        )
    }

    private fun showOwnMessageActions(message: ChatMessage, rawX: Float, rawY: Float) {
        if (message.sendState != MessageSendState.SENT) {
            Toast.makeText(this, "Сообщение еще отправляется", Toast.LENGTH_SHORT).show()
            return
        }
        if (message.type != MessageType.TEXT && message.type != MessageType.IMAGE) {
            showDeleteOwnMessageDialog(message, rawX, rawY)
            return
        }

        ActionSheetDialog.showAtPoint(
            this,
            listOf(
                ActionSheetDialog.Action(getString(R.string.edit_message)) { showEditMessageDialog(message) },
                ActionSheetDialog.Action(getString(R.string.delete_message), destructive = true) {
                    showDeleteOwnMessageDialog(message, rawX, rawY)
                }
            ),
            rawX,
            rawY
        )
    }

    private fun showDeleteOwnMessageDialog(message: ChatMessage, rawX: Float? = null, rawY: Float? = null) {
        val actions = listOf(
            ActionSheetDialog.Action(getString(R.string.delete_for_everyone), destructive = true) {
                deleteMessageForEveryone(message)
            },
            ActionSheetDialog.Action(getString(R.string.delete_for_me), destructive = true) {
                deleteMessageForMe(message)
            }
        )
        if (rawX != null && rawY != null) {
            ActionSheetDialog.showAtPoint(this, actions, rawX, rawY)
        } else {
            ActionSheetDialog.show(this, actions)
        }
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
            replyController.clearIfMatches(message, serverMessageId)
            submitVisibleMessages(mergeMessagesForDisplay())
            Toast.makeText(this, getString(R.string.message_deleted_for_me), Toast.LENGTH_SHORT).show()
        }
    }

    private fun setReplyTarget(message: ChatMessage) {
        replyController.setReplyTarget(message)
    }

    private fun clearReplyTarget() {
        replyController.clearReplyTarget()
    }

    private fun onReplyPreviewTap(message: ChatMessage) {
        replyController.onReplyPreviewTap(message)
    }

    private fun replyPreviewText(message: ChatMessage): String {
        return replyController.replyPreviewText(message)
    }

    private fun replyPreviewImageUrl(message: ChatMessage): String? {
        return replyController.replyPreviewImageUrl(message)
    }

    private fun currentReplyTarget(): ChatMessage? {
        return replyController.replyTargetMessage
    }
    private fun showChatMenu(anchor: View) {
        val actions = if (isGroupChat) {
            listOf(
                ActionSheetDialog.Action(getString(R.string.chat_menu_group_info)) { showGroupInfo() },
                ActionSheetDialog.Action(getString(R.string.chat_menu_add_user)) { showAddUserToGroupDialog() },
                ActionSheetDialog.Action("Исключить участника", destructive = true) { showRemoveUserFromGroupDialog() },
                ActionSheetDialog.Action("Покинуть группу", destructive = true) { confirmLeaveGroup() }
            )
        } else {
            listOf(
                ActionSheetDialog.Action(getString(R.string.chat_menu_contact_info)) { showContactInfo() },
                ActionSheetDialog.Action(getString(R.string.chat_menu_share_contact)) { shareContact() }
            )
        }
        ActionSheetDialog.showAnchoredBelow(this, actions, anchor, alignEnd = true)
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
        inlineEditController.begin(
            message = message,
            serverMessageId = serverMessageId,
            emojiVisible = binding.emojiContainer.isVisible
        )
    }

    private fun cancelInlineEdit() {
        if (inlineEditController.cancel()) {
            pendingPhotoSelectionTarget = PhotoSelectionTarget.COMPOSE
        }
    }

    private fun finishInlineEdit() {
        inlineEditController.finish()
        pendingPhotoSelectionTarget = PhotoSelectionTarget.COMPOSE
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
        optimisticMessageStore.putPendingEditedMessage(message.id, serverMessageId, optimisticMessage)
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

            optimisticMessageStore.putPendingEditedMessage(
                messageId = message.id,
                serverMessageId = serverMessageId,
                replacement = confirmedMessage.copy(id = message.id)
            )
            submitVisibleMessages(mergeMessagesForDisplay())
        } catch (throwable: Throwable) {
            optimisticMessageStore.removePendingEditedMessage(message.id)
            submitVisibleMessages(mergeMessagesForDisplay())
            throw throwable
        }
    }

    private fun buildOptimisticEditedMessage(
        message: ChatMessage,
        newText: String,
        replacementPhotoSources: List<String>?
    ): ChatMessage {
        return optimisticMessageStore.buildOptimisticEditedMessage(message, newText, replacementPhotoSources)
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

        private const val VOICE_PREVIEW_TEXT = "\uD83C\uDFA4 Voice message"
        private const val PHOTO_PREVIEW_TEXT = "\uD83D\uDCF7 \u0424\u043E\u0442\u043E"
        private const val VIDEO_PREVIEW_TEXT = "\uD83C\uDFA5 \u0412\u0438\u0434\u0435\u043E"
        private const val MAX_PHOTO_DRAFTS = 10
        private const val SWIPE_REPLY_MAX_SHIFT_FRACTION = 0.36f
        private const val SWIPE_REPLY_TRIGGER_FRACTION = 0.22f
        private const val SWIPE_BACK_FINISH_FRACTION = 0.32f
        private const val INITIAL_LOADING_INDICATOR_DELAY_MS = 450L
    }

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


