package com.example.csci_asp.scrapbook

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.ContextCompat
import java.io.OutputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PdfExporter(
    private val context: Context
) {

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val frameStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#33000000")
        strokeWidth = FRAME_STROKE
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
    }

    suspend fun export(
        photoUris: List<Uri>,
        template: ScrapbookTemplate,
        outputStream: OutputStream
    ) = withContext(Dispatchers.IO) {
        require(photoUris.isNotEmpty()) { "Photo list cannot be empty" }

        val document = PdfDocument()
        try {
            val pages = photoUris.chunked(max(1, template.photosPerPage))
            pages.forEachIndexed { index, pagePhotos ->
                val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, index + 1).create()
                val page = document.startPage(pageInfo)
                val canvas = page.canvas
                drawBackground(canvas, template, pageInfo)
                drawPhotos(canvas, pagePhotos, template, pageInfo)
                document.finishPage(page)
            }

            document.writeTo(outputStream)
        } finally {
            document.close()
        }
    }

    private fun drawBackground(
        canvas: Canvas,
        template: ScrapbookTemplate,
        pageInfo: PdfDocument.PageInfo
    ) {
        val background = ContextCompat.getDrawable(context, template.backgroundRes)
        background?.setBounds(0, 0, pageInfo.pageWidth, pageInfo.pageHeight)
        background?.draw(canvas)
    }

    private fun drawPhotos(
        canvas: Canvas,
        photoUris: List<Uri>,
        template: ScrapbookTemplate,
        pageInfo: PdfDocument.PageInfo
    ) {
        val columns = max(1, template.columns)
        val rows = ceil(photoUris.size / columns.toFloat()).toInt()
        val padding = PAGE_PADDING
        val availableWidth = pageInfo.pageWidth.toFloat() - padding * 2
        val availableHeight = pageInfo.pageHeight.toFloat() - padding * 2
        val cellWidth = (availableWidth - (SPACING * (columns - 1))) / columns
        val cellHeight = (availableHeight - (SPACING * (rows - 1))) / rows

        photoUris.forEachIndexed { index, uri ->
            val columnIndex = index % columns
            val rowIndex = index / columns
            val left = padding + columnIndex * (cellWidth + SPACING)
            val top = padding + rowIndex * (cellHeight + SPACING)
            val right = left + cellWidth
            val bottom = top + cellHeight

            val bitmap = decodeScaledBitmap(
                uri,
                max(1, (cellWidth - FRAME_MARGIN * 2).toInt()),
                max(1, (cellHeight - FRAME_MARGIN * 2).toInt())
            ) ?: return@forEachIndexed

            val frameRect = RectF(
                left + FRAME_MARGIN,
                top + FRAME_MARGIN,
                right - FRAME_MARGIN,
                bottom - FRAME_MARGIN
            )

            val photoRect = RectF(
                frameRect.left + PHOTO_INSET,
                frameRect.top + PHOTO_INSET,
                frameRect.right - PHOTO_INSET,
                frameRect.bottom - PHOTO_INSET
            )

            val scale = min(
                photoRect.width() / bitmap.width.toFloat(),
                photoRect.height() / bitmap.height.toFloat()
            )
            val scaledWidth = bitmap.width * scale
            val scaledHeight = bitmap.height * scale
            val bitmapLeft = photoRect.left + (photoRect.width() - scaledWidth) / 2
            val bitmapTop = photoRect.top + (photoRect.height() - scaledHeight) / 2
            val bitmapRect = RectF(
                bitmapLeft,
                bitmapTop,
                bitmapLeft + scaledWidth,
                bitmapTop + scaledHeight
            )

            val angle = template.rotationFor(index)
            val centerX = frameRect.centerX()
            val centerY = frameRect.centerY()
            canvas.save()
            canvas.rotate(angle, centerX, centerY)
            canvas.drawRoundRect(frameRect, FRAME_RADIUS, FRAME_RADIUS, framePaint)
            canvas.drawRoundRect(frameRect, FRAME_RADIUS, FRAME_RADIUS, frameStrokePaint)
            canvas.drawBitmap(bitmap, null, bitmapRect, bitmapPaint)
            canvas.restore()
            bitmap.recycle()
        }
    }

    private fun decodeScaledBitmap(
        uri: Uri,
        width: Int,
        height: Int
    ): Bitmap? {
        val resolver: ContentResolver = context.contentResolver
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
        options.inSampleSize = calculateInSampleSize(options, width, height)
        options.inJustDecodeBounds = false
        return try {
            resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val (height: Int, width: Int) = options.run { outHeight to outWidth }
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            var halfHeight = height / 2
            var halfWidth = width / 2

            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    companion object {
        private const val PAGE_WIDTH = 1240
        private const val PAGE_HEIGHT = 1754
        private const val SPACING = 32f
        private const val PAGE_PADDING = 72f
        private const val FRAME_MARGIN = 20f
        private const val FRAME_RADIUS = 36f
        private const val FRAME_STROKE = 6f
        private const val PHOTO_INSET = 18f
    }

    private fun ScrapbookTemplate.rotationFor(index: Int): Float =
        if (rotationPattern.isEmpty()) 0f else rotationPattern[index % rotationPattern.size]
}

