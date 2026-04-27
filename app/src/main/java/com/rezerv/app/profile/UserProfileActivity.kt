package com.rezerv.app.profile

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.rezerv.app.databinding.ActivityUserProfileBinding
import com.rezerv.app.util.AvatarLoader

class UserProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUserProfileBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUserProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME)
            .orEmpty()
            .ifBlank { "\u041f\u043e\u043b\u044c\u0437\u043e\u0432\u0430\u0442\u0435\u043b\u044c" }
        val login = intent.getStringExtra(EXTRA_LOGIN).orEmpty()
        val uid = intent.getStringExtra(EXTRA_UID).orEmpty()
        val avatarUrl = intent.getStringExtra(EXTRA_AVATAR_URL)

        val baseTopPadding = binding.topBar.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topBar.updatePadding(top = baseTopPadding + bars.top)
            insets
        }

        binding.btnBackProfile.setOnClickListener { finish() }
        binding.avatarContainer.setOnClickListener {
            startActivity(AvatarPreviewActivity.newIntent(this, displayName, avatarUrl))
        }
        binding.ivAvatar.setOnClickListener {
            startActivity(AvatarPreviewActivity.newIntent(this, displayName, avatarUrl))
        }
        binding.tvAvatarFallback.setOnClickListener {
            startActivity(AvatarPreviewActivity.newIntent(this, displayName, avatarUrl))
        }

        AvatarLoader.bind(
            imageView = binding.ivAvatar,
            fallbackView = binding.tvAvatarFallback,
            displayName = displayName,
            avatarUrl = avatarUrl
        )
        binding.tvDisplayNameValue.text = displayName
        binding.tvLoginValue.text = if (login.isBlank()) "@" else "@$login"
        binding.tvUidValue.text = uid.ifBlank { "-" }
    }

    companion object {
        private const val EXTRA_UID = "extra_uid"
        private const val EXTRA_LOGIN = "extra_login"
        private const val EXTRA_DISPLAY_NAME = "extra_display_name"
        private const val EXTRA_AVATAR_URL = "extra_avatar_url"

        fun newIntent(
            context: Context,
            uid: String,
            login: String,
            displayName: String,
            avatarUrl: String?
        ): Intent {
            return Intent(context, UserProfileActivity::class.java).apply {
                putExtra(EXTRA_UID, uid)
                putExtra(EXTRA_LOGIN, login)
                putExtra(EXTRA_DISPLAY_NAME, displayName)
                putExtra(EXTRA_AVATAR_URL, avatarUrl)
            }
        }
    }
}
