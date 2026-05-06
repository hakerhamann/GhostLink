package com.rezerv.app.ui.adapters

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

internal object RoundVideoCache {
    private const val CACHE_DIR = "round_video_cache"
    private const val MAX_BYTES = 512L * 1024L * 1024L
    private const val MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L

    suspend fun fileFor(
        context: Context,
        url: String,
        onProgress: (Float?) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        val safeUrl = url.trim()
        if (safeUrl.isBlank() || safeUrl.startsWith("pending://")) return@withContext null
        val file = cacheFile(context.applicationContext, safeUrl)
        if (file.exists() && file.length() > 0L) {
            file.setLastModified(System.currentTimeMillis())
            return@withContext file
        }
        trim(context.applicationContext)
        download(safeUrl, file, onProgress)
        if (file.exists() && file.length() > 0L) file else null
    }

    private fun download(url: String, target: File, onProgress: (Float?) -> Unit) {
        val parent = target.parentFile ?: return
        parent.mkdirs()
        val tmp = File(parent, "${target.name}.tmp")
        runCatching {
            onProgress(null)
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 25_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "video/*")
            val total = connection.contentLengthLong.takeIf { it > 0L }
            var copied = 0L
            connection.inputStream.use { input ->
                FileOutputStream(tmp).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (total != null) {
                            onProgress((copied.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            if (tmp.length() <= 0L) error("empty round video cache file")
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            target.setLastModified(System.currentTimeMillis())
            onProgress(1f)
        }.onFailure {
            tmp.delete()
            target.delete()
        }
    }

    private fun trim(context: Context) {
        runCatching {
            val now = System.currentTimeMillis()
            val files = cacheDir(context).listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") }.orEmpty()
            files.filter { now - it.lastModified() > MAX_AGE_MS }.forEach { it.delete() }
            var remaining = files.filter { it.exists() }.sortedByDescending { it.lastModified() }
            var size = remaining.sumOf { it.length() }
            remaining.asReversed().forEach { file ->
                if (size <= MAX_BYTES) return@forEach
                size -= file.length()
                file.delete()
            }
        }
    }

    private fun cacheFile(context: Context, url: String): File {
        val path = runCatching { URL(url).path }.getOrDefault("")
        val ext = path.substringAfterLast('.', "").takeIf { it.length in 2..5 } ?: "mp4"
        return File(cacheDir(context), "${sha256(url)}.$ext")
    }

    private fun cacheDir(context: Context): File = File(context.cacheDir, CACHE_DIR).also { it.mkdirs() }

    private fun sha256(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
