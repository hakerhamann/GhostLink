package com.rezerv.app.data.model

sealed class UpdateDownloadState {
    object Idle : UpdateDownloadState()

    data class Downloading(
        val versionCode: Int,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : UpdateDownloadState() {
        val progressPercent: Int?
            get() = if (totalBytes > 0L) {
                ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
            } else {
                null
            }
    }

    data class Ready(val versionCode: Int) : UpdateDownloadState()

    data class Failed(
        val versionCode: Int,
        val message: String?
    ) : UpdateDownloadState()
}
