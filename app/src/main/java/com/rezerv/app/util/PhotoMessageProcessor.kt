package com.rezerv.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max

data class ProcessedPhoto(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val fileName: String
)

data class PhotoDisplaySize(
    val width: Int,
    val height: Int
)

object PhotoMessageProcessor {

    private const val MAX_DECODE_SIDE = 1920
    private const val TARGET_SIDE = 1600
    private const val TARGET_MAX_BYTES = 1_200_000
    private const val MIN_SIDE = 640
    private const val START_QUALITY = 88
    private const val MIN_QUALITY = 62

    suspend fun prepareForUpload(context: Context, uri: Uri): ProcessedPhoto = withContext(Dispatchers.IO) {
        val sampled = decodeSampledBitmap(context, uri, MAX_DECODE_SIDE)
            ?: throw IllegalArgumentException("Не удалось прочитать изображение")
        val rotated = applyExifRotation(context, uri, sampled)
        if (rotated !== sampled) sampled.recycle()

        var working = if (max(rotated.width, rotated.height) > TARGET_SIDE) {
            scaleToMaxSide(rotated, TARGET_SIDE)
        } else {
            rotated
        }
        if (working !== rotated) rotated.recycle()

        var encoded = compressAdaptive(working)
        while (
            encoded.size > TARGET_MAX_BYTES &&
            max(working.width, working.height) > MIN_SIDE
        ) {
            val next = scaleToMaxSide(working, (max(working.width, working.height) * 0.88f).toInt().coerceAtLeast(MIN_SIDE))
            if (next === working) break
            working.recycle()
            working = next
            encoded = compressAdaptive(working)
        }

        val result = ProcessedPhoto(
            bytes = encoded,
            width = working.width,
            height = working.height,
            fileName = "photo_${System.currentTimeMillis()}.jpg"
        )

        working.recycle()
        result
    }

    suspend fun readDisplaySize(context: Context, uri: Uri): PhotoDisplaySize? = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

        val orientation = context.contentResolver.openInputStream(uri)?.use { input ->
            ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL

        val swapsSides = orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            orientation == ExifInterface.ORIENTATION_ROTATE_270
        if (swapsSides) {
            PhotoDisplaySize(width = bounds.outHeight, height = bounds.outWidth)
        } else {
            PhotoDisplaySize(width = bounds.outWidth, height = bounds.outHeight)
        }
    }

    private fun decodeSampledBitmap(context: Context, uri: Uri, maxSize: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = false
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxSize, maxSize)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        }
    }

    private fun applyExifRotation(context: Context, uri: Uri, source: Bitmap): Bitmap {
        val orientation = context.contentResolver.openInputStream(uri)?.use { input ->
            ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL

        val rotation = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rotation == 0f) return source

        val matrix = Matrix().apply { postRotate(rotation) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun compressAdaptive(bitmap: Bitmap): ByteArray {
        var quality = START_QUALITY
        var encoded = compressJpeg(bitmap, quality)
        while (encoded.size > TARGET_MAX_BYTES && quality > MIN_QUALITY) {
            quality = (quality - 6).coerceAtLeast(MIN_QUALITY)
            encoded = compressJpeg(bitmap, quality)
        }
        return encoded
    }

    private fun compressJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(MIN_QUALITY, 95), output)
        return output.toByteArray()
    }

    private fun scaleToMaxSide(source: Bitmap, maxSide: Int): Bitmap {
        val safeMax = maxSide.coerceAtLeast(MIN_SIDE)
        val currentMax = max(source.width, source.height)
        if (currentMax <= safeMax) return source
        val scale = safeMax.toFloat() / currentMax.toFloat()
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    private fun calculateInSampleSize(
        width: Int,
        height: Int,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        var sample = 1
        while (width / sample > reqWidth || height / sample > reqHeight) {
            sample *= 2
        }
        return sample.coerceAtLeast(1)
    }
}
