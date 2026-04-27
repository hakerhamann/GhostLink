package com.rezerv.app.data.model

data class GroupInfo(
    val chatId: String,
    val title: String,
    val description: String,
    val avatarUrl: String?,
    val createdByUid: String? = null,
    val createdByLogin: String? = null,
    val members: List<UserProfile>
)
