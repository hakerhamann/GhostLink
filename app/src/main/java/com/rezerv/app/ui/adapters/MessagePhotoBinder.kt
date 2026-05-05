package com.rezerv.app.ui.adapters

import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import com.rezerv.app.R
import com.rezerv.app.data.model.ChatMessage
import com.rezerv.app.util.ImageThumbnailLoader

internal object MessagePhotoBinder {
    const val PHOTO_FALLBACK_TEXT = "\uD83D\uDCF7 \u0424\u043E\u0442\u043E"

    fun bind(
        imageView: ImageView,
        albumGrid: GridLayout,
        captionView: TextView,
        item: ChatMessage,
        onMessageImageTap: ((Int, String) -> Unit)?
    ) {
        val imageUrls = resolvePhotoUrls(item)
        val hasCaption = hasVisibleCaption(item.text)
        captionView.text = if (hasCaption) item.text else ""
        captionView.isVisible = hasCaption

        if (imageUrls.isEmpty()) {
            imageView.setImageDrawable(null)
            imageView.isVisible = false
            imageView.setOnClickListener(null)
            albumGrid.isVisible = false
            albumGrid.removeAllViews()
            return
        }

        val photos = resolveAlbumPhotos(item, imageUrls)

        if (photos.size == 1) {
            val photo = photos.first()
            albumGrid.isVisible = false
            albumGrid.removeAllViews()
            bindSinglePhoto(
                imageView = imageView,
                imageUrl = photo.url,
                sourceWidth = photo.width,
                sourceHeight = photo.height,
                onTap = onMessageImageTap?.let { tap ->
                    { tap(photo.originalIndex, photo.url) }
                }
            )
            return
        }

        imageView.isVisible = false
        imageView.tag = null
        imageView.setImageDrawable(null)
        imageView.setOnClickListener(null)

        albumGrid.isVisible = true
        albumGrid.removeAllViews()
        albumGrid.useDefaultMargins = false
        val orderedPhotos = orderAlbumPhotosForMosaic(photos.take(10))
        val photoCount = orderedPhotos.size
        albumGrid.columnCount = ALBUM_GRID_COLUMNS
        val density = albumGrid.resources.displayMetrics.density
        val margin = albumThumbSpacingPx(density)
        val tiles = albumTileSpecs(orderedPhotos, density)
        for (index in 0 until photoCount) {
            val tile = tiles[index]
            val photo = orderedPhotos[index]
            val thumb = ImageView(albumGrid.context).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    rowSpec = GridLayout.spec(tile.row, tile.rowSpan)
                    columnSpec = GridLayout.spec(tile.column, tile.columnSpan)
                    width = tile.widthPx
                    height = tile.heightPx
                    setMargins(0, 0, if (tile.endsRow) 0 else margin, if (tile.isLastRow) 0 else margin)
                }
                background = albumGrid.context.getDrawable(R.drawable.bg_message_image)
                clipToOutline = true
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            thumb.tag = photo.url
            thumb.setOnClickListener(
                if (onMessageImageTap != null) {
                    { onMessageImageTap(photo.originalIndex, photo.url) }
                } else {
                    null
                }
            )
            bindThumbnail(thumb, photo.url)
            albumGrid.addView(thumb)
        }
    }

    fun bindThumbnail(imageView: ImageView, imageUrl: String) {
        val safeUrl = imageUrl.trim()
        if (safeUrl.isBlank()) {
            imageView.setImageDrawable(null)
            return
        }
        ImageThumbnailLoader.bind(imageView, safeUrl)
    }

    private fun albumTileSpecs(photos: List<AlbumPhoto>, density: Float): List<AlbumTileSpec> {
        val count = photos.size
        val width = albumWidthPx(density)
        val gap = albumThumbSpacingPx(density)
        val half = albumSpanWidthPx(width, gap, span = 3)
        val third = albumSpanWidthPx(width, gap, span = 2)
        val heroHeight = if (photos.firstOrNull()?.isPortrait == true) {
            albumPortraitHeroHeightPx(density)
        } else {
            albumHeroHeightPx(density)
        }
        val tallHalfHeight = albumTwoPhotoHeightPx(density)
        if (photos.firstOrNull()?.isPortrait == true && count >= 3) {
            return portraitAlbumTileSpecs(
                count = count,
                width = width,
                gap = gap,
                half = half,
                third = third
            )
        }
        val rows = when (count) {
            2 -> listOf(AlbumRow.TileSet(heightPx = tallHalfHeight, spans = listOf(3, 3)))
            3 -> listOf(
                AlbumRow.Hero(heightPx = heroHeight),
                AlbumRow.TileSet(heightPx = half, spans = listOf(3, 3))
            )
            4 -> listOf(
                AlbumRow.TileSet(heightPx = half, spans = listOf(3, 3)),
                AlbumRow.TileSet(heightPx = half, spans = listOf(3, 3))
            )
            5 -> listOf(
                AlbumRow.Hero(heightPx = heroHeight),
                AlbumRow.TileSet(heightPx = half, spans = listOf(3, 3)),
                AlbumRow.TileSet(heightPx = half, spans = listOf(3, 3))
            )
            6 -> listOf(
                AlbumRow.TileSet(heightPx = third, spans = listOf(2, 2, 2)),
                AlbumRow.TileSet(heightPx = third, spans = listOf(2, 2, 2))
            )
            7 -> listOf(
                AlbumRow.Hero(heightPx = heroHeight),
                AlbumRow.TileSet(heightPx = third, spans = listOf(2, 2, 2)),
                AlbumRow.TileSet(heightPx = third, spans = listOf(2, 2, 2))
            )
            8 -> listOf(
                AlbumRow.TileSet(heightPx = half, spans = listOf(3, 3)),
                AlbumRow.TileSet(heightPx = third, spans = listOf(2, 2, 2)),
                AlbumRow.TileSet(heightPx = third, spans = listOf(2, 2, 2))
            )
            9 -> listOf(
                AlbumRow.TileSet(heightPx = third, spans = listOf(2, 2, 2)),
                AlbumRow.TileSet(heightPx = third, spans = listOf(2, 2, 2)),
                AlbumRow.TileSet(heightPx = third, spans = listOf(2, 2, 2))
            )
            10 -> listOf(
                AlbumRow.Hero(heightPx = heroHeight),
                AlbumRow.TileSet(heightPx = third, spans = listOf(2, 2, 2)),
                AlbumRow.TileSet(heightPx = third, spans = listOf(2, 2, 2)),
                AlbumRow.TileSet(heightPx = third, spans = listOf(2, 2, 2))
            )
            else -> listOf(AlbumRow.Hero(heightPx = heroHeight))
        }
        val result = ArrayList<AlbumTileSpec>(count)
        rows.forEachIndexed rowLoop@{ rowIndex, row ->
            if (result.size >= count) return@rowLoop
            when (row) {
                is AlbumRow.Hero -> {
                    result += AlbumTileSpec(
                        row = rowIndex,
                        column = 0,
                        rowSpan = 1,
                        columnSpan = ALBUM_GRID_COLUMNS,
                        widthPx = width,
                        heightPx = row.heightPx,
                        endsRow = true,
                        isLastRow = rowIndex == rows.lastIndex
                    )
                }
                is AlbumRow.TileSet -> {
                    var column = 0
                    row.spans.forEachIndexed spanLoop@{ tileIndex, span ->
                        if (result.size >= count) return@spanLoop
                        result += AlbumTileSpec(
                            row = rowIndex,
                            column = column,
                            rowSpan = 1,
                            columnSpan = span,
                            widthPx = albumSpanWidthPx(width, gap, span),
                            heightPx = row.heightPx,
                            endsRow = tileIndex == row.spans.lastIndex,
                            isLastRow = rowIndex == rows.lastIndex
                        )
                        column += span
                    }
                }
                is AlbumRow.PortraitLead -> Unit
            }
        }
        return result
    }

    private fun portraitAlbumTileSpecs(
        count: Int,
        width: Int,
        gap: Int,
        half: Int,
        third: Int
    ): List<AlbumTileSpec> {
        val rows = mutableListOf<AlbumRow>(
            AlbumRow.PortraitLead(largeHeightPx = half * 2 + gap, smallHeightPx = half)
        )
        var remaining = count - 3
        while (remaining > 0) {
            when {
                remaining == 1 -> {
                    rows += AlbumRow.Hero(heightPx = albumWideTailHeightPx(half))
                    remaining -= 1
                }
                remaining == 2 || remaining == 4 -> {
                    rows += AlbumRow.TileSet(heightPx = half, spans = listOf(3, 3))
                    remaining -= 2
                }
                else -> {
                    rows += AlbumRow.TileSet(heightPx = third, spans = listOf(2, 2, 2))
                    remaining -= 3
                }
            }
        }

        val result = ArrayList<AlbumTileSpec>(count)
        rows.forEachIndexed rowLoop@{ rowIndex, row ->
            if (result.size >= count) return@rowLoop
            when (row) {
                is AlbumRow.PortraitLead -> {
                    result += AlbumTileSpec(rowIndex, 0, 2, 3, half, row.largeHeightPx, false, rows.size == 1)
                    result += AlbumTileSpec(rowIndex, 3, 1, 3, half, row.smallHeightPx, true, false)
                    result += AlbumTileSpec(rowIndex + 1, 3, 1, 3, half, row.smallHeightPx, true, rows.size == 1)
                }
                is AlbumRow.Hero -> {
                    result += AlbumTileSpec(
                        row = rowIndex + 1,
                        column = 0,
                        rowSpan = 1,
                        columnSpan = ALBUM_GRID_COLUMNS,
                        widthPx = width,
                        heightPx = row.heightPx,
                        endsRow = true,
                        isLastRow = rowIndex == rows.lastIndex
                    )
                }
                is AlbumRow.TileSet -> {
                    var column = 0
                    row.spans.forEachIndexed spanLoop@{ tileIndex, span ->
                        if (result.size >= count) return@spanLoop
                        result += AlbumTileSpec(
                            row = rowIndex + 1,
                            column = column,
                            rowSpan = 1,
                            columnSpan = span,
                            widthPx = albumSpanWidthPx(width, gap, span),
                            heightPx = row.heightPx,
                            endsRow = tileIndex == row.spans.lastIndex,
                            isLastRow = rowIndex == rows.lastIndex
                        )
                        column += span
                    }
                }
            }
        }
        return result
    }

    private fun bindSinglePhoto(
        imageView: ImageView,
        imageUrl: String,
        sourceWidth: Int,
        sourceHeight: Int,
        onTap: (() -> Unit)?
    ) {
        if (imageUrl.isBlank()) {
            imageView.setImageDrawable(null)
            imageView.isVisible = false
            imageView.setOnClickListener(null)
            return
        }

        applyImageBounds(
            imageView = imageView,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight
        )
        imageView.tag = imageUrl
        imageView.isVisible = true
        imageView.setImageDrawable(null)
        imageView.setOnClickListener(
            if (onTap != null) {
                { onTap() }
            } else {
                null
            }
        )
        bindThumbnail(imageView, imageUrl)
    }

    private fun resolvePhotoUrls(item: ChatMessage): List<String> {
        val urls = item.imageUrls.asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
        if (urls.isNotEmpty()) return urls
        val fallback = item.imageUrl?.trim().orEmpty()
        return if (fallback.isNotBlank()) listOf(fallback) else emptyList()
    }

    private fun resolveAlbumPhotos(item: ChatMessage, imageUrls: List<String>): List<AlbumPhoto> {
        return imageUrls.mapIndexed { index, url ->
            val width = item.imageWidths.getOrNull(index)
                ?: if (index == 0) item.imageWidth else 0
            val height = item.imageHeights.getOrNull(index)
                ?: if (index == 0) item.imageHeight else 0
            AlbumPhoto(
                url = url,
                originalIndex = index,
                width = width.coerceAtLeast(0),
                height = height.coerceAtLeast(0)
            )
        }
    }

    private fun orderAlbumPhotosForMosaic(photos: List<AlbumPhoto>): List<AlbumPhoto> {
        if (photos.size < 3) return photos
        if (photos.any { !it.hasKnownSize }) return photos
        val portraitIndex = photos.indexOfFirst { it.isStrongPortrait }
        if (portraitIndex <= 0) return photos
        val reordered = photos.toMutableList()
        val portrait = reordered.removeAt(portraitIndex)
        reordered.add(0, portrait)
        return reordered
    }

    private fun hasVisibleCaption(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.isNotBlank() && trimmed != PHOTO_FALLBACK_TEXT
    }

    private fun applyImageBounds(
        imageView: ImageView,
        sourceWidth: Int,
        sourceHeight: Int
    ) {
        val density = imageView.resources.displayMetrics.density
        val maxWidth = (248f * density).toInt()
        val maxHeight = (332f * density).toInt()
        val minWidth = (120f * density).toInt()
        val minHeight = (92f * density).toInt()

        var width = sourceWidth.coerceAtLeast(0)
        var height = sourceHeight.coerceAtLeast(0)
        if (width <= 0 || height <= 0) {
            width = maxWidth
            height = (maxWidth * 0.72f).toInt()
        }

        val scale = minOf(
            maxWidth.toFloat() / width.toFloat(),
            maxHeight.toFloat() / height.toFloat(),
            1f
        )
        val targetWidth = (width * scale).toInt().coerceAtLeast(minWidth)
        val targetHeight = (height * scale).toInt().coerceAtLeast(minHeight)

        val params = imageView.layoutParams
        if (params.width != targetWidth || params.height != targetHeight) {
            params.width = targetWidth
            params.height = targetHeight
            imageView.layoutParams = params
        }
    }

    private fun albumWidthPx(density: Float): Int = (248f * density).toInt().coerceAtLeast(1)

    private fun albumHeroHeightPx(density: Float): Int = (164f * density).toInt().coerceAtLeast(1)

    private fun albumPortraitHeroHeightPx(density: Float): Int = (268f * density).toInt().coerceAtLeast(1)

    private fun albumTwoPhotoHeightPx(density: Float): Int = (168f * density).toInt().coerceAtLeast(1)

    private fun albumWideTailHeightPx(halfWidthPx: Int): Int = (halfWidthPx * 0.72f).toInt().coerceAtLeast(1)

    private fun albumThumbSpacingPx(density: Float): Int = (4f * density).toInt().coerceAtLeast(0)

    private fun albumSpanWidthPx(totalWidthPx: Int, gapPx: Int, span: Int): Int {
        val cell = (totalWidthPx - gapPx * (ALBUM_GRID_COLUMNS - 1)).toFloat() / ALBUM_GRID_COLUMNS.toFloat()
        return (cell * span + gapPx * (span - 1)).toInt().coerceAtLeast(1)
    }

    private const val ALBUM_GRID_COLUMNS = 6
}

private sealed class AlbumRow {
    data class Hero(val heightPx: Int) : AlbumRow()

    data class PortraitLead(
        val largeHeightPx: Int,
        val smallHeightPx: Int
    ) : AlbumRow()

    data class TileSet(
        val heightPx: Int,
        val spans: List<Int>
    ) : AlbumRow()
}

private data class AlbumPhoto(
    val url: String,
    val originalIndex: Int,
    val width: Int,
    val height: Int
) {
    val hasKnownSize: Boolean
        get() = width > 0 && height > 0

    val aspectRatio: Float
        get() = if (width > 0 && height > 0) width.toFloat() / height.toFloat() else 1f

    val isPortrait: Boolean
        get() = height > width && width > 0

    val isStrongPortrait: Boolean
        get() = aspectRatio <= 0.78f
}

private data class AlbumTileSpec(
    val row: Int,
    val column: Int,
    val rowSpan: Int,
    val columnSpan: Int,
    val widthPx: Int,
    val heightPx: Int,
    val endsRow: Boolean,
    val isLastRow: Boolean
)
