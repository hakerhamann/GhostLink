package com.rezerv.app.data.repository

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.rezerv.app.data.model.UpdateEntry
import com.rezerv.app.data.model.UpdateDownloadState
import com.rezerv.app.data.model.UpdateInfo
import com.rezerv.app.network.ApiClient
import com.rezerv.app.storage.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

class UpdateRepository(
    private val context: Context,
    private val apiClient: ApiClient,
    private val sessionStore: SessionStore
) {

    private val updatesDir = File(context.filesDir, "updates").apply { mkdirs() }
    private val downloadLock = Mutex()
    private val _downloadState = MutableStateFlow<UpdateDownloadState>(UpdateDownloadState.Idle)
    val downloadState: StateFlow<UpdateDownloadState> = _downloadState.asStateFlow()

    fun currentVersionCode(): Int {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode
        }
    }

    fun currentVersionName(): String {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return packageInfo.versionName ?: "0.0"
    }

    suspend fun checkForUpdatesAndDownload(currentCode: Int = currentVersionCode()): Result<UpdateInfo> = runCatching {
        cleanupInstalledUpdateFiles(currentCode)
        val info = fetchUpdateInfo(currentCode).getOrElse { throwable ->
            cachedUpdateInfo(currentCode) ?: throw throwable
        }
        val latest = info.latest

        if (latest != null && latest.versionCode > currentCode) {
            sessionStore.setAvailableUpdate(latest.versionCode, latest.versionName)
            ensureLatestApkDownloaded(latest)
        } else {
            sessionStore.clearAvailableUpdate()
            if (_downloadState.value !is UpdateDownloadState.Downloading) {
                _downloadState.value = UpdateDownloadState.Idle
            }
        }

        info
    }

    suspend fun fetchUpdateInfo(currentCode: Int = currentVersionCode()): Result<UpdateInfo> = runCatching {
        val response = apiClient.get(
            path = "/api/updates?currentVersionCode=$currentCode",
            withAuth = false
        )
        sessionStore.setUpdatesInfoCache(response.toString())
        parseUpdateInfoResponse(response, currentCode)
    }

    fun cachedUpdateInfo(currentCode: Int = currentVersionCode()): UpdateInfo? {
        val raw = sessionStore.updatesInfoCache().orEmpty()
        if (raw.isBlank()) return null
        return runCatching {
            parseUpdateInfoResponse(JSONObject(raw), currentCode)
        }.getOrNull()
    }

    fun hasUnreadUpdate(currentCode: Int = currentVersionCode()): Boolean {
        val availableCode = sessionStore.availableUpdateVersionCode()
        if (availableCode <= currentCode) return false
        return availableCode > sessionStore.lastSeenUpdateCode()
    }

    fun markLatestUpdateSeen() {
        val availableCode = sessionStore.availableUpdateVersionCode()
        if (availableCode > 0) {
            sessionStore.markUpdateSeen(availableCode)
        }
    }

    fun isLatestDownloaded(latest: UpdateEntry?, currentCode: Int = currentVersionCode()): Boolean {
        if (latest == null || latest.versionCode <= currentCode) return false
        val downloadedCode = sessionStore.downloadedUpdateVersionCode()
        val path = sessionStore.downloadedUpdatePath().orEmpty()
        return downloadedCode == latest.versionCode && path.isNotBlank() && File(path).exists()
    }

    fun hasReadyDownloadedUpdate(currentCode: Int = currentVersionCode()): Boolean {
        val downloadedCode = sessionStore.downloadedUpdateVersionCode()
        val path = sessionStore.downloadedUpdatePath().orEmpty()
        return downloadedCode > currentCode && path.isNotBlank() && File(path).exists()
    }

    fun installDownloadedUpdate(activity: Activity): Boolean {
        val downloadedCode = sessionStore.downloadedUpdateVersionCode()
        if (downloadedCode <= currentVersionCode()) return false

        val path = sessionStore.downloadedUpdatePath().orEmpty()
        if (path.isBlank()) return false

        val apkFile = File(path)
        if (!apkFile.exists()) {
            sessionStore.clearDownloadedUpdate()
            return false
        }

        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            apkFile
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        return runCatching {
            activity.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    fun cleanupInstalledUpdateFiles(currentCode: Int = currentVersionCode()) {
        val downloadedCode = sessionStore.downloadedUpdateVersionCode()
        val downloadedPath = sessionStore.downloadedUpdatePath()
        val keepPath = downloadedPath?.takeIf { downloadedCode > currentCode && File(it).exists() }

        updatesDir.listFiles()?.forEach { file ->
            if (!file.isFile || !file.name.lowercase(Locale.US).endsWith(".apk")) return@forEach
            if (keepPath == null || file.absolutePath != File(keepPath).absolutePath) {
                runCatching { file.delete() }
            }
        }

        if (downloadedCode <= currentCode || downloadedPath.isNullOrBlank() || !File(downloadedPath).exists()) {
            sessionStore.clearDownloadedUpdate()
        }
        if (sessionStore.availableUpdateVersionCode() <= currentCode) {
            sessionStore.clearAvailableUpdate()
        }
    }

    fun cleanupAllDownloadedApks() {
        updatesDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.lowercase(Locale.US).endsWith(".apk")) {
                runCatching { file.delete() }
            }
        }
        sessionStore.clearDownloadedUpdate()
    }

    private suspend fun ensureLatestApkDownloaded(latest: UpdateEntry) {
        val downloadUrl = latest.apkUrl.orEmpty().trim()
        if (downloadUrl.isBlank()) return

        downloadLock.withLock {
            val currentCode = sessionStore.downloadedUpdateVersionCode()
            val currentPath = sessionStore.downloadedUpdatePath().orEmpty()
            if (currentCode == latest.versionCode && currentPath.isNotBlank() && File(currentPath).exists()) {
                _downloadState.value = UpdateDownloadState.Ready(latest.versionCode)
                return
            }

            val target = File(updatesDir, "ghostlink-update-v${latest.versionCode}.apk")
            _downloadState.value = UpdateDownloadState.Downloading(
                versionCode = latest.versionCode,
                downloadedBytes = 0L,
                totalBytes = -1L
            )
            runCatching {
                downloadToFile(downloadUrl, target, latest.sha256) { downloaded, total ->
                    _downloadState.value = UpdateDownloadState.Downloading(
                        versionCode = latest.versionCode,
                        downloadedBytes = downloaded,
                        totalBytes = total
                    )
                }
            }.onSuccess {
                sessionStore.setDownloadedUpdate(latest.versionCode, target.absolutePath)
                cleanupInstalledUpdateFiles(currentVersionCode())
                _downloadState.value = UpdateDownloadState.Ready(latest.versionCode)
            }.onFailure { throwable ->
                _downloadState.value = UpdateDownloadState.Failed(
                    versionCode = latest.versionCode,
                    message = throwable.message
                )
                throw throwable
            }
        }
    }

    private suspend fun downloadToFile(
        urlString: String,
        target: File,
        expectedSha256: String?,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            if (!updatesDir.exists()) {
                updatesDir.mkdirs()
            }

            val tmp = File(target.parentFile, "${target.name}.part")
            if (tmp.exists()) {
                tmp.delete()
            }

            val digest = MessageDigest.getInstance("SHA-256")
            val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.android.package-archive")
            }

            val status = connection.responseCode
            if (status !in 200..299) {
                throw IllegalStateException("Не удалось скачать обновление: HTTP $status")
            }
            val totalBytes = connection.contentLengthLong.takeIf { it > 0 } ?: -1L

            connection.inputStream.use { input ->
                FileOutputStream(tmp).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloadedBytes = 0L
                    var lastPercent = -1
                    onProgress(downloadedBytes, totalBytes)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        downloadedBytes += read
                        if (totalBytes > 0) {
                            val percent = ((downloadedBytes * 100L) / totalBytes)
                                .toInt()
                                .coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(downloadedBytes, totalBytes)
                            }
                        } else if (downloadedBytes == read.toLong() || downloadedBytes % (256L * 1024L) < read) {
                            onProgress(downloadedBytes, totalBytes)
                        }
                    }
                    onProgress(downloadedBytes, totalBytes)
                    output.flush()
                }
            }

            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            val expected = expectedSha256?.trim()?.lowercase(Locale.US).orEmpty()
            if (expected.isNotBlank() && expected != actual.lowercase(Locale.US)) {
                tmp.delete()
                throw IllegalStateException("Контрольная сумма обновления не совпадает")
            }

            if (target.exists()) {
                target.delete()
            }
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw IllegalStateException("Не удалось сохранить файл обновления")
            }
        }
    }

    private fun parseEntry(raw: JSONObject): UpdateEntry {
        val changesJson = raw.optJSONArray("changes")
        val changes = ArrayList<String>()
        if (changesJson != null) {
            for (index in 0 until changesJson.length()) {
                val value = changesJson.optString(index).trim()
                if (value.isNotBlank()) {
                    changes += value
                }
            }
        }

        return UpdateEntry(
            versionCode = raw.optInt("versionCode"),
            versionName = raw.optString("versionName").ifBlank { "0.0" },
            title = raw.optString("title").ifBlank { "Обновление" },
            changes = changes,
            publishedAt = raw.optLong("publishedAt"),
            apkUrl = raw.optString("apkUrl").ifBlank { null },
            fileName = raw.optString("fileName").ifBlank { null },
            fileSize = raw.optLong("fileSize"),
            sha256 = raw.optString("sha256").ifBlank { null }
        )
    }

    private fun parseUpdateInfoResponse(response: JSONObject, currentCode: Int): UpdateInfo {
        val historyRaw = response.optJSONArray("history")
        val history = ArrayList<UpdateEntry>()
        if (historyRaw != null) {
            for (index in 0 until historyRaw.length()) {
                val item = historyRaw.optJSONObject(index) ?: continue
                history += parseEntry(item)
            }
        }

        val latest = response.optJSONObject("latest")?.let { parseEntry(it) } ?: history.firstOrNull()
        val hasUpdate = response.optBoolean("hasUpdate", (latest?.versionCode ?: 0) > currentCode)

        return UpdateInfo(
            latest = latest,
            history = history,
            hasUpdate = hasUpdate
        )
    }

    companion object {
        fun cleanupAfterPackageReplaced(context: Context) {
            val sessionStore = SessionStore(context)
            val repository = UpdateRepository(
                context = context.applicationContext,
                apiClient = ApiClient(sessionStore),
                sessionStore = sessionStore
            )
            repository.cleanupInstalledUpdateFiles(repository.currentVersionCode())
        }
    }
}
