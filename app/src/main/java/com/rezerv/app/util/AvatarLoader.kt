package com.rezerv.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object AvatarLoader {

    private const val MEMORY_CACHE_DIVIDER = 8
    private const val MIN_CIRCLE_CACHE_BYTES = 4 * 1024 * 1024
    private const val MIN_SOURCE_CACHE_BYTES = 2 * 1024 * 1024
    private const val DISK_CACHE_DIR = "avatar_source_cache_v2"
    private const val MAX_PREFETCH_ITEMS = 80

    private val maxMemoryBytes = Runtime.getRuntime()
        .maxMemory()
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()

    private val circleCacheBytes = (maxMemoryBytes / MEMORY_CACHE_DIVIDER)
        .coerceAtLeast(MIN_CIRCLE_CACHE_BYTES)

    private val sourceCacheBytes = (circleCacheBytes / 3)
        .coerceAtLeast(MIN_SOURCE_CACHE_BYTES)

    private val circleCache = object : LruCache<String, Bitmap>(circleCacheBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val sourceCache = object : LruCache<String, Bitmap>(sourceCacheBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightMutex = Mutex()
    private val inFlightCircle = mutableMapOf<String, Deferred<Bitmap?>>()
    private val inFlightSource = mutableMapOf<String, Deferred<Bitmap?>>()

    fun bind(
        imageView: ImageView,
        fallbackView: TextView,
        displayName: String,
        avatarUrl: String?
    ) {
        val initial = displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "G"
        fallbackView.text = initial

        val safeUrl = normalizeUrl(avatarUrl)
        if (safeUrl.isBlank()) {
            imageView.setImageDrawable(null)
            imageView.tag = null
            fallbackView.visibility = View.VISIBLE
            imageView.visibility = View.INVISIBLE
            return
        }

        imageView.tag = safeUrl
        val cached = circleCache.get(safeUrl)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            imageView.visibility = View.VISIBLE
            fallbackView.visibility = View.INVISIBLE
            return
        }

        imageView.setImageDrawable(null)
        imageView.visibility = View.INVISIBLE
        fallbackView.visibility = View.VISIBLE

        scope.launch {
            val bitmap = awaitCircleBitmap(imageView.context.applicationContext, safeUrl)
            withContext(Dispatchers.Main) {
                if (imageView.tag != safeUrl) return@withContext
                if (bitmap == null) {
                    imageView.visibility = View.INVISIBLE
                    fallbackView.visibility = View.VISIBLE
                } else {
                    imageView.setImageBitmap(bitmap)
                    imageView.visibility = View.VISIBLE
                    fallbackView.visibility = View.INVISIBLE
                }
            }
        }
    }

    fun prefetch(context: Context, avatarUrls: Iterable<String?>) {
        val urls = avatarUrls.asSequence()
            .map(::normalizeUrl)
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_PREFETCH_ITEMS)
            .toList()
        if (urls.isEmpty()) return

        val appContext = context.applicationContext
        scope.launch {
            urls.forEach { url ->
                awaitCircleBitmap(appContext, url)
            }
        }
    }

    fun loadFullSize(context: Context, avatarUrl: String?, onLoaded: (Bitmap?) -> Unit) {
        val safeUrl = normalizeUrl(avatarUrl)
        if (safeUrl.isBlank()) {
            onLoaded(null)
            return
        }

        val appContext = context.applicationContext
        scope.launch {
            val bitmap = awaitSourceBitmap(appContext, safeUrl)
            withContext(Dispatchers.Main) {
                onLoaded(bitmap)
            }
        }
    }

    private suspend fun awaitCircleBitmap(context: Context, urlString: String): Bitmap? {
        circleCache.get(urlString)?.let { return it }

        val deferred = inFlightMutex.withLock {
            inFlightCircle[urlString] ?: scope.async {
                loadCircleBitmapInternal(context, urlString)
            }.also { created ->
                inFlightCircle[urlString] = created
                created.invokeOnCompletion {
                    scope.launch {
                        inFlightMutex.withLock {
                            if (inFlightCircle[urlString] === created) {
                                inFlightCircle.remove(urlString)
                            }
                        }
                    }
                }
            }
        }

        return deferred.await()
    }

    private suspend fun awaitSourceBitmap(context: Context, urlString: String): Bitmap? {
        sourceCache.get(urlString)?.let { return it }

        val deferred = inFlightMutex.withLock {
            inFlightSource[urlString] ?: scope.async {
                loadSourceBitmapInternal(context, urlString)
            }.also { created ->
                inFlightSource[urlString] = created
                created.invokeOnCompletion {
                    scope.launch {
                        inFlightMutex.withLock {
                            if (inFlightSource[urlString] === created) {
                                inFlightSource.remove(urlString)
                            }
                        }
                    }
                }
            }
        }

        return deferred.await()
    }

    private suspend fun loadCircleBitmapInternal(context: Context, urlString: String): Bitmap? {
        val source = awaitSourceBitmap(context, urlString) ?: return null
        val circle = toCircleBitmap(source)
        circleCache.put(urlString, circle)
        return circle
    }

    private fun loadSourceBitmapInternal(context: Context, urlString: String): Bitmap? {
        val cacheFile = cacheFileForUrl(context, urlString)
        if (cacheFile.exists()) {
            decodeBitmap(cacheFile)?.let { cached ->
                sourceCache.put(urlString, cached)
                return cached
            }
            cacheFile.delete()
        }

        if (!downloadToFile(urlString, cacheFile)) return null

        val bitmap = decodeBitmap(cacheFile) ?: return null
        sourceCache.put(urlString, bitmap)
        return bitmap
    }

    private fun decodeBitmap(file: File): Bitmap? {
        return runCatching {
            BitmapFactory.decodeFile(file.absolutePath)
        }.getOrNull()
    }

    private fun downloadToFile(urlString: String, targetFile: File): Boolean {
        val parent = targetFile.parentFile ?: return false
        if (!parent.exists()) {
            parent.mkdirs()
        }

        val tempFile = File(parent, "${targetFile.name}.tmp")
        return runCatching {
            val connection = URL(urlString).openConnection() as HttpURLConnection
            connection.connectTimeout = 12_000
            connection.readTimeout = 12_000
            connection.setRequestProperty("Accept", "image/*")
            connection.instanceFollowRedirects = true
            connection.useCaches = true
            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
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
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, "${sha256(urlString)}.img")
    }

    private fun normalizeUrl(value: String?): String = value?.trim().orEmpty()

    private fun sha256(value: String): String {
        return runCatching {
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString(separator = "") { byte -> "%02x".format(byte) }
        }.getOrElse {
            value.hashCode().toString()
        }
    }

    private fun toCircleBitmap(source: Bitmap): Bitmap {
        val size = minOf(source.width, source.height)
        val left = (source.width - size) / 2
        val top = (source.height - size) / 2
        val squared = Bitmap.createBitmap(source, left, top, size, size)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(squared, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        val radius = size / 2f
        canvas.drawCircle(radius, radius, radius, paint)
        return output
    }
}
