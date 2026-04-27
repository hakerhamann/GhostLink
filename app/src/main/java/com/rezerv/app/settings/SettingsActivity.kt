package com.rezerv.app.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.rezerv.app.AppContainer
import com.rezerv.app.R
import com.rezerv.app.auth.LoginActivity
import com.rezerv.app.data.model.UserProfile
import com.rezerv.app.databinding.ActivitySettingsBinding
import com.rezerv.app.notifications.PushManager
import com.rezerv.app.profile.AvatarPreviewActivity
import com.rezerv.app.util.AvatarLoader
import com.rezerv.app.util.AvatarProcessor
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private var currentUser: UserProfile? = null
    private var pendingAvatarPickerLaunch: Boolean = false

    private val serverOptions = listOf(
        ServerOption("GhostLink Cloud", "http://130.49.128.205:18080"),
        ServerOption("Android emulator (10.0.2.2)", "http://10.0.2.2:8080"),
        ServerOption("Custom server...", null, isCustom = true)
    )

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) uploadAvatar(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        pendingAvatarPickerLaunch = intent.getBooleanExtra(EXTRA_OPEN_AVATAR_PICKER, false)

        val baseTopPadding = binding.topBar.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topBar.updatePadding(top = baseTopPadding + bars.top)
            insets
        }

        setupServerSelector()

        binding.btnBackSettings.setOnClickListener { finish() }
        binding.btnChangeAvatar.setOnClickListener { pickImage.launch("image/*") }
        binding.ivAvatar.setOnClickListener { openAvatarPreview() }
        binding.tvAvatarFallback.setOnClickListener { openAvatarPreview() }
        binding.btnSaveProfile.setOnClickListener { saveProfile() }
        binding.btnSaveServer.setOnClickListener { saveServer() }
        binding.btnLogout.setOnClickListener {
            lifecycleScope.launch {
                runCatching { PushManager.unregisterTokenBeforeSignOut() }
                AppContainer.authRepository.signOut()
                openLogin()
            }
        }

        lifecycleScope.launch {
            refreshUser()
            if (pendingAvatarPickerLaunch) {
                pendingAvatarPickerLaunch = false
                pickImage.launch("image/*")
            }
        }
    }

    private suspend fun refreshUser() {
        setLoading(true)
        val profile = AppContainer.authRepository.currentUserProfile()
        setLoading(false)
        if (profile == null) {
            openLogin()
            return
        }
        currentUser = profile
        renderUser(profile)
    }

    private fun renderUser(user: UserProfile) {
        binding.tvLoginValue.text = "@${user.login}"
        binding.etDisplayName.setText(user.displayName)
        AvatarLoader.bind(
            imageView = binding.ivAvatar,
            fallbackView = binding.tvAvatarFallback,
            displayName = user.displayName,
            avatarUrl = user.avatarUrl
        )
    }

    private fun setupServerSelector() {
        val labels = serverOptions.map { it.label }
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, labels)
        binding.acServerSelector.setAdapter(adapter)
        binding.acServerSelector.keyListener = null
        binding.acServerSelector.setOnClickListener { binding.acServerSelector.showDropDown() }
        binding.acServerSelector.setOnItemClickListener { _, _, position, _ ->
            applyServerOption(serverOptions[position])
        }

        val currentUrl = AppContainer.sessionStore.getServerUrl()
        val selected = serverOptions.firstOrNull { it.url == currentUrl }
        if (selected != null) {
            binding.acServerSelector.setText(selected.label, false)
            applyServerOption(selected)
        } else {
            val custom = serverOptions.last()
            binding.acServerSelector.setText(custom.label, false)
            binding.etCustomServerUrl.setText(currentUrl)
            applyServerOption(custom)
        }
    }

    private fun applyServerOption(option: ServerOption) {
        binding.inputCustomServerLayout.isVisible = option.isCustom
    }

    private fun selectedServerOption(): ServerOption {
        val selectedLabel = binding.acServerSelector.text?.toString().orEmpty()
        return serverOptions.firstOrNull { it.label == selectedLabel } ?: serverOptions.first()
    }

    private fun saveProfile() {
        val name = binding.etDisplayName.text?.toString().orEmpty().trim()
        if (name.isBlank()) {
            Toast.makeText(this, getString(R.string.display_name_required), Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            setLoading(true)
            val result = AppContainer.authRepository.updateDisplayName(name)
            setLoading(false)
            result.onSuccess { user ->
                currentUser = user
                renderUser(user)
                Toast.makeText(this@SettingsActivity, getString(R.string.profile_saved), Toast.LENGTH_SHORT).show()
            }.onFailure { throwable ->
                Toast.makeText(
                    this@SettingsActivity,
                    throwable.message ?: getString(R.string.profile_save_error),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun uploadAvatar(uri: Uri) {
        lifecycleScope.launch {
            setLoading(true)
            val result = runCatching {
                val bytes = AvatarProcessor.compressForUpload(this@SettingsActivity, uri)
                AppContainer.authRepository.uploadAvatar(bytes).getOrThrow()
            }
            setLoading(false)

            result.onSuccess { user ->
                currentUser = user
                renderUser(user)
                Toast.makeText(this@SettingsActivity, getString(R.string.avatar_saved), Toast.LENGTH_SHORT).show()
            }.onFailure { throwable ->
                Toast.makeText(
                    this@SettingsActivity,
                    throwable.message ?: getString(R.string.avatar_save_error),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun saveServer() {
        val option = selectedServerOption()
        val value = if (option.isCustom) {
            binding.etCustomServerUrl.text?.toString().orEmpty().trim()
        } else {
            option.url.orEmpty()
        }

        if (value.isBlank()) {
            Toast.makeText(this, getString(R.string.server_url_required), Toast.LENGTH_SHORT).show()
            return
        }

        val oldUrl = AppContainer.sessionStore.getServerUrl()
        AppContainer.sessionStore.setServerUrl(value)
        val newUrl = AppContainer.sessionStore.getServerUrl()
        if (oldUrl != newUrl) {
            Toast.makeText(this, getString(R.string.server_changed_relogin), Toast.LENGTH_LONG).show()
            openLogin()
        } else {
            Toast.makeText(this, getString(R.string.server_saved), Toast.LENGTH_SHORT).show()
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progress.isVisible = loading
        binding.btnSaveProfile.isEnabled = !loading
        binding.btnSaveServer.isEnabled = !loading
        binding.btnChangeAvatar.isEnabled = !loading
    }

    private fun openAvatarPreview() {
        val user = currentUser ?: return
        val displayName = user.displayName.ifBlank { user.login }
        startActivity(AvatarPreviewActivity.newIntent(this, displayName, user.avatarUrl))
    }

    private fun openLogin() {
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private data class ServerOption(
        val label: String,
        val url: String?,
        val isCustom: Boolean = false
    )

    companion object {
        const val EXTRA_OPEN_PROFILE = "extra_open_profile"
        const val EXTRA_OPEN_AVATAR_PICKER = "extra_open_avatar_picker"
    }
}
