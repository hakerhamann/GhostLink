package com.rezerv.app.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.MediaController
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.rezerv.app.databinding.ActivityVideoPreviewBinding

class VideoMessagePreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoPreviewBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL).orEmpty().trim()
        if (videoUrl.isBlank()) {
            Toast.makeText(this, "Видео недоступно", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val baseTopPadding = binding.btnClose.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.btnClose.updatePadding(top = baseTopPadding + bars.top)
            insets
        }

        binding.btnClose.setOnClickListener { finish() }

        val mediaController = MediaController(this).apply {
            setAnchorView(binding.videoView)
        }
        binding.videoView.setMediaController(mediaController)
        binding.videoView.setVideoURI(Uri.parse(videoUrl))
        binding.videoView.setOnPreparedListener { mediaPlayer ->
            mediaPlayer.isLooping = true
            binding.progress.isVisible = false
            binding.videoView.start()
        }
        binding.videoView.setOnErrorListener { _, _, _ ->
            binding.progress.isVisible = false
            Toast.makeText(this, "Не удалось открыть видео", Toast.LENGTH_SHORT).show()
            finish()
            true
        }
    }

    override fun onPause() {
        if (binding.videoView.isPlaying) {
            binding.videoView.pause()
        }
        super.onPause()
    }

    override fun onDestroy() {
        binding.videoView.stopPlayback()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_VIDEO_URL = "extra_video_url"

        fun newIntent(context: Context, videoUrl: String): Intent {
            return Intent(context, VideoMessagePreviewActivity::class.java).apply {
                putExtra(EXTRA_VIDEO_URL, videoUrl)
            }
        }
    }
}
