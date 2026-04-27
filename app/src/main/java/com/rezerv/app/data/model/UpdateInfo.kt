package com.rezerv.app.data.model

data class UpdateInfo(
    val latest: UpdateEntry?,
    val history: List<UpdateEntry>,
    val hasUpdate: Boolean
)
