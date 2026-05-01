package com.rezerv.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import android.widget.ImageView
import androidx.core.view.doOnLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.math.max

object ImageThumbnailLoader {
    private const val DEFAULT_MAX_SIDE_PX = 720
    private const val MIN_CACHE_BYTES = 8 * 1024 * 1024
    private const val DISK_CACHE_DIR = "image_thumbnail_source_cache_v1"

    private val maxMemoryBytes = Runtime.getRuntime()
        .maxMemory()
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()

    private val memoryCache = object : LruCache<String, Bitmap>((maxMemoryBytes / 6).coerceAtLeast(MIN_CACHE_BYTES)) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val inFlight = mutableMapOf<String, Deferred<Bitmap?>>()

    fun bind(imageView: ImageView, source: String?, maxSidePx: Int = 0) {
        val safeSource = source?.trim().orEmpty()
        if (safeSource.isBlank()) {
            imageView.tag = null
            imageView.setImageDrawable(null)
            return
        }

        val targetMaxSide = maxSidePx.takeIf { it > 0 }
            ?: max(imageView.width, imageView.height).takeIf { it > 0 }
            ?: DEFAULT_MAX_SIDE_PX
        val safeMaxSide = targetMaxSide.coerceIn(96, 1280)
        val cacheKey = "$safeSource#$safeMaxSide"

        imageView.tag = cacheKey
        memoryCache.get(cacheKey)?.let { cached ->
            imageView.setImageBitmap(cached)
            return
        }

        imageView.setImageDrawable(null)
        if (imageView.width <= 0 || imageView.height <= 0) {
            imageView.doOnLayout {
                if (imageView.tag == cacheKey) {
                    bind(imageView, safeSource, max(imageView.width, imageView.height))
                }
            }
            return
        }

        val appContext = imageView.context.applicationContext
        scope.launch {
            val bitmap = awaitBitmap(appContext, safeSource, safeMaxSide, cacheKey)
            withContext(Dispatchers.Main) {
                if (imageView.tag != cacheKey) return@withContext
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap)
                }
            }
        }
    }

    private suspend fun awaitBitmap(context: Context, source: String, maxSidePx: Int, cacheKey: String): Bitmap? {
        memoryCache.get(cacheKey)?.let { return it }
        val deferred = mutex.withLock {
            inFlight[cacheKey] ?: scope.async {
                loadBitmapInternal(context, source, maxSidePx)?.also { memoryCache.put(cacheKey, it) }
            }.also { created ->
                inFlight[cacheKey] = created
                created.invokeOnCompletion {
                    scope.launch {
                        mutex.withLock {
                            if (inFlight[cacheKey] === created) inFlight.remove(cacheKey)
                        }
                    }
                }
            }
        }
        return deferred.await()
    }

    private fun loadBitmapInternal(context: Context, source: String, maxSidePx: Int): Bitmap? {
        val uri = runCatching { Uri.parse(source) }.getOrNull()
        return when {
            uri != null && (uri.scheme == "content" || uri.scheme == "file") -> decodeSampledUri(context, uri, maxSidePx)
            source.startsWith("http://", ignoreCase = true) || source.startsWith("https://", ignoreCase = true) -> {
                val cacheFile = cacheFileForUrl(context, source)
                if (!cacheFile.exists() && !downloadToFile(source, cacheFile)) return null
                decodeSampledFile(cacheFile, maxSidePx).also { if (it == null) cacheFile.delete() }
            }
            else -> decodeSampledFile(File(source), maxSidePx)
        }
    }

    private fun decodeSampledUri(context: Context, uri: Uri, maxSidePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOptions(bounds.outWidth, bounds.outHeight, maxSidePx))
        }
    }

    private fun decodeSampledFile(file: File, maxSidePx: Int): Bitmap? {
        if (!file.exists() || file.length() <= 0L) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return BitmapFactory.decodeFile(file.absolutePath, decodeOptions(bounds.outWidth, bounds.outHeight, maxSidePx))
    }

    private fun decodeOptions(width: Int, height: Int, maxSidePx: Int): BitmapFactory.Options {
        return BitmapFactory.Options().apply {
            inJustDecodeBounds = false
            inSampleSize = calculateInSampleSize(width, height, maxSidePx)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxSidePx: Int): Int {
        var sample = 1
        val safeMaxSide = maxSidePx.coerceAtLeast(96)
        while (width / sample > safeMaxSide || height / sample > safeMaxSide) {
            sample *= 2
        }
        return sample.coerceAtLeast(1)
    }

    private fun downloadToFile(urlString: String, targetFile: File): Boolean {
        val parent = targetFile.parentFile ?: return false
        if (!parent.exists()) parent.mkdirs()
        val tempFile = File(parent, "${targetFile.name}.tmp")
        return runCatching {
            val connection = URL(urlString).openConnection() as HttpURLConnection
            connection.connectTimeout = 12_000
            connection.readTimeout = 12_000
            connection.setRequestProperty("Accept", "image/*")
            connection.instanceFollowRedirects = true
            connection.useCaches = true
            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output -> input.copyTo(output) }
            }
            if (tempFile.length() <= 0L) {
                tempFile.delete()
                false
            } else if (tempFile.renameTo(targetFile)) {
                true
            } else {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
                true
            }
        }.getOrElse {
            tempFile.delete()
            false
        }
    }

    private fun cacheFileForUrl(context: Context, urlString: String): File {
        val dir = File(context.cacheDir, DISK_CACHE_DIR)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "${sha256(urlString)}.img")
    }

    private fun sha256(value: String): String {
        return runCatching {
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString(separator = "") { byte -> "%02x".format(byte) }
        }.getOrElse {
            value.hashCode().toString()
        }
    }
}
