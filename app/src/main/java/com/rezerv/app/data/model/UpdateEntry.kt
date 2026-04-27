package com.rezerv.app.data.model

data class UpdateEntry(
    val versionCode: Int,
    val versionName: String,
    val title: String,
    val changes: List<String>,
    val publishedAt: Long,
    val apkUrl: String?,
    val fileName: String?,
    val fileSize: Long,
    val sha256: String?
)
