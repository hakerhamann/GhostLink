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

    fun bind(imageView: ImageView, videoUrl: String) {
        if (videoUrl.isBlank() || videoUrl.startsWith("pending://")) {
            clear(imageView)
            return
        }

        imageView.tag = videoUrl
        cache.get(videoUrl)?.let { cached ->
            imageView.setImageBitmap(cached)
            imageView.isVisible = true
            return
        }

        imageView.setImageDrawable(null)
        imageView.isVisible = false
        executor.execute {
            val bitmap = loadFrame(videoUrl) ?: return@execute
            cache.put(videoUrl, bitmap)
            mainHandler.post {
                if (imageView.tag == videoUrl) {
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
                if (videoUrl.startsWith("http://") || videoUrl.startsWith("https://")) {
                    retriever.setDataSource(videoUrl, emptyMap())
                } else {
                    retriever.setDataSource(videoUrl)
                }
                retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } finally {
                retriever.release()
            }
        }.getOrNull()
    }
}
