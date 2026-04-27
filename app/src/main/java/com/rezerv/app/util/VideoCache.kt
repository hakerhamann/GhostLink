package com.rezerv.app.util

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object VideoCache {

    private const val DISK_CACHE_DIR = "video_message_cache_v1"
    private const val MAX_PREFETCH_ITEMS = 32

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightMutex = Mutex()
    private val inFlight = mutableMapOf<String, Deferred<File?>>()

    suspend fun awaitVideoFile(context: Context, videoUrl: String?): File? {
        val safeUrl = normalizeUrl(videoUrl)
        if (safeUrl.isBlank()) return null

        val deferred = inFlightMutex.withLock {
            inFlight[safeUrl] ?: scope.async {
                loadVideoFileInternal(context.applicationContext, safeUrl)
            }.also { created ->
                inFlight[safeUrl] = created
                created.invokeOnCompletion {
                    scope.launch {
                        inFlightMutex.withLock {
                            if (inFlight[safeUrl] === created) {
                                inFlight.remove(safeUrl)
                            }
                        }
                    }
                }
            }
        }

        return deferred.await()
    }

    fun prefetch(context: Context, videoUrls: Iterable<String?>) {
        val urls = videoUrls.asSequence()
            .map(::normalizeUrl)
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_PREFETCH_ITEMS)
            .toList()
        if (urls.isEmpty()) return

        val appContext = context.applicationContext
        scope.launch {
            urls.forEach { url ->
                awaitVideoFile(appContext, url)
            }
        }
    }

    private fun loadVideoFileInternal(context: Context, urlString: String): File? {
        val cacheFile = cacheFileForUrl(context, urlString)
        if (cacheFile.exists() && cacheFile.length() > 0L) {
            return cacheFile
        }

        if (!downloadToFile(urlString, cacheFile)) return null
        return if (cacheFile.exists() && cacheFile.length() > 0L) cacheFile else null
    }

    private fun downloadToFile(urlString: String, targetFile: File): Boolean {
        val parent = targetFile.parentFile ?: return false
        if (!parent.exists()) {
            parent.mkdirs()
        }

        val tempFile = File(parent, "${targetFile.name}.tmp")
        return runCatching {
            val connection = URL(urlString).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "video/*")
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
        return File(dir, "${sha256(urlString)}${extensionFromUrl(urlString)}")
    }

    private fun extensionFromUrl(urlString: String): String {
        val path = runCatching { URL(urlString).path }.getOrDefault("")
        val ext = path.substringAfterLast('.', "")
        return if (ext.length in 2..5 && ext.all { it.isLetterOrDigit() }) {
            ".${ext.lowercase()}"
        } else {
            ".mp4"
        }
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
}
