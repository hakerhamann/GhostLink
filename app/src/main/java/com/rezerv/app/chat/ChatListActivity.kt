package com.rezerv.app.chat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rezerv.app.AppContainer
import com.rezerv.app.R
import com.rezerv.app.auth.LoginActivity
import com.rezerv.app.data.model.ChatPreview
import com.rezerv.app.data.model.UpdateDownloadState
import com.rezerv.app.data.model.UserProfile
import com.rezerv.app.data.repository.RealtimeSubscription
import com.rezerv.app.databinding.ActivityChatListBinding
import com.rezerv.app.notifications.MessageNotificationHelper
import com.rezerv.app.notifications.PushManager
import com.rezerv.app.profile.AvatarPreviewActivity
import com.rezerv.app.profile.UserProfileActivity
import com.rezerv.app.settings.SettingsActivity
import com.rezerv.app.ui.adapters.ChatListAdapter
import com.rezerv.app.updates.UpdatesActivity
import com.rezerv.app.util.AvatarLoader
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

class ChatListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatListBinding
    private lateinit var adapter: ChatListAdapter
    private var chatsListener: RealtimeSubscription? = null
    private var currentUser: UserProfile? = null

    private var allChats: List<ChatPreview> = emptyList()
    private var searchQuery: String = ""
    private val pinnedChatIds: MutableSet<String> = mutableSetOf()
    private val pinnedChatOrder: MutableList<String> = mutableListOf()
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                PushManager.syncTokenNow(this)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.progress.isVisible = false
        requestNotificationPermissionIfNeeded()

        binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, GravityCompat.START)
        pinnedChatIds += AppContainer.sessionStore.pinnedChatIds()
        pinnedChatOrder += AppContainer.sessionStore.pinnedChatOrder().filter { pinnedChatIds.contains(it) }
        pinnedChatIds.forEach { id ->
            if (!pinnedChatOrder.contains(id)) {
                pinnedChatOrder += id
            }
        }

        val baseHeaderTop = binding.headerContainer.paddingTop
        val baseDrawerTop = binding.drawerContainer.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(binding.drawerLayout) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.headerContainer.updatePadding(top = baseHeaderTop + bars.top)
            binding.drawerContainer.updatePadding(top = baseDrawerTop + bars.top)
            insets
        }

        adapter = ChatListAdapter(
            onClick = ::openChat,
            onLongClick = ::showChatActionsMenu,
            onAvatarClick = ::openChatAvatarPreview,
            onNameClick = ::openChatProfileFromName,
            isPinned = { chat -> pinnedChatIds.contains(chat.id) }
        )
        binding.recyclerChats.layoutManager = LinearLayoutManager(this)
        binding.recyclerChats.adapter = adapter

        binding.etSearch.doAfterTextChanged { text ->
            searchQuery = text?.toString().orEmpty().trim()
            applyChatFilters()
        }

        binding.btnMenu.setOnClickListener { binding.drawerLayout.openDrawer(GravityCompat.START) }
        binding.tvHeaderDisplayName.setOnClickListener { openProfileFromHeader() }
        binding.tvHeaderLogin.setOnClickListener { openProfileFromHeader() }
        binding.ivHeaderAvatar.setOnClickListener { openOwnAvatarPreview() }
        binding.tvHeaderAvatarFallback.setOnClickListener { openOwnAvatarPreview() }
        binding.ivHeaderAvatar.setOnLongClickListener {
            openAvatarEditorFromHeader()
            true
        }
        binding.tvHeaderAvatarFallback.setOnLongClickListener {
            openAvatarEditorFromHeader()
            true
        }
        binding.ivDrawerAvatar.setOnClickListener { openOwnAvatarPreview() }
        binding.tvDrawerAvatarFallback.setOnClickListener { openOwnAvatarPreview() }
        binding.ivDrawerAvatar.setOnLongClickListener {
            openAvatarEditorFromHeader()
            true
        }
        binding.tvDrawerAvatarFallback.setOnLongClickListener {
            openAvatarEditorFromHeader()
            true
        }
        binding.itemMenuUpdates.setOnClickListener { openUpdatesFromDrawer() }
        binding.itemMenuSettings.setOnClickListener { openSettingsFromDrawer() }
        binding.itemMenuLogout.setOnClickListener { logoutFromDrawer() }
        binding.btnUpdateBannerAction.setOnClickListener { installPendingUpdateFromBanner() }
        onBackPressedDispatcher.addCallback(this) {
            if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                binding.drawerLayout.closeDrawer(GravityCompat.START)
            } else {
                this.isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }

        binding.fabCompose.setOnClickListener {
            showComposeOptionsDialog()
        }
        observeUpdateDownloadState()
        startForegroundUpdatePolling()

        lifecycleScope.launch {
            initSession()
        }
    }

    override fun onDestroy() {
        chatsListener?.remove()
        chatsListener = null
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        currentUser?.let { startChatsObserver(it) }
    }

    override fun onStop() {
        binding.progress.isVisible = false
        chatsListener?.remove()
        chatsListener = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        AppContainer.sessionStore.currentUser()?.let {
            currentUser = it
            renderHeader(it)
        }
        PushManager.syncTokenNow(this, force = false)
        refreshUpdateBadges()
    }

    private suspend fun initSession() {
        val localUser = AppContainer.sessionStore.currentUser()
        if (localUser != null) {
            initSessionForUser(localUser)
            startBackgroundRefreshTasks()
            lifecycleScope.launch {
                val refreshed = AppContainer.authRepository.currentUserProfile()
                if (refreshed == null) {
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            openLogin()
                        }
                    }
                } else {
                    runOnUiThread {
                        currentUser = refreshed
                        renderHeader(refreshed)
                    }
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
        startBackgroundRefreshTasks()
    }

    private fun initSessionForUser(user: UserProfile) {
        currentUser = user
        renderHeader(user)
        refreshUpdateBadges()
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            startChatsObserver(user)
        }
    }

    private fun startBackgroundRefreshTasks() {
        lifecycleScope.launch {
            runCatching { AppContainer.updateRepository.checkForUpdatesAndDownload() }
            refreshUpdateBadges()
        }
        PushManager.syncTokenNow(this, force = false)
    }

    private fun startChatsObserver(user: UserProfile) {
        chatsListener?.remove()
        showCachedChatsIfAvailable(user.uid)
        chatsListener = AppContainer.chatRepository.observeChats(
            userId = user.uid,
            onUpdate = { chats ->
                runOnUiThread { renderChats(chats) }
            },
            onError = { throwable ->
                runOnUiThread {
                    if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                        Toast.makeText(
                            this,
                            throwable.message ?: getString(R.string.dialogs_load_error),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )
    }

    private fun showCachedChatsIfAvailable(userId: String) {
        val repository = AppContainer.chatRepository
        if (!repository.hasCachedChats(userId)) return
        renderChats(repository.getCachedChats(userId))
    }

    private fun renderHeader(user: UserProfile) {
        val displayName = user.displayName.ifBlank { user.login }
        val loginText = "@${user.login}"

        AvatarLoader.bind(
            imageView = binding.ivHeaderAvatar,
            fallbackView = binding.tvHeaderAvatarFallback,
            displayName = displayName,
            avatarUrl = user.avatarUrl
        )
        binding.tvHeaderDisplayName.text = displayName
        binding.tvHeaderLogin.text = loginText

        AvatarLoader.bind(
            imageView = binding.ivDrawerAvatar,
            fallbackView = binding.tvDrawerAvatarFallback,
            displayName = displayName,
            avatarUrl = user.avatarUrl
        )
        binding.tvDrawerDisplayName.text = displayName
        binding.tvDrawerLogin.text = loginText
    }

    private fun renderChats(chats: List<ChatPreview>) {
        allChats = chats
        prefetchMessagesForFastOpen(chats)
        AvatarLoader.prefetch(this, chats.map { it.avatarUrl })
        val hasUnread = chats.any { it.unreadCount > 0 }
        chats.filter { it.unreadCount <= 0 }.forEach { chat ->
            MessageNotificationHelper.cancelChatNotification(this, chat.id)
        }
        if (!hasUnread) {
            MessageNotificationHelper.cancelAllMessageNotifications(this)
        }
        applyChatFilters()
    }

    private fun prefetchMessagesForFastOpen(chats: List<ChatPreview>) {
        if (chats.isEmpty()) return
        val chatIdsByRecency = chats
            .sortedByDescending { it.timestamp }
            .map { it.id }
        AppContainer.chatRepository.prefetchMessagesForChats(chatIdsByRecency)
    }

    private fun applyChatFilters() {
        val query = searchQuery.lowercase(Locale.getDefault())
        val hiddenChatIds = AppContainer.sessionStore.hiddenChatIds()
        val filtered = if (query.isBlank()) {
            allChats.filterNot { hiddenChatIds.contains(it.id) }
        } else {
            allChats.filter { chat ->
                !hiddenChatIds.contains(chat.id) &&
                    (
                        chat.title.lowercase(Locale.getDefault()).contains(query) ||
                            chat.lastMessage.lowercase(Locale.getDefault()).contains(query)
                        )
            }
        }

        val pinOrderIndex = pinnedChatOrder.withIndex().associate { it.value to it.index }
        val sorted = filtered.sortedWith(
            compareByDescending<ChatPreview> { pinnedChatIds.contains(it.id) }
                .thenBy { pinOrderIndex[it.id] ?: Int.MAX_VALUE }
                .thenByDescending { it.timestamp }
        )

        adapter.submitList(sorted)
        binding.tvEmpty.isVisible = sorted.isEmpty()
    }

    private fun showChatActionsMenu(chat: ChatPreview) {
        val chatPinned = pinnedChatIds.contains(chat.id)
        val pinLabel = if (chatPinned) {
            getString(R.string.chat_action_unpin)
        } else {
            getString(R.string.chat_action_pin)
        }
        val options = arrayOf(pinLabel, getString(R.string.chat_action_delete))
        MaterialAlertDialogBuilder(this)
            .setTitle(chat.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> setPinnedState(chat, !chatPinned)
                    1 -> confirmHideChat(chat)
                }
            }
            .show()
    }

    private fun confirmHideChat(chat: ChatPreview) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.chat_action_delete))
            .setMessage(getString(R.string.chat_delete_confirm))
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.delete_short)) { _, _ ->
                AppContainer.sessionStore.hideChat(chat.id)
                setPinnedState(chat, false, showToast = false)
                applyChatFilters()
                Toast.makeText(this, getString(R.string.chat_deleted_local), Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun setPinnedState(chat: ChatPreview, pinned: Boolean, showToast: Boolean = true) {
        AppContainer.sessionStore.setChatPinned(chat.id, pinned)
        pinnedChatIds.clear()
        pinnedChatIds += AppContainer.sessionStore.pinnedChatIds()
        pinnedChatOrder.clear()
        pinnedChatOrder += AppContainer.sessionStore.pinnedChatOrder().filter { pinnedChatIds.contains(it) }
        pinnedChatIds.forEach { id ->
            if (!pinnedChatOrder.contains(id)) {
                pinnedChatOrder += id
            }
        }
        applyChatFilters()
        adapter.notifyDataSetChanged()
        if (showToast) {
            val message = if (pinned) getString(R.string.chat_pinned) else getString(R.string.chat_unpinned)
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showComposeOptionsDialog() {
        val options = arrayOf(
            getString(R.string.new_private_chat),
            getString(R.string.new_group_chat)
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.new_chat))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showCreateDirectChatDialog()
                    else -> showCreateGroupChatDialog()
                }
            }
            .show()
    }

    private fun showCreateDirectChatDialog() {
        lifecycleScope.launch {
            binding.progress.isVisible = true
            val users = runCatching { AppContainer.chatRepository.listUsers() }
                .onFailure { throwable ->
                    Toast.makeText(
                        this@ChatListActivity,
                        throwable.message ?: getString(R.string.users_load_error),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                .getOrNull()
                .orEmpty()
            binding.progress.isVisible = false

            if (users.isEmpty()) {
                Toast.makeText(this@ChatListActivity, getString(R.string.no_users_available), Toast.LENGTH_SHORT).show()
                return@launch
            }

            val labels = users.map { "${it.displayName} (@${it.login})" }.toTypedArray()
            MaterialAlertDialogBuilder(this@ChatListActivity)
                .setTitle(getString(R.string.select_user_for_chat))
                .setItems(labels) { _, which ->
                    val user = users.getOrNull(which) ?: return@setItems
                    lifecycleScope.launch {
                        binding.progress.isVisible = true
                        runCatching {
                            AppContainer.chatRepository.createDirectChat(user.login)
                        }.onSuccess { chat ->
                            openChat(chat)
                        }.onFailure { throwable ->
                            Toast.makeText(
                                this@ChatListActivity,
                                throwable.message ?: getString(R.string.chat_create_error),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        binding.progress.isVisible = false
                    }
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }
    }

    private fun showCreateGroupChatDialog() {
        val titleInput = EditText(this).apply {
            hint = getString(R.string.group_name_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(Color.parseColor("#E7F2E5"))
            setHintTextColor(Color.parseColor("#93A994"))
            setBackgroundColor(Color.parseColor("#0C1910"))
            setPadding(30, 24, 30, 24)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.new_group_chat))
            .setView(titleInput)
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.next_step)) { _, _ ->
                val title = titleInput.text?.toString().orEmpty().trim()
                if (title.isBlank()) {
                    Toast.makeText(this, getString(R.string.group_name_required), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                showGroupMembersDialog(title)
            }
            .show()
    }

    private fun showGroupMembersDialog(groupTitle: String) {
        lifecycleScope.launch {
            binding.progress.isVisible = true
            val users = runCatching { AppContainer.chatRepository.listUsers() }
                .onFailure { throwable ->
                    Toast.makeText(
                        this@ChatListActivity,
                        throwable.message ?: getString(R.string.users_load_error),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                .getOrNull()
                .orEmpty()
            binding.progress.isVisible = false

            if (users.isEmpty()) {
                Toast.makeText(this@ChatListActivity, getString(R.string.no_users_available), Toast.LENGTH_SHORT).show()
                return@launch
            }

            val labels = users.map { "${it.displayName} (@${it.login})" }.toTypedArray()
            val checked = BooleanArray(users.size)

            MaterialAlertDialogBuilder(this@ChatListActivity)
                .setTitle(getString(R.string.select_group_members))
                .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                    checked[which] = isChecked
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .setPositiveButton(getString(R.string.create_group)) { _, _ ->
                    val members = users.filterIndexed { index, _ -> checked[index] }.map { it.login }
                    if (members.isEmpty()) {
                        Toast.makeText(
                            this@ChatListActivity,
                            getString(R.string.group_members_required),
                            Toast.LENGTH_SHORT
                        ).show()
                        return@setPositiveButton
                    }

                    lifecycleScope.launch {
                        binding.progress.isVisible = true
                        runCatching {
                            AppContainer.chatRepository.createGroupChat(groupTitle, members)
                        }.onSuccess { chat ->
                            openChat(chat)
                        }.onFailure { throwable ->
                            Toast.makeText(
                                this@ChatListActivity,
                                throwable.message ?: getString(R.string.chat_create_error),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        binding.progress.isVisible = false
                    }
                }
                .show()
        }
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun openProfileFromHeader() {
        startActivity(
            Intent(this, SettingsActivity::class.java).apply {
                putExtra(SettingsActivity.EXTRA_OPEN_PROFILE, true)
            }
        )
    }

    private fun openAvatarEditorFromHeader() {
        startActivity(
            Intent(this, SettingsActivity::class.java).apply {
                putExtra(SettingsActivity.EXTRA_OPEN_PROFILE, true)
                putExtra(SettingsActivity.EXTRA_OPEN_AVATAR_PICKER, true)
            }
        )
    }

    private fun openOwnAvatarPreview() {
        val user = currentUser ?: return
        val displayName = user.displayName.ifBlank { user.login }
        startActivity(AvatarPreviewActivity.newIntent(this, displayName, user.avatarUrl))
    }

    private fun openSettingsFromDrawer() {
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        openSettings()
    }

    private fun openUpdatesFromDrawer() {
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        AppContainer.updateRepository.markLatestUpdateSeen()
        refreshUpdateBadges()
        startActivity(Intent(this, UpdatesActivity::class.java))
    }

    private fun logoutFromDrawer() {
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        lifecycleScope.launch {
            runCatching { PushManager.unregisterTokenBeforeSignOut() }
            AppContainer.authRepository.signOut()
            openLogin()
        }
    }

    private fun refreshUpdateBadges() {
        val hasUnread = AppContainer.updateRepository.hasUnreadUpdate()
        binding.tvHeaderUpdateBadge.isVisible = hasUnread
        binding.tvDrawerUpdateBadge.isVisible = hasUnread
        renderUpdateBanner()
    }

    private fun observeUpdateDownloadState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppContainer.updateRepository.downloadState.collect { state ->
                    renderUpdateBanner(state)
                }
            }
        }
    }

    private fun startForegroundUpdatePolling() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    runCatching { AppContainer.updateRepository.checkForUpdatesAndDownload() }
                    refreshUpdateBadges()
                    delay(60_000L)
                }
            }
        }
    }

    private fun renderUpdateBanner(downloadState: UpdateDownloadState? = null) {
        when (val state = downloadState ?: AppContainer.updateRepository.downloadState.value) {
            is UpdateDownloadState.Downloading -> {
                val percent = state.progressPercent
                binding.tvUpdateBannerText.text = if (percent != null) {
                    getString(R.string.updates_banner_downloading_percent, percent)
                } else {
                    getString(R.string.updates_banner_downloading)
                }
                binding.btnUpdateBannerAction.isVisible = false
                binding.updateBannerProgress.isVisible = true
                binding.updateBannerProgress.isIndeterminate = percent == null
                if (percent != null) {
                    binding.updateBannerProgress.progress = percent
                }
                showUpdateBanner()
            }

            is UpdateDownloadState.Ready -> {
                showReadyUpdateBanner()
            }

            is UpdateDownloadState.Failed -> {
                if (AppContainer.updateRepository.hasReadyDownloadedUpdate()) {
                    showReadyUpdateBanner()
                } else {
                    hideUpdateBanner()
                }
            }

            UpdateDownloadState.Idle -> {
                if (AppContainer.updateRepository.hasReadyDownloadedUpdate()) {
                    showReadyUpdateBanner()
                } else {
                    hideUpdateBanner()
                }
            }
        }
    }

    private fun showReadyUpdateBanner() {
        val versionName = AppContainer.sessionStore.availableUpdateVersionName()
            ?.trim()
            ?.ifBlank { null }
        binding.tvUpdateBannerText.text = if (versionName != null) {
            getString(R.string.updates_banner_ready, versionName)
        } else {
            getString(R.string.updates_banner_ready_generic)
        }
        binding.updateBannerProgress.isVisible = false
        binding.btnUpdateBannerAction.isVisible = true
        showUpdateBanner()
    }

    private fun installPendingUpdateFromBanner() {
        val started = AppContainer.updateRepository.installDownloadedUpdate(this)
        if (!started) {
            Toast.makeText(this, getString(R.string.updates_install_error), Toast.LENGTH_SHORT).show()
            refreshUpdateBadges()
        }
    }

    private fun showUpdateBanner() {
        if (binding.updateBanner.isVisible) return
        binding.updateBanner.alpha = 0f
        binding.updateBanner.translationY = -18f * resources.displayMetrics.density
        binding.updateBanner.isVisible = true
        binding.updateBanner.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(180L)
            .start()
    }

    private fun hideUpdateBanner() {
        if (!binding.updateBanner.isVisible) return
        binding.updateBanner.animate()
            .alpha(0f)
            .translationY(-14f * resources.displayMetrics.density)
            .setDuration(150L)
            .withEndAction {
                binding.updateBanner.isVisible = false
                binding.updateBanner.alpha = 1f
                binding.updateBanner.translationY = 0f
            }
            .start()
    }

    private fun openChat(chat: ChatPreview) {
        val intent = Intent(this, ChatActivity::class.java).apply {
            putExtra(ChatActivity.EXTRA_CHAT_ID, chat.id)
            putExtra(ChatActivity.EXTRA_CHAT_TITLE, chat.title)
            putExtra(ChatActivity.EXTRA_CHAT_MEMBER_COUNT, chat.memberCount)
            putExtra(ChatActivity.EXTRA_CHAT_IS_GROUP, chat.isGroup)
            putExtra(ChatActivity.EXTRA_CHAT_PEER_UID, chat.peerUid)
            putExtra(ChatActivity.EXTRA_CHAT_PEER_LOGIN, chat.peerLogin)
            putExtra(ChatActivity.EXTRA_CHAT_PEER_DISPLAY_NAME, chat.peerDisplayName)
            putExtra(ChatActivity.EXTRA_CHAT_AVATAR_URL, chat.avatarUrl)
            putExtra(ChatActivity.EXTRA_CHAT_UNREAD_COUNT, chat.unreadCount)
        }
        startActivity(intent)
        overridePendingTransition(R.anim.chat_slide_in_right, R.anim.chat_stay)
    }

    private fun openChatAvatarPreview(chat: ChatPreview) {
        val displayName = chat.peerDisplayName?.ifBlank { chat.title }
            ?: chat.title
        startActivity(AvatarPreviewActivity.newIntent(this, displayName, chat.avatarUrl))
    }

    private fun openChatProfileFromName(chat: ChatPreview) {
        if (chat.isGroup) {
            openChat(chat)
            return
        }

        val displayName = chat.peerDisplayName?.ifBlank { chat.title } ?: chat.title
        startActivity(
            UserProfileActivity.newIntent(
                context = this,
                uid = chat.peerUid.orEmpty(),
                login = chat.peerLogin.orEmpty(),
                displayName = displayName,
                avatarUrl = chat.avatarUrl
            )
        )
    }

    private fun openLogin() {
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
