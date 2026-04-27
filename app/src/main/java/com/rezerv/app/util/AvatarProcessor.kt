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

object AvatarProcessor {

    suspend fun compressForUpload(
        context: Context,
        uri: Uri,
        maxSide: Int = 1024,
        jpegQuality: Int = 82
    ): ByteArray = withContext(Dispatchers.IO) {
        val sampled = decodeSampledBitmap(context, uri, maxSide * 2)
            ?: throw IllegalArgumentException("Не удалось прочитать изображение")
        val rotated = applyExifRotation(context, uri, sampled)
        val cropped = cropCenterSquare(rotated)
        val scaled = if (cropped.width > maxSide) {
            Bitmap.createScaledBitmap(cropped, maxSide, maxSide, true)
        } else {
            cropped
        }

        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, jpegQuality.coerceIn(60, 95), out)

        if (scaled !== cropped) scaled.recycle()
        if (cropped !== rotated) cropped.recycle()
        if (rotated !== sampled) rotated.recycle()
        sampled.recycle()

        out.toByteArray()
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

    private fun cropCenterSquare(source: Bitmap): Bitmap {
        val size = max(1, minOf(source.width, source.height))
        val left = (source.width - size) / 2
        val top = (source.height - size) / 2
        return Bitmap.createBitmap(source, left, top, size, size)
    }

    private fun calculateInSampleSize(
        width: Int,
        height: Int,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        var sample = 1
        var halfWidth = width / 2
        var halfHeight = height / 2
        while (halfWidth / sample >= reqWidth && halfHeight / sample >= reqHeight) {
            sample *= 2
        }
        return sample.coerceAtLeast(1)
    }
}
