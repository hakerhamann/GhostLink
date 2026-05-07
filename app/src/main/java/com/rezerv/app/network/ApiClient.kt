package com.rezerv.app.network

import android.util.Log
import com.rezerv.app.storage.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

class ApiClient(
    private val sessionStore: SessionStore
) {

    suspend fun get(path: String, withAuth: Boolean = true): JSONObject {
        return request(method = "GET", path = path, payload = null, withAuth = withAuth)
    }

    suspend fun post(path: String, payload: JSONObject?, withAuth: Boolean = true): JSONObject {
        return request(method = "POST", path = path, payload = payload, withAuth = withAuth)
    }

    suspend fun put(path: String, payload: JSONObject?, withAuth: Boolean = true): JSONObject {
        return request(method = "PUT", path = path, payload = payload, withAuth = withAuth)
    }

    suspend fun delete(path: String, payload: JSONObject? = null, withAuth: Boolean = true): JSONObject {
        return request(method = "DELETE", path = path, payload = payload, withAuth = withAuth)
    }

    suspend fun uploadAvatar(imageBytes: ByteArray, fileName: String = "avatar.jpg"): JSONObject =
        uploadMultipartBinary(
            path = "/api/profile/avatar",
            fieldName = "avatar",
            payloadBytes = imageBytes,
            fileName = fileName,
            contentType = "image/jpeg"
        )

    suspend fun uploadChatAvatar(chatId: String, imageBytes: ByteArray, fileName: String = "chat_avatar.jpg"): JSONObject =
        uploadMultipartBinary(
            path = "/api/chats/$chatId/avatar",
            fieldName = "avatar",
            payloadBytes = imageBytes,
            fileName = fileName,
            contentType = "image/jpeg"
        )

    suspend fun uploadVoice(
        chatId: String,
        voiceBytes: ByteArray,
        fileName: String = "voice.m4a",
        contentType: String = "audio/mp4"
    ): JSONObject = uploadMultipartBinary(
        path = "/api/chats/$chatId/voice",
        fieldName = "voice",
        payloadBytes = voiceBytes,
        fileName = fileName,
        contentType = contentType
    )

    suspend fun uploadPhoto(
        chatId: String,
        imageBytes: ByteArray,
        fileName: String = "photo.jpg",
        contentType: String = "image/jpeg"
    ): JSONObject = uploadMultipartBinary(
        path = "/api/chats/$chatId/photo",
        fieldName = "photo",
        payloadBytes = imageBytes,
        fileName = fileName,
        contentType = contentType
    )

    suspend fun uploadVideo(
        chatId: String,
        videoBytes: ByteArray,
        fileName: String = "video.mp4",
        contentType: String = "video/mp4",
        onProgress: ((Float) -> Unit)? = null
    ): JSONObject = uploadMultipartBinary(
        path = "/api/chats/$chatId/video",
        fieldName = "video",
        payloadBytes = videoBytes,
        fileName = fileName,
        contentType = contentType,
        onProgress = onProgress
    )

    suspend fun uploadVideoFile(
        chatId: String,
        file: File,
        fileName: String = "video.mp4",
        contentType: String = "video/mp4",
        onProgress: ((Float) -> Unit)? = null
    ): JSONObject = uploadMultipartFile(
        path = "/api/chats/$chatId/video",
        fieldName = "video",
        file = file,
        fileName = fileName,
        contentType = contentType,
        onProgress = onProgress
    )

    suspend fun uploadMultipartFile(
        path: String,
        fieldName: String,
        file: File,
        fileName: String,
        contentType: String,
        onProgress: ((Float) -> Unit)? = null
    ): JSONObject =
        withContext(Dispatchers.IO) {
            require(file.exists() && file.length() > 0L) { "Upload file is empty" }
            var connection: HttpURLConnection? = null
            try {
                val baseUrl = sessionStore.getServerUrl().removeSuffix("/")
                val safePath = if (path.startsWith("/")) path else "/$path"
                val url = URL("$baseUrl$safePath")
                val boundary = "----GhostLinkBoundary${System.currentTimeMillis()}"
                val fileLength = file.length()

                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 60_000
                    readTimeout = 60_000
                    doInput = true
                    doOutput = true
                    setChunkedStreamingMode(64 * 1024)
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                    sessionStore.authToken()?.let { token ->
                        setRequestProperty("Authorization", "Bearer $token")
                    }
                }

                DataOutputStream(connection.outputStream).use { output ->
                    output.writeBytes("--$boundary\r\n")
                    output.writeBytes("Content-Disposition: form-data; name=\"$fieldName\"; filename=\"${escapeMultipartFilename(fileName)}\"\r\n")
                    output.writeBytes("Content-Type: $contentType\r\n\r\n")
                    val buffer = ByteArray(UPLOAD_CHUNK_BYTES)
                    var sent = 0L
                    FileInputStream(file).use { input ->
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            sent += read.toLong()
                            onProgress?.invoke(sent.toFloat() / fileLength.toFloat())
                        }
                    }
                    output.writeBytes("\r\n--$boundary--\r\n")
                    output.flush()
                }

                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.use { input ->
                    BufferedReader(InputStreamReader(input)).readText()
                }.orEmpty()

                if (status !in 200..299) {
                    Log.e("VideoUpload", "Upload failed status=$status body=$body path=$safePath")
                    val message = parseErrorMessage(body)
                    throw ApiException(status, message)
                }

                if (body.isBlank()) JSONObject() else JSONObject(body)
            } catch (exception: IOException) {
                Log.e("VideoUpload", "Upload exception path=$path file=${file.absolutePath}", exception)
                throw connectionException(exception)
            } finally {
                connection?.disconnect()
            }
        }

    private fun escapeMultipartFilename(fileName: String): String {
        return fileName.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "_").replace("\n", "_")
    }

    private suspend fun uploadMultipartBinary(
        path: String,
        fieldName: String,
        payloadBytes: ByteArray,
        fileName: String,
        contentType: String,
        onProgress: ((Float) -> Unit)? = null
    ): JSONObject =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val baseUrl = sessionStore.getServerUrl().removeSuffix("/")
                val safePath = if (path.startsWith("/")) path else "/$path"
                val url = URL("$baseUrl$safePath")
                val boundary = "----GhostLinkBoundary${System.currentTimeMillis()}"

                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 20_000
                    readTimeout = 20_000
                    doInput = true
                    doOutput = true
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                    sessionStore.authToken()?.let { token ->
                        setRequestProperty("Authorization", "Bearer $token")
                    }
                }

                DataOutputStream(connection.outputStream).use { output ->
                    output.writeBytes("--$boundary\r\n")
                    output.writeBytes("Content-Disposition: form-data; name=\"$fieldName\"; filename=\"$fileName\"\r\n")
                    output.writeBytes("Content-Type: $contentType\r\n\r\n")
                    var offset = 0
                    while (offset < payloadBytes.size) {
                        coroutineContext.ensureActive()
                        val count = minOf(UPLOAD_CHUNK_BYTES, payloadBytes.size - offset)
                        output.write(payloadBytes, offset, count)
                        offset += count
                        onProgress?.invoke(offset.toFloat() / payloadBytes.size.toFloat())
                    }
                    output.writeBytes("\r\n--$boundary--\r\n")
                    output.flush()
                }

                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.use { input ->
                    BufferedReader(InputStreamReader(input)).readText()
                }.orEmpty()

                if (status !in 200..299) {
                    val message = parseErrorMessage(body)
                    throw ApiException(status, message)
                }

                if (body.isBlank()) JSONObject() else JSONObject(body)
            } catch (exception: IOException) {
                throw connectionException(exception)
            } finally {
                connection?.disconnect()
            }
        }

    private suspend fun request(
        method: String,
        path: String,
        payload: JSONObject?,
        withAuth: Boolean
    ): JSONObject = withContext(Dispatchers.IO) {
        try {
            val baseUrl = sessionStore.getServerUrl().removeSuffix("/")
            val safePath = if (path.startsWith("/")) path else "/$path"
            val url = URL("$baseUrl$safePath")

            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 10_000
                readTimeout = 10_000
                doInput = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                if (withAuth) {
                    sessionStore.authToken()?.let { token ->
                        setRequestProperty("Authorization", "Bearer $token")
                    }
                }
            }

            if (payload != null) {
                connection.doOutput = true
                connection.outputStream.use { output ->
                    output.write(payload.toString().toByteArray(Charsets.UTF_8))
                }
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { input ->
                BufferedReader(InputStreamReader(input)).readText()
            }.orEmpty()

            if (status !in 200..299) {
                val message = parseErrorMessage(body)
                throw ApiException(status, message)
            }

            if (body.isBlank()) {
                JSONObject()
            } else {
                JSONObject(body)
            }
        } catch (exception: IOException) {
            throw connectionException(exception)
        }
    }

    private fun parseErrorMessage(body: String): String {
        if (body.isBlank()) return "Ошибка соединения с сервером"
        return runCatching {
            JSONObject(body).optString("error").ifBlank { body }
        }.getOrDefault(body)
    }

    private fun connectionException(cause: IOException): ApiException {
        return ApiException(statusCode = 0, message = "Нет соединения с сервером").also {
            it.initCause(cause)
        }
    }

    private companion object {
        private const val UPLOAD_CHUNK_BYTES = 64 * 1024
    }
}
