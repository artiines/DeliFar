package com.delifar.app.cloud

import com.delifar.app.data.InvoiceWithPages
import com.delifar.app.domain.BusinessRules
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

enum class InvoiceUploadErrorCode {
    MISSING_FILE,
    UNREADABLE_FILE,
    EMPTY_FILE,
    OVERSIZED_FILE,
    INVALID_CONTENT_TYPE,
    CHECKSUM_MISMATCH,
    INCOMPLETE_REMOTE_ATTACHMENTS
}

class InvoiceUploadException(
    val code: InvoiceUploadErrorCode,
    message: String
) : IllegalStateException(message)

data class ValidatedAttachment(
    val file: File,
    val byteSize: Long,
    val contentType: String,
    val sha256: String
)

/**
 * Enforces strict attachment existence, readability, size, magic-byte content-type,
 * and SHA-256 integrity checks before any invoice binary is uploaded or any invoice
 * record is permitted to transition to SYNCHRONIZED.
 */
object InvoiceSyncValidator {
    const val MAX_INVOICE_FILE_BYTES: Long = 15L * 1024L * 1024L

    private val ALLOWED_MIME_TYPES = setOf("image/jpeg", "image/png", "application/pdf")

    fun validateLocalAttachment(
        localPath: String?,
        label: String,
        expectedMimeType: String,
        expectedSha256: String? = null,
        maxBytes: Long = MAX_INVOICE_FILE_BYTES
    ): ValidatedAttachment {
        if (localPath.isNullOrBlank()) {
            throw InvoiceUploadException(
                InvoiceUploadErrorCode.MISSING_FILE,
                "Missing local file path for $label."
            )
        }
        val file = File(localPath)
        if (!file.exists() || !file.isFile) {
            throw InvoiceUploadException(
                InvoiceUploadErrorCode.MISSING_FILE,
                "Required file missing for $label (${file.name})."
            )
        }
        if (!file.canRead()) {
            throw InvoiceUploadException(
                InvoiceUploadErrorCode.UNREADABLE_FILE,
                "Cannot read local file for $label (${file.name})."
            )
        }
        val size = file.length()
        if (size <= 0L) {
            throw InvoiceUploadException(
                InvoiceUploadErrorCode.EMPTY_FILE,
                "File is empty (0 bytes) for $label (${file.name})."
            )
        }
        if (size > maxBytes) {
            throw InvoiceUploadException(
                InvoiceUploadErrorCode.OVERSIZED_FILE,
                "File exceeds ${maxBytes / (1024 * 1024)} MB limit for $label (${size / (1024 * 1024)} MB)."
            )
        }
        val normalizedMime = expectedMimeType.lowercase().trim()
        if (normalizedMime !in ALLOWED_MIME_TYPES) {
            throw InvoiceUploadException(
                InvoiceUploadErrorCode.INVALID_CONTENT_TYPE,
                "Unsupported content type '$expectedMimeType' for $label."
            )
        }

        // Verify file header magic bytes match declared content type
        val header = ByteArray(8)
        val headerRead = FileInputStream(file).use { it.read(header) }
        if (!matchesMagicBytes(header, headerRead, normalizedMime)) {
            throw InvoiceUploadException(
                InvoiceUploadErrorCode.INVALID_CONTENT_TYPE,
                "File content does not match expected $normalizedMime format for $label."
            )
        }

        // Compute SHA-256 checksum and compare against recorded hash if present
        val actualSha = computeFileSha256(file)
        val expectedClean = expectedSha256?.trim().orEmpty()
        if (expectedClean.isNotEmpty() && !actualSha.equals(expectedClean, ignoreCase = true)) {
            throw InvoiceUploadException(
                InvoiceUploadErrorCode.CHECKSUM_MISMATCH,
                "SHA-256 integrity mismatch for $label (file may be corrupted)."
            )
        }

        return ValidatedAttachment(
            file = file,
            byteSize = size,
            contentType = normalizedMime,
            sha256 = actualSha
        )
    }

    /**
     * Blocks writing invoice metadata as SYNCHRONIZED unless every required page
     * (and signature + signed PDF when signed) has a confirmed Cloud Storage path.
     */
    fun assertInvoiceReadyForMetadataSync(iwp: InvoiceWithPages) {
        val inv = iwp.invoice
        if (iwp.sortedPages.isEmpty()) {
            throw InvoiceUploadException(
                InvoiceUploadErrorCode.INCOMPLETE_REMOTE_ATTACHMENTS,
                "Invoice #${inv.invoiceNumber} has no pages and cannot be marked synchronized."
            )
        }
        for (page in iwp.sortedPages) {
            if (page.originalStoragePath.isNullOrBlank()) {
                throw InvoiceUploadException(
                    InvoiceUploadErrorCode.INCOMPLETE_REMOTE_ATTACHMENTS,
                    "Original scan for page ${page.pageIndex + 1} has not finished uploading."
                )
            }
            if (page.editedStoragePath.isNullOrBlank()) {
                throw InvoiceUploadException(
                    InvoiceUploadErrorCode.INCOMPLETE_REMOTE_ATTACHMENTS,
                    "Edited scan for page ${page.pageIndex + 1} has not finished uploading."
                )
            }
        }
        if (inv.signed) {
            if (inv.signatureStoragePath.isNullOrBlank()) {
                throw InvoiceUploadException(
                    InvoiceUploadErrorCode.INCOMPLETE_REMOTE_ATTACHMENTS,
                    "Customer signature PNG has not finished uploading for invoice #${inv.invoiceNumber}."
                )
            }
            if (inv.signedPdfStoragePath.isNullOrBlank()) {
                throw InvoiceUploadException(
                    InvoiceUploadErrorCode.INCOMPLETE_REMOTE_ATTACHMENTS,
                    "Signed PDF has not finished uploading for invoice #${inv.invoiceNumber}."
                )
            }
        }
    }

    /**
     * Checks local files that still need to be uploaded and returns human-readable explanations
     * for any missing, empty, oversized, or corrupted image/signature/PDF so users know exactly
     * what needs attention before retrying an upload.
     */
    fun inspectLocalAttachmentIssues(iwp: InvoiceWithPages): List<String> {
        val issues = mutableListOf<String>()
        val inv = iwp.invoice
        if (iwp.sortedPages.isEmpty()) {
            issues += "No invoice pages are attached."
            return issues
        }
        for (page in iwp.sortedPages) {
            val pageNum = page.pageIndex + 1
            if (page.originalStoragePath.isNullOrBlank()) {
                runCatching {
                    validateLocalAttachment(
                        localPath = page.originalLocalPath,
                        label = "Page $pageNum original photo",
                        expectedMimeType = page.mimeType.ifBlank { "image/jpeg" },
                        expectedSha256 = page.originalSha256
                    )
                }.onFailure { err ->
                    issues += (err.message ?: "Page $pageNum original photo is missing or unreadable.")
                }
            }
            if (page.editedStoragePath.isNullOrBlank()) {
                val editedPath = page.editedLocalPath?.ifBlank { null } ?: page.originalLocalPath
                runCatching {
                    validateLocalAttachment(
                        localPath = editedPath,
                        label = "Page $pageNum scan image",
                        expectedMimeType = page.mimeType.ifBlank { "image/jpeg" },
                        expectedSha256 = page.editedSha256?.ifBlank { null }
                    )
                }.onFailure { err ->
                    issues += (err.message ?: "Page $pageNum scan image is missing or unreadable.")
                }
            }
        }
        if (inv.signed) {
            if (inv.signatureStoragePath.isNullOrBlank()) {
                runCatching {
                    validateLocalAttachment(
                        localPath = inv.signatureLocalPath,
                        label = "Customer signature image",
                        expectedMimeType = "image/png"
                    )
                }.onFailure { err ->
                    issues += (err.message ?: "Customer signature image is missing from this device.")
                }
            }
            if (inv.signedPdfStoragePath.isNullOrBlank()) {
                runCatching {
                    validateLocalAttachment(
                        localPath = inv.signedPdfLocalPath,
                        label = "Signed invoice PDF",
                        expectedMimeType = "application/pdf",
                        expectedSha256 = inv.signedPdfSha256
                    )
                }.onFailure { err ->
                    issues += (err.message ?: "Signed invoice PDF is missing from this device.")
                }
            }
        }
        return issues
    }

    fun hasAllRemoteAttachments(iwp: InvoiceWithPages): Boolean {
        return runCatching { assertInvoiceReadyForMetadataSync(iwp) }.isSuccess
    }

    fun computeFileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val read = fis.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun matchesMagicBytes(header: ByteArray, length: Int, mimeType: String): Boolean {
        return when (mimeType) {
            "image/jpeg" -> length >= 3 &&
                (header[0].toInt() and 0xFF) == 0xFF &&
                (header[1].toInt() and 0xFF) == 0xD8 &&
                (header[2].toInt() and 0xFF) == 0xFF
            "image/png" -> length >= 8 &&
                (header[0].toInt() and 0xFF) == 0x89 &&
                header[1] == 0x50.toByte() &&
                header[2] == 0x4E.toByte() &&
                header[3] == 0x47.toByte()
            "application/pdf" -> length >= 4 &&
                header[0] == 0x25.toByte() && // %
                header[1] == 0x50.toByte() && // P
                header[2] == 0x44.toByte() && // D
                header[3] == 0x46.toByte()    // F
            else -> false
        }
    }

    /**
     * Convenience wrapper to also hash byte arrays identically to BusinessRules.
     */
    fun sha256Bytes(bytes: ByteArray): String = BusinessRules.sha256Hex(bytes)
}
