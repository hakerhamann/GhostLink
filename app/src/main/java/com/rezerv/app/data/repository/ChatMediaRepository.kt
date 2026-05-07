package com.rezerv.app.data.repository

import com.rezerv.app.data.model.ChatMessage
import com.rezerv.app.network.ApiClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal class ChatMediaRepository(
    private val apiClient: ApiClient
) {
    suspend fun uploadVoice(chatId: String, voiceBytes: ByteArray, fileName: String): String {
        val response = apiClient.uploadVoice(chatId = chatId, voiceBytes = voiceBytes, fileName = fileName)
        return response.optString("voiceUrl").ifBlank { null }
            ?: response.optString("fileName").ifBlank { throw IllegalStateException("Voice upload failed") }
    }

    suspend fun uploadPhoto(chatId: String, imageBytes: ByteArray, fileName: String): String {
        val response = apiClient.uploadPhoto(chatId = chatId, imageBytes = imageBytes, fileName = fileName)
        return response.optString("photoUrl").ifBlank { null }
            ?: response.optString("fileName").ifBlank { throw IllegalStateException("Photo upload failed") }
    }

    suspend fun uploadVideo(
        chatId: String,
        videoBytes: ByteArray,
        fileName: String,
        onProgress: ((Float) -> Unit)? = null
    ): String {
        val response = apiClient.uploadVideo(
            chatId = chatId,
            videoBytes = videoBytes,
            fileName = fileName,
            onProgress = onProgress
        )
        return response.optString("videoUrl").ifBlank { null }
            ?: response.optString("fileName").ifBlank { throw IllegalStateException("Video upload failed") }
    }

    suspend fun uploadVideoFile(
        chatId: String,
        file: File,
        fileName: String,
        onProgress: ((Float) -> Unit)? = null
    ): String {
        val response = apiClient.uploadVideoFile(
            chatId = chatId,
            file = file,
            fileName = fileName,
            onProgress = onProgress
        )
        return response.optString("videoUrl").ifBlank { null }
            ?: response.optString("fileName").ifBlank { throw IllegalStateException("Video upload failed") }
    }

    suspend fun sendVoiceMessage(
        chatId: String,
        voiceUrl: String,
        durationSec: Int,
        fallbackText: String,
        replyToMessageId: String?
    ): ChatMessage {
        val payload = JSONObject()
            .put("type", "voice")
            .put("voiceUrl", voiceUrl)
            .put("voiceDurationSec", durationSec.coerceAtLeast(0))
            .put("text", fallbackText)
        if (!replyToMessageId.isNullOrBlank()) {
            payload.put("replyToMessageId", replyToMessageId)
        }

        val response = apiClient.post(
            path = "/api/chats/$chatId/messages",
            payload = payload
        )
        return ChatJsonParsers.messageFromSendResponse(response)
    }

    suspend fun sendPhotoMessage(
        chatId: String,
        photoUrls: List<String>,
        width: Int,
        height: Int,
        widths: List<Int>?,
        heights: List<Int>?,
        caption: String?,
        fallbackText: String,
        replyToMessageId: String?
    ): ChatMessage {
        val cleanedPhotoUrls = photoUrls
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
        require(cleanedPhotoUrls.isNotEmpty()) { "Photo list is empty" }

        val payload = JSONObject()
            .put("type", "image")
            .put("imageUrl", cleanedPhotoUrls.first())
            .put("imageUrls", JSONArray(cleanedPhotoUrls))
            .put("imageWidth", width.coerceAtLeast(0))
            .put("imageHeight", height.coerceAtLeast(0))
            .put("text", caption?.trim().takeUnless { it.isNullOrBlank() } ?: fallbackText)
        widths?.takeIf { it.isNotEmpty() }?.let { values ->
            payload.put("imageWidths", JSONArray(values.map { it.coerceAtLeast(0) }))
        }
        heights?.takeIf { it.isNotEmpty() }?.let { values ->
            payload.put("imageHeights", JSONArray(values.map { it.coerceAtLeast(0) }))
        }
        if (!replyToMessageId.isNullOrBlank()) {
            payload.put("replyToMessageId", replyToMessageId)
        }

        val response = apiClient.post(
            path = "/api/chats/$chatId/messages",
            payload = payload
        )
        return ChatJsonParsers.messageFromSendResponse(response)
    }

    suspend fun sendVideoMessage(
        chatId: String,
        videoUrl: String,
        videoThumbnailUrl: String?,
        durationSec: Int,
        fallbackText: String,
        replyToMessageId: String?
    ): ChatMessage {
        val payload = JSONObject()
            .put("type", "video")
            .put("videoUrl", videoUrl)
            .put("videoDurationSec", durationSec.coerceAtLeast(0))
            .put("text", fallbackText)
        if (!videoThumbnailUrl.isNullOrBlank()) {
            payload.put("videoThumbnailUrl", videoThumbnailUrl)
        }
        if (!replyToMessageId.isNullOrBlank()) {
            payload.put("replyToMessageId", replyToMessageId)
        }

        val response = apiClient.post(
            path = "/api/chats/$chatId/messages",
            payload = payload
        )
        return ChatJsonParsers.messageFromSendResponse(response)
    }
}
