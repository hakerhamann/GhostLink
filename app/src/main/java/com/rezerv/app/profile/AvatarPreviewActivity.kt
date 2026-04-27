package com.rezerv.app.profile

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.rezerv.app.databinding.ActivityAvatarPreviewBinding
import com.rezerv.app.util.AvatarLoader
import kotlin.math.roundToInt

class AvatarPreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAvatarPreviewBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAvatarPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME).orEmpty()
        val avatarUrl = intent.getStringExtra(EXTRA_AVATAR_URL)

        val baseTopPadding = binding.btnClose.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.btnClose.updatePadding(top = baseTopPadding + bars.top)
            insets
        }

        binding.btnClose.setOnClickListener { finish() }
        val rootBackground = binding.root.background?.mutate()
        if (rootBackground != null) {
            binding.root.background = rootBackground
        }
        binding.ivAvatarPreview.onSwipeUpDismiss = { finish() }
        binding.ivAvatarPreview.onDismissDragProgress = { progress ->
            val p = progress.coerceIn(0f, 1f)
            val alpha = (255f * (1f - p * 0.58f)).roundToInt().coerceIn(0, 255)
            rootBackground?.alpha = alpha
            binding.btnClose.alpha = (1f - p * 0.7f).coerceIn(0f, 1f)
        }

        binding.tvAvatarFallback.text = displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "G"
        binding.ivAvatarPreview.isVisible = false
        binding.tvAvatarFallback.isVisible = true

        AvatarLoader.loadFullSize(this, avatarUrl) { bitmap ->
            if (isFinishing || isDestroyed) return@loadFullSize
            if (bitmap == null) {
                binding.ivAvatarPreview.isVisible = false
                binding.tvAvatarFallback.isVisible = true
            } else {
                binding.ivAvatarPreview.setImageBitmap(bitmap)
                binding.ivAvatarPreview.isVisible = true
                binding.tvAvatarFallback.isVisible = false
            }
        }
    }

    companion object {
        private const val EXTRA_DISPLAY_NAME = "extra_display_name"
        private const val EXTRA_AVATAR_URL = "extra_avatar_url"

        fun newIntent(context: Context, displayName: String, avatarUrl: String?): Intent {
            return Intent(context, AvatarPreviewActivity::class.java).apply {
                putExtra(EXTRA_DISPLAY_NAME, displayName)
                putExtra(EXTRA_AVATAR_URL, avatarUrl)
            }
        }
    }
}
