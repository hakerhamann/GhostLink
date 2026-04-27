package com.rezerv.app.auth

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.rezerv.app.AppContainer
import com.rezerv.app.R
import com.rezerv.app.chat.ChatListActivity
import com.rezerv.app.databinding.ActivityLoginBinding
import com.rezerv.app.notifications.PushManager
import com.rezerv.app.storage.SessionStore
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private var registerMode = false
    private var rememberedAccounts: List<SessionStore.RememberedAccount> = emptyList()

    private val serverOptions = listOf(
        ServerOption("GhostLink Cloud", "http://130.49.128.205:18080"),
        ServerOption("Android emulator (10.0.2.2)", "http://10.0.2.2:8080"),
        ServerOption("Custom server...", null, isCustom = true)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupServerSelector()
        setupRememberedAccountsSelector()

        binding.btnSubmit.setOnClickListener { submit() }
        binding.btnSwitchMode.setOnClickListener {
            registerMode = !registerMode
            renderMode()
        }
        binding.tvHideError.setOnClickListener { binding.errorGroup.isVisible = false }

        renderMode()
    }

    private fun setupServerSelector() {
        val labels = serverOptions.map { it.label }
        val dropdownAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, labels)
        binding.acServerSelector.setAdapter(dropdownAdapter)
        binding.acServerSelector.keyListener = null
        binding.acServerSelector.setOnClickListener { binding.acServerSelector.showDropDown() }

        binding.acServerSelector.setOnItemClickListener { _, _, position, _ ->
            val option = serverOptions.getOrNull(position) ?: return@setOnItemClickListener
            applyServerOption(option)
        }
        binding.etCustomServerUrl.doAfterTextChanged { refreshRememberedAccountsSelector() }

        val current = AppContainer.sessionStore.getServerUrl()
        val matched = serverOptions.firstOrNull { it.url == current }
        if (matched != null) {
            binding.acServerSelector.setText(matched.label, false)
            applyServerOption(matched)
        } else {
            val customOption = serverOptions.last()
            binding.acServerSelector.setText(customOption.label, false)
            binding.etCustomServerUrl.setText(current)
            applyServerOption(customOption)
        }
        refreshRememberedAccountsSelector()
    }

    private fun applyServerOption(option: ServerOption) {
        binding.inputCustomServerLayout.isVisible = option.isCustom
        refreshRememberedAccountsSelector()
    }

    private fun setupRememberedAccountsSelector() {
        binding.acRememberedAccounts.keyListener = null
        binding.acRememberedAccounts.setOnClickListener { binding.acRememberedAccounts.showDropDown() }
        binding.acRememberedAccounts.setOnItemClickListener { _, _, position, _ ->
            val selected = rememberedAccounts.getOrNull(position) ?: return@setOnItemClickListener
            quickLogin(selected)
        }
        refreshRememberedAccountsSelector()
    }

    private fun refreshRememberedAccountsSelector() {
        val serverUrl = selectedServerUrl()
        rememberedAccounts = AppContainer.sessionStore.rememberedAccountsForServer(serverUrl)
        binding.inputRememberedLayout.isVisible = !registerMode && rememberedAccounts.isNotEmpty()
        if (rememberedAccounts.isEmpty()) {
            binding.acRememberedAccounts.setText("", false)
            return
        }

        val labels = rememberedAccounts.map { "${it.displayName} (@${it.login})" }
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, labels)
        binding.acRememberedAccounts.setAdapter(adapter)
    }

    private fun quickLogin(account: SessionStore.RememberedAccount) {
        lifecycleScope.launch {
            setLoading(true)
            binding.errorGroup.isVisible = false

            val restored = AppContainer.sessionStore.restoreRememberedAccount(
                login = account.login,
                serverUrl = account.serverUrl
            )
            if (!restored) {
                setLoading(false)
                showError(getString(R.string.quick_login_missing))
                refreshRememberedAccountsSelector()
                return@launch
            }

            val user = runCatching { AppContainer.authRepository.currentUserProfile() }.getOrNull()
            if (user != null) {
                openChats()
                return@launch
            }

            AppContainer.sessionStore.removeRememberedAccount(account.login, account.serverUrl)
            AppContainer.authRepository.signOut()
            setLoading(false)
            showError(getString(R.string.quick_login_expired))
            refreshRememberedAccountsSelector()
        }
    }

    private fun selectedServerOption(): ServerOption {
        val selectedLabel = binding.acServerSelector.text?.toString().orEmpty()
        return serverOptions.firstOrNull { it.label == selectedLabel } ?: serverOptions.first()
    }

    private fun selectedServerUrl(): String {
        val option = selectedServerOption()
        return if (option.isCustom) {
            binding.etCustomServerUrl.text?.toString().orEmpty().trim()
        } else {
            option.url.orEmpty()
        }
    }

    private fun submit() {
        val login = binding.etLogin.text?.toString().orEmpty().trim()
        val password = binding.etPassword.text?.toString().orEmpty()
        val displayName = binding.etDisplayName.text?.toString().orEmpty().trim()

        val serverUrl = selectedServerUrl()

        if (serverUrl.isBlank()) {
            showError(getString(R.string.server_url_required))
            return
        }

        AppContainer.sessionStore.setServerUrl(serverUrl)

        lifecycleScope.launch {
            setLoading(true)
            val result = if (registerMode) {
                AppContainer.authRepository.register(
                    login = login,
                    password = password,
                    displayName = displayName
                )
            } else {
                AppContainer.authRepository.signIn(
                    login = login,
                    password = password
                )
            }

            result.onSuccess {
                openChats()
            }.onFailure { throwable ->
                showError(throwable.message ?: getString(R.string.auth_error))
            }

            setLoading(false)
        }
    }

    private fun renderMode() {
        binding.tvTitle.text = if (registerMode) getString(R.string.register_title) else getString(R.string.login_title)
        binding.btnSubmit.text = if (registerMode) getString(R.string.create_account) else getString(R.string.submit_login)
        binding.btnSwitchMode.text = if (registerMode) {
            getString(R.string.switch_to_login)
        } else {
            getString(R.string.switch_mode_create)
        }
        binding.inputDisplayNameLayout.isVisible = registerMode
        binding.inputRememberedLayout.isVisible = !registerMode && rememberedAccounts.isNotEmpty()
    }

    private fun setLoading(loading: Boolean) {
        binding.progress.isVisible = loading
        binding.btnSubmit.isEnabled = !loading
        binding.btnSwitchMode.isEnabled = !loading
        binding.acServerSelector.isEnabled = !loading
        binding.acRememberedAccounts.isEnabled = !loading
        binding.etCustomServerUrl.isEnabled = !loading
    }

    private fun showError(message: String) {
        binding.errorGroup.isVisible = true
        binding.tvError.text = message
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun openChats() {
        PushManager.syncTokenNow(this)
        val intent = Intent(this, ChatListActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private data class ServerOption(
        val label: String,
        val url: String?,
        val isCustom: Boolean = false
    )
}
