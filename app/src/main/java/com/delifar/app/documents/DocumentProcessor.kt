package com.delifar.app.documents

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import com.delifar.app.data.InvoicePageEntity
import com.delifar.app.domain.BusinessRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Handles invoice photography, gallery/PDF multi-page imports, untouched original preservation,
 * non-destructive crop/rotate/contrast document adjustments, intelligent compression, and SHA-256 digests.
 */
object DocumentProcessor {
    private const val MAX_DIMENSION_PX = 1800
    private const val JPEG_QUALITY = 82

    fun invoiceDir(context: Context, invoiceId: String): File {
        val dir = File(File(context.filesDir, "invoices"), invoiceId)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun createCameraCaptureFile(context: Context): Pair<File, Uri> {
        val dir = File(context.cacheDir, "camera")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return file to uri
    }

    /**
     * Imports a single camera photo or gallery image URI, preserving the untouched original separately
     * from the intelligently compressed & enhanced edited version.
     */
    suspend fun importImageUri(
        context: Context,
        invoiceId: String,
        pageIndex: Int,
        sourceUri: Uri
    ): Result<InvoicePageEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val pageId = UUID.randomUUID().toString()
            val dir = invoiceDir(context, invoiceId)
            val originalFile = File(dir, "orig_${pageId}.jpg")
            val editedFile = File(dir, "edit_${pageId}.jpg")

            val rawBytes = context.contentResolver.openInputStream(sourceUri)?.use { it.readBytes() }
                ?: throw IllegalStateException("Unable to read selected image.")
            originalFile.writeBytes(rawBytes)
            val origSha = BusinessRules.sha256Hex(rawBytes)

            val editedBytes = renderAdjustedJpeg(originalFile, 0, false, 0)
            editedFile.writeBytes(editedBytes)
            val editSha = BusinessRules.sha256Hex(editedBytes)

            InvoicePageEntity(
                id = pageId,
                invoiceId = invoiceId,
                pageIndex = pageIndex,
                originalLocalPath = originalFile.absolutePath,
                editedLocalPath = editedFile.absolutePath,
                originalSha256 = origSha,
                editedSha256 = editSha,
                rotationDegrees = 0,
                contrastBoost = false,
                cropInsetPercent = 0,
                byteSize = originalFile.length() + editedFile.length(),
                mimeType = "image/jpeg"
            )
        }
    }

    /**
     * Imports a local image file captured directly by the phone camera.
     */
    suspend fun importCapturedFile(
        context: Context,
        invoiceId: String,
        pageIndex: Int,
        capturedFile: File
    ): Result<InvoicePageEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val pageId = UUID.randomUUID().toString()
            val dir = invoiceDir(context, invoiceId)
            val originalFile = File(dir, "orig_${pageId}.jpg")
            val editedFile = File(dir, "edit_${pageId}.jpg")

            val rawBytes = capturedFile.readBytes()
            originalFile.writeBytes(rawBytes)
            val origSha = BusinessRules.sha256Hex(rawBytes)

            val editedBytes = renderAdjustedJpeg(originalFile, 0, false, 0)
            editedFile.writeBytes(editedBytes)
            val editSha = BusinessRules.sha256Hex(editedBytes)
            runCatching { capturedFile.delete() }

            InvoicePageEntity(
                id = pageId,
                invoiceId = invoiceId,
                pageIndex = pageIndex,
                originalLocalPath = originalFile.absolutePath,
                editedLocalPath = editedFile.absolutePath,
                originalSha256 = origSha,
                editedSha256 = editSha,
                rotationDegrees = 0,
                contrastBoost = false,
                cropInsetPercent = 0,
                byteSize = originalFile.length() + editedFile.length(),
                mimeType = "image/jpeg"
            )
        }
    }

    /**
     * Imports a multi-page PDF document by rendering each page into crisp high-readability page images
     * while preserving an untouched copy of each rendered original page.
     */
    suspend fun importPdfUri(
        context: Context,
        invoiceId: String,
        startingPageIndex: Int,
        pdfUri: Uri
    ): Result<List<InvoicePageEntity>> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = invoiceDir(context, invoiceId)
            val tempPdf = File(dir, "import_${System.currentTimeMillis()}.pdf")
            context.contentResolver.openInputStream(pdfUri)?.use { input ->
                FileOutputStream(tempPdf).use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("Unable to open PDF.")

            val pages = mutableListOf<InvoicePageEntity>()
            val pfd = ParcelFileDescriptor.open(tempPdf, ParcelFileDescriptor.MODE_READ_ONLY)
            pfd.use { fd ->
                PdfRenderer(fd).use { renderer ->
                    val count = renderer.pageCount.coerceAtMost(20)
                    for (i in 0 until count) {
                        renderer.openPage(i).use { page ->
                            val scale = (1600f / maxOf(page.width, page.height).coerceAtLeast(1)).coerceIn(1f, 2.5f)
                            val bmpWidth = (page.width * scale).toInt().coerceAtLeast(1)
                            val bmpHeight = (page.height * scale).toInt().coerceAtLeast(1)
                            val bmp = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                            val baos = ByteArrayOutputStream()
                            bmp.compress(Bitmap.CompressFormat.JPEG, 88, baos)
                            bmp.recycle()
                            val bytes = baos.toByteArray()
                            val sha = BusinessRules.sha256Hex(bytes)
                            val pageId = UUID.randomUUID().toString()
                            val origFile = File(dir, "orig_${pageId}.jpg").apply { writeBytes(bytes) }
                            val editFile = File(dir, "edit_${pageId}.jpg").apply { writeBytes(bytes) }

                            pages += InvoicePageEntity(
                                id = pageId,
                                invoiceId = invoiceId,
                                pageIndex = startingPageIndex + i,
                                originalLocalPath = origFile.absolutePath,
                                editedLocalPath = editFile.absolutePath,
                                originalSha256 = sha,
                                editedSha256 = sha,
                                rotationDegrees = 0,
                                contrastBoost = false,
                                cropInsetPercent = 0,
                                byteSize = origFile.length() + editFile.length(),
                                mimeType = "image/jpeg"
                            )
                        }
                    }
                }
            }
            runCatching { tempPdf.delete() }
            pages
        }
    }

    /**
     * Re-renders only the edited copy from the untouched original file with rotation, crop, and document contrast.
     */
    suspend fun updatePageAdjustments(
        page: InvoicePageEntity,
        rotationDegrees: Int,
        contrastBoost: Boolean,
        cropInsetPercent: Int
    ): Result<InvoicePageEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val normalizedRot = ((rotationDegrees % 360) + 360) % 360
            val clampedCrop = cropInsetPercent.coerceIn(0, 20)
            val originalFile = File(page.originalLocalPath)
            val editedFile = File(page.editedLocalPath)
            val bytes = renderAdjustedJpeg(originalFile, normalizedRot, contrastBoost, clampedCrop)
            editedFile.writeBytes(bytes)
            val sha = BusinessRules.sha256Hex(bytes)
            page.copy(
                rotationDegrees = normalizedRot,
                contrastBoost = contrastBoost,
                cropInsetPercent = clampedCrop,
                editedSha256 = sha,
                byteSize = originalFile.length() + editedFile.length()
            )
        }
    }

    fun decodeScaledBitmap(path: String, maxDim: Int = MAX_DIMENSION_PX): Bitmap? {
        val file = File(path)
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var inSample = 1
        while (bounds.outWidth / inSample > maxDim * 2 || bounds.outHeight / inSample > maxDim * 2) {
            inSample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = inSample }
        val raw = BitmapFactory.decodeFile(path, opts) ?: return null
        val maxSide = maxOf(raw.width, raw.height)
        if (maxSide <= maxDim) return raw
        val ratio = maxDim.toFloat() / maxSide.toFloat()
        val scaled = Bitmap.createScaledBitmap(
            raw,
            (raw.width * ratio).toInt().coerceAtLeast(1),
            (raw.height * ratio).toInt().coerceAtLeast(1),
            true
        )
        if (scaled != raw) raw.recycle()
        return scaled
    }

    private fun renderAdjustedJpeg(
        originalFile: File,
        rotationDegrees: Int,
        contrastBoost: Boolean,
        cropInsetPercent: Int
    ): ByteArray {
        var bmp = decodeScaledBitmap(originalFile.absolutePath, MAX_DIMENSION_PX)
            ?: throw IllegalStateException("Cannot decode invoice image.")

        if (rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            if (rotated != bmp) bmp.recycle()
            bmp = rotated
        }

        if (cropInsetPercent > 0) {
            val dx = (bmp.width * cropInsetPercent / 100f).toInt()
            val dy = (bmp.height * cropInsetPercent / 100f).toInt()
            val w = (bmp.width - 2 * dx).coerceAtLeast(64)
            val h = (bmp.height - 2 * dy).coerceAtLeast(64)
            val cropped = Bitmap.createBitmap(bmp, dx, dy, w, h)
            if (cropped != bmp) bmp.recycle()
            bmp = cropped
        }

        if (contrastBoost) {
            val enhanced = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(enhanced)
            val contrast = 1.32f
            val translate = (-.5f * contrast + .5f) * 255f + 12f
            val cm = ColorMatrix(
                floatArrayOf(
                    contrast, 0f, 0f, 0f, translate,
                    0f, contrast, 0f, 0f, translate,
                    0f, 0f, contrast, 0f, translate,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
            canvas.drawBitmap(bmp, 0f, 0f, paint)
            bmp.recycle()
            bmp = enhanced
        }

        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        bmp.recycle()
        return out.toByteArray()
    }
}
