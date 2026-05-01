package com.rezerv.app.chat

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.rezerv.app.databinding.ActivityPhotoPreviewBinding
import com.rezerv.app.databinding.ItemPhotoPreviewPageBinding
import com.rezerv.app.util.AvatarLoader
import kotlin.math.roundToInt
import java.util.ArrayList

class PhotoPreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPhotoPreviewBinding
    private val photoUrls = mutableListOf<String>()
    private var rootBackground: Drawable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhotoPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val baseTopPadding = binding.btnClose.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.btnClose.updatePadding(top = baseTopPadding + bars.top)
            insets
        }

        photoUrls += intent.getStringArrayListExtra(EXTRA_PHOTO_URLS)
            .orEmpty()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (photoUrls.isEmpty()) {
            finish()
            return
        }

        rootBackground = binding.root.background?.mutate()
        if (rootBackground != null) {
            binding.root.background = rootBackground
        }

        binding.btnClose.setOnClickListener { finish() }
        binding.photoPager.adapter = PhotoPagerAdapter()
        binding.photoPager.offscreenPageLimit = 1
        binding.photoPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                rootBackground?.alpha = 255
                binding.btnClose.alpha = 1f
            }
        })
        binding.photoPager.setCurrentItem(
            intent.getIntExtra(EXTRA_START_INDEX, 0).coerceIn(0, photoUrls.lastIndex),
            false
        )
    }

    private fun updateDismissProgress(progress: Float) {
        val p = progress.coerceIn(0f, 1f)
        val alpha = (255f * (1f - p * 0.58f)).roundToInt().coerceIn(0, 255)
        rootBackground?.alpha = alpha
        binding.btnClose.alpha = (1f - p * 0.7f).coerceIn(0f, 1f)
    }

    private inner class PhotoPagerAdapter : RecyclerView.Adapter<PhotoPageViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoPageViewHolder {
            val binding = ItemPhotoPreviewPageBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return PhotoPageViewHolder(binding)
        }

        override fun getItemCount(): Int = photoUrls.size

        override fun onBindViewHolder(holder: PhotoPageViewHolder, position: Int) {
            holder.bind(photoUrls[position])
        }
    }

    private inner class PhotoPageViewHolder(
        private val binding: ItemPhotoPreviewPageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(photoUrl: String) {
            binding.ivPhotoPreview.onSwipeUpDismiss = { finish() }
            binding.ivPhotoPreview.onDismissDragProgress = { progress ->
                updateDismissProgress(progress)
            }
            binding.ivPhotoPreview.setImageDrawable(null)
            binding.ivPhotoPreview.isVisible = false
            AvatarLoader.loadFullSize(this@PhotoPreviewActivity, photoUrl) { bitmap ->
                if (isFinishing || isDestroyed) return@loadFullSize
                if (bitmap == null) {
                    binding.ivPhotoPreview.setImageDrawable(null)
                    binding.ivPhotoPreview.isVisible = false
                } else {
                    binding.ivPhotoPreview.setImageBitmap(bitmap)
                    binding.ivPhotoPreview.isVisible = true
                }
            }
        }
    }

    companion object {
        private const val EXTRA_PHOTO_URLS = "extra_photo_urls"
        private const val EXTRA_START_INDEX = "extra_start_index"

        fun newIntent(context: Context, photoUrls: List<String>, startIndex: Int): Intent {
            return Intent(context, PhotoPreviewActivity::class.java).apply {
                putStringArrayListExtra(EXTRA_PHOTO_URLS, ArrayList(photoUrls))
                putExtra(EXTRA_START_INDEX, startIndex)
            }
        }
    }
}
