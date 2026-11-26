package com.delifar.app.documents

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.core.content.FileProvider
import com.delifar.app.data.InvoiceWithPages
import com.delifar.app.domain.BusinessRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.ZoneId

data class SignatureStrokePoint(val x: Float, val y: Float)

data class GeneratedSignedPdf(
    val signaturePngPath: String,
    val signedPdfPath: String,
    val signedPdfSha256: String,
    val totalBytes: Long
)

/**
 * Generates high-readability signed invoice PDFs embedding all invoice pages, the positioned
 * handwritten customer signature, delivery reference, recipient attestation, employee name,
 * and distinct local vs. server-confirmed timestamps.
 *
 * Explicitly clarifies that the embedded signature is an electronic handwritten receipt
 * acknowledgment and NOT a certified cryptographic digital signature.
 */
object SignedPdfGenerator {

    private const val PAGE_WIDTH = 1240  // ~150 DPI US Letter
    private const val PAGE_HEIGHT = 1754

    suspend fun generateSignedPdf(
        context: Context,
        invoiceWithPages: InvoiceWithPages,
        strokes: List<List<SignatureStrokePoint>>,
        canvasWidth: Float,
        canvasHeight: Float,
        signaturePageIndex: Int,
        signaturePosX: Float,
        signaturePosY: Float,
        recipientName: String,
        recipientTitle: String,
        employeeName: String,
        deliveryReference: String,
        signedLocalAt: Long,
        zoneId: ZoneId
    ): Result<GeneratedSignedPdf> = withContext(Dispatchers.IO) {
        runCatching {
            val invoice = invoiceWithPages.invoice
            val dir = DocumentProcessor.invoiceDir(context, invoice.id)
            val sigFile = File(dir, "signature_v${invoice.version}.png")
            val pdfFile = File(dir, "signed_${invoice.invoiceNumber.replace(Regex("[^A-Za-z0-9_-]"), "_")}_v${invoice.version}.pdf")

            // 1. Render crisp transparent signature PNG
            val sigBitmap = renderSignatureBitmap(strokes, canvasWidth, canvasHeight)
            val pdfDoc = PdfDocument()
            try {
                FileOutputStream(sigFile).use { out ->
                    sigBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }

                // 2. Build multi-page PDF containing edited invoice pages + positioned signature + attestation footer
                val pages = invoiceWithPages.sortedPages
                val clampedSigPage = signaturePageIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))

                pages.forEachIndexed { idx, pageEntity ->
                    val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, idx + 1).create()
                    val pdfPage = pdfDoc.startPage(pageInfo)
                    val canvas = pdfPage.canvas
                    canvas.drawColor(Color.WHITE)

                    val margin = 48f
                    val headerHeight = 72f
                    val contentTop = margin + headerHeight
                    val contentBottom = PAGE_HEIGHT - margin - 52f
                    val contentRect = RectF(margin, contentTop, PAGE_WIDTH - margin, contentBottom)

                    // Header bar
                    val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.parseColor("#183A37")
                        textSize = 26f
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    }
                    val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.parseColor("#57636B")
                        textSize = 18f
                    }
                    canvas.drawText(
                        "DeliFar Invoice #${invoice.invoiceNumber} (v${invoice.version})",
                        margin,
                        margin + 28f,
                        headerPaint
                    )
                    canvas.drawText(
                        "${invoice.storeName}  •  Page ${idx + 1} of ${pages.size + 1}",
                        margin,
                        margin + 56f,
                        subPaint
                    )

                    // Invoice page bitmap scaled to fit cleanly
                    val pageBmp = DocumentProcessor.decodeScaledBitmap(pageEntity.editedLocalPath, 1600)
                    if (pageBmp != null) {
                        try {
                            val scale = minOf(
                                contentRect.width() / pageBmp.width.toFloat(),
                                contentRect.height() / pageBmp.height.toFloat()
                            )
                            val drawW = pageBmp.width * scale
                            val drawH = pageBmp.height * scale
                            val left = contentRect.left + (contentRect.width() - drawW) / 2f
                            val top = contentRect.top + (contentRect.height() - drawH) / 2f
                            val destRect = RectF(left, top, left + drawW, top + drawH)
                            canvas.drawBitmap(pageBmp, null, destRect, Paint(Paint.FILTER_BITMAP_FLAG))

                            // Overlay positioned handwritten signature on chosen invoice page
                            if (idx == clampedSigPage) {
                                val sigW = (destRect.width() * 0.34f).coerceIn(180f, 360f)
                                val sigH = sigW * (sigBitmap.height.toFloat() / sigBitmap.width.toFloat())
                                val cx = destRect.left + destRect.width() * signaturePosX.coerceIn(0.15f, 0.85f)
                                val cy = destRect.top + destRect.height() * signaturePosY.coerceIn(0.15f, 0.88f)
                                val sigRect = RectF(cx - sigW / 2f, cy - sigH / 2f, cx + sigW / 2f, cy + sigH / 2f)

                                val boxBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                    color = Color.argb(220, 255, 255, 255)
                                    style = Paint.Style.FILL
                                }
                                val boxStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                    color = Color.parseColor("#183A37")
                                    style = Paint.Style.STROKE
                                    strokeWidth = 2f
                                }
                                val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                    color = Color.parseColor("#183A37")
                                    textSize = 15f
                                }
                                val outerRect = RectF(sigRect.left - 8f, sigRect.top - 8f, sigRect.right + 8f, sigRect.bottom + 36f)
                                canvas.drawRoundRect(outerRect, 8f, 8f, boxBg)
                                canvas.drawRoundRect(outerRect, 8f, 8f, boxStroke)
                                canvas.drawBitmap(sigBitmap, null, sigRect, Paint(Paint.FILTER_BITMAP_FLAG))
                                canvas.drawText(
                                    "Signed: $recipientName (${BusinessRules.formatTimestamp(signedLocalAt, zoneId)})",
                                    sigRect.left,
                                    sigRect.bottom + 22f,
                                    labelPaint
                                )
                            }
                        } finally {
                            if (!pageBmp.isRecycled) pageBmp.recycle()
                        }
                    }
                    pdfDoc.finishPage(pdfPage)
                }

                // 3. Dedicated Delivery & Electronic Signature Attestation Page
                val attPageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pages.size + 1).create()
                val attPage = pdfDoc.startPage(attPageInfo)
                val ac = attPage.canvas
                ac.drawColor(Color.WHITE)

                val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#183A37")
                    textSize = 34f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                }
                val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#57636B")
                    textSize = 20f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                }
                val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#14191D")
                    textSize = 22f
                }
                val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#CBD5E1")
                    style = Paint.Style.STROKE
                    strokeWidth = 2f
                }

                var y = 96f
                val x = 64f
                ac.drawText("Delivery Acknowledgment & Signature Record", x, y, titlePaint)
                y += 48f

                val rows = listOf(
                    "Invoice Number" to "${invoice.invoiceNumber} (Version ${invoice.version})",
                    "Store / Business" to invoice.storeName,
                    "Delivery Reference" to deliveryReference,
                    "Employee / Driver" to employeeName.ifBlank { "DeliFar Operator" },
                    "Recipient Full Name" to recipientName,
                    "Recipient Title / Role" to recipientTitle.ifBlank { "Not specified" },
                    "Acknowledgment" to "Confirmed: Recipient acknowledged receiving the delivery in full/as noted.",
                    "Local Capture Timestamp" to BusinessRules.formatTimestamp(signedLocalAt, zoneId),
                    "Server-Confirmed Sync Time" to (invoice.signedServerAt?.let {
                        BusinessRules.formatTimestamp(it, zoneId)
                    } ?: "Pending cloud synchronization (recorded on server upon sync)")
                )
                for ((label, value) in rows) {
                    ac.drawText(label, x, y, labelPaint)
                    y += 30f
                    ac.drawText(value, x, y, valuePaint)
                    y += 44f
                }

                y += 16f
                ac.drawText("Handwritten Customer Signature", x, y, labelPaint)
                y += 20f
                val sigBox = RectF(x, y, PAGE_WIDTH - x, y + 260f)
                ac.drawRoundRect(sigBox, 12f, 12f, borderPaint)
                val sigDrawRect = RectF(sigBox.left + 24f, sigBox.top + 16f, sigBox.right - 24f, sigBox.bottom - 16f)
                ac.drawBitmap(sigBitmap, null, sigDrawRect, Paint(Paint.FILTER_BITMAP_FLAG))
                y = sigBox.bottom + 54f

                val disclaimerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#64748B")
                    textSize = 17f
                }
                ac.drawText(
                    "Electronic Receipt Notice: This handwritten signature was captured electronically on a mobile device",
                    x, y, disclaimerPaint
                )
                y += 26f
                ac.drawText(
                    "as proof of delivery acknowledgment. It is NOT a certified cryptographic digital signature.",
                    x, y, disclaimerPaint
                )
                y += 26f
                ac.drawText(
                    "Original invoice photographs are preserved separately. Signed records are immutable; any correction",
                    x, y, disclaimerPaint
                )
                y += 26f
                ac.drawText(
                    "must be issued as a new version with full audit trail.",
                    x, y, disclaimerPaint
                )

                pdfDoc.finishPage(attPage)

                FileOutputStream(pdfFile).use { out ->
                    pdfDoc.writeTo(out)
                }
            } finally {
                if (!sigBitmap.isRecycled) sigBitmap.recycle()
                runCatching { pdfDoc.close() }
            }

            val sha256 = com.delifar.app.cloud.InvoiceSyncValidator.computeFileSha256(pdfFile)

            GeneratedSignedPdf(
                signaturePngPath = sigFile.absolutePath,
                signedPdfPath = pdfFile.absolutePath,
                signedPdfSha256 = sha256,
                totalBytes = sigFile.length() + pdfFile.length()
            )
        }
    }

    private fun renderSignatureBitmap(
        strokes: List<List<SignatureStrokePoint>>,
        sourceWidth: Float,
        sourceHeight: Float
    ): Bitmap {
        val outW = 900
        val outH = 340
        val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.TRANSPARENT)

        val safeW = sourceWidth.coerceAtLeast(1f)
        val safeH = sourceHeight.coerceAtLeast(1f)
        val scaleX = outW / safeW
        val scaleY = outH / safeH

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            style = Paint.Style.STROKE
            strokeWidth = 7f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        for (stroke in strokes) {
            if (stroke.isEmpty()) continue
            val path = Path()
            val first = stroke.first()
            path.moveTo(first.x * scaleX, first.y * scaleY)
            if (stroke.size == 1) {
                canvas.drawPoint(first.x * scaleX, first.y * scaleY, paint)
            } else {
                for (i in 1 until stroke.size) {
                    val p = stroke[i]
                    path.lineTo(p.x * scaleX, p.y * scaleY)
                }
                canvas.drawPath(path, paint)
            }
        }
        return bmp
    }

    fun shareFile(context: Context, filePath: String, mimeType: String, chooserTitle: String) {
        val file = File(filePath)
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, chooserTitle).apply {
            if (context !is android.app.Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        runCatching { context.startActivity(chooser) }
    }

    fun openFile(context: Context, filePath: String, mimeType: String) {
        val file = File(filePath)
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is android.app.Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        runCatching { context.startActivity(intent) }
    }

    fun printPdf(context: Context, pdfPath: String, jobName: String) {
        val file = File(pdfPath)
        if (!file.exists()) return
        val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
        val adapter = object : PrintDocumentAdapter() {
            override fun onLayout(
                oldAttributes: PrintAttributes?,
                newAttributes: PrintAttributes?,
                cancellationSignal: CancellationSignal?,
                callback: LayoutResultCallback,
                extras: Bundle?
            ) {
                if (cancellationSignal?.isCanceled == true) {
                    callback.onLayoutCancelled()
                    return
                }
                val info = PrintDocumentInfo.Builder(file.name)
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                    .build()
                callback.onLayoutFinished(info, true)
            }

            override fun onWrite(
                pages: Array<out PageRange>?,
                destination: ParcelFileDescriptor,
                cancellationSignal: CancellationSignal?,
                callback: WriteResultCallback
            ) {
                try {
                    FileInputStream(file).use { input ->
                        FileOutputStream(destination.fileDescriptor).use { output ->
                            input.copyTo(output)
                        }
                    }
                    callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    callback.onWriteFailed(e.message)
                }
            }
        }
        printManager.print(jobName, adapter, null)
    }
}
