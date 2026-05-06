package com.rezerv.app.ui.adapters

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import androidx.core.view.isVisible
import java.util.concurrent.Executors

internal object RoundVideoThumbnailLoader {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(2)
    private val cache = object : LruCache<String, Bitmap>(8 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun bind(imageView: ImageView, source: String) {
        if (
            source.isBlank() ||
            source.startsWith("pending://") ||
            source.startsWith("http://") ||
            source.startsWith("https://")
        ) {
            clear(imageView)
            return
        }

        imageView.tag = source
        cache.get(source)?.let { cached ->
            imageView.setImageBitmap(cached)
            imageView.isVisible = true
            return
        }

        imageView.setImageDrawable(null)
        imageView.isVisible = false
        executor.execute {
            val bitmap = loadFrame(source) ?: return@execute
            cache.put(source, bitmap)
            mainHandler.post {
                if (imageView.tag == source) {
                    imageView.setImageBitmap(bitmap)
                    imageView.isVisible = true
                }
            }
        }
    }

    fun clear(imageView: ImageView) {
        imageView.tag = null
        imageView.setImageDrawable(null)
        imageView.isVisible = false
    }

    private fun loadFrame(videoUrl: String): Bitmap? {
        return runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(videoUrl)
                retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } finally {
                retriever.release()
            }
        }.getOrNull()
    }
}
