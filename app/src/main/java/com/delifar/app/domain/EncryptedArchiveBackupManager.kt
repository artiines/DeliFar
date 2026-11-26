package com.delifar.app.domain

import androidx.room.withTransaction
import com.delifar.app.cloud.InvoiceSyncValidator
import com.delifar.app.data.CompanyEntity
import com.delifar.app.data.DeliFarDatabase
import com.delifar.app.data.HoursMode
import com.delifar.app.data.InvoiceEntity
import com.delifar.app.data.InvoicePageEntity
import com.delifar.app.data.InvoiceStatus
import com.delifar.app.data.InvoiceWithPages
import com.delifar.app.data.JobEntity
import com.delifar.app.data.JobIssue
import com.delifar.app.data.JobItemEntity
import com.delifar.app.data.JobStatus
import com.delifar.app.data.JobType
import com.delifar.app.data.JobWithItems
import com.delifar.app.data.PrefKeys
import com.delifar.app.data.PreferenceEntity
import com.delifar.app.data.SearchArea
import com.delifar.app.data.StoreEntity
import com.delifar.app.data.StoreHoursEntity
import com.delifar.app.data.StoreWithHours
import com.delifar.app.data.SyncState
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class ArchiveExportSummary(
    val outputFile: File,
    val byteSize: Long,
    val companyName: String,
    val storeCount: Int,
    val jobCount: Int,
    val invoiceCount: Int,
    val attachmentCount: Int,
    val warnings: List<String>
)

data class ArchiveImportPreview(
    val exportedAt: String,
    val dbSchemaVersion: Int,
    val backupCompanyId: String,
    val backupCompanyName: String,
    val isCrossCompany: Boolean,
    val isCleanDeviceRestore: Boolean,
    val newStores: Int,
    val identicalStores: Int,
    val conflictingStores: Int,
    val newJobs: Int,
    val identicalJobs: Int,
    val conflictingJobs: Int,
    val newInvoices: Int,
    val identicalInvoices: Int,
    val conflictingInvoices: Int,
    val attachmentCount: Int,
    val preferenceCount: Int
)

data class ArchiveRestoreResult(
    val companyRestored: Boolean,
    val storesWritten: Int,
    val jobsWritten: Int,
    val invoicesWritten: Int,
    val attachmentsRestored: Int,
    val preferencesRestored: Int,
    val restoredCompanyId: String
)

/**
 * DeliFar v2 Encrypted Business-Data Backup & Recovery Manager (`.delifarbak`).
 *
 * Envelope binary layout:
 *  - Bytes 0..7   : ASCII magic "DLFBK002"
 *  - Bytes 8..23  : 16-byte cryptographically random PBKDF2 salt
 *  - Bytes 24..35 : 12-byte cryptographically random AES-GCM IV
 *  - Bytes 36..N  : AES-256-GCM ciphertext (with 128-bit auth tag) of a ZIP archive
 *
 * ZIP archive contents:
 *  - `manifest.json` : format version, db schema version, company identity, SHA-256 of data.json & every attachment
 *  - `data.json`     : company operational settings, stores + hours, jobs + items, invoices + pages, safe preferences
 *  - `files/<invoiceId>/<safeFile>` : original invoice photos, edited scans, signature PNGs, signed PDFs
 *
 * Security guarantees:
 *  - Never exports auth credentials, user email/UID secrets, FCM tokens, or Android signing keys.
 *  - Protects against ZipSlip traversal, zip bombs, corrupted ciphertext, tampered/missing attachments,
 *    duplicate IDs, broken foreign-key relations, and unauthorized cross-company merges.
 *  - Restores transactionally with file staging and automatic rollback on failure; never deletes existing data.
 */
class EncryptedArchiveBackupManager(private val db: DeliFarDatabase) {

    companion object {
        val MAGIC_BYTES: ByteArray = "DLFBK002".toByteArray(Charsets.US_ASCII)
        const val ARCHIVE_FORMAT = "DeliFarArchive"
        const val ARCHIVE_VERSION = 2
        const val CURRENT_DB_SCHEMA_VERSION = 7
        const val MIN_PASSPHRASE_LENGTH = 6
        const val PBKDF2_ITERATIONS = 120_000
        const val MAX_ARCHIVE_BYTES = 512L * 1024L * 1024L
        const val MAX_UNCOMPRESSED_BYTES = 512L * 1024L * 1024L
        const val MAX_ZIP_ENTRIES = 10_000

        private val SAFE_PREF_KEYS = setOf(
            PrefKeys.LANGUAGE,
            PrefKeys.AREA,
            PrefKeys.DAILY,
            PrefKeys.SUMMARY_MINUTE,
            PrefKeys.JOB_REMINDERS,
            PrefKeys.ADVANCE,
            PrefKeys.COMPANY_TIMEZONE,
            PrefKeys.NOTIF_NEW_ASSIGNMENT,
            PrefKeys.NOTIF_UPCOMING,
            PrefKeys.NOTIF_CHANGED_ASSIGNMENT,
            PrefKeys.NOTIF_COMPLETED,
            PrefKeys.NOTIF_SIGNED_INVOICE,
            PrefKeys.NOTIF_MISSING_SIGNATURE,
            PrefKeys.NOTIF_FAILED_UPLOAD,
            PrefKeys.NOTIF_EMPLOYEE_INVITE,
            PrefKeys.GEOCODING_URL,
            PrefKeys.ROUTING_URL,
            PrefKeys.MAP_STYLE_URL,
            PrefKeys.ALLOW_OSM_FALLBACK
        )

        private val SAFE_ENTRY_PATH_REGEX = Regex("^(manifest\\.json|data\\.json|files/[A-Za-z0-9_-]{1,128}/[A-Za-z0-9._-]{1,160})$")

        fun isEncryptedArchive(file: File): Boolean {
            if (!file.exists() || !file.isFile || file.length() < MAGIC_BYTES.size + 28) return false
            val header = ByteArray(MAGIC_BYTES.size)
            val read = runCatching { FileInputStream(file).use { it.read(header) } }.getOrDefault(0)
            return read == MAGIC_BYTES.size && header.contentEquals(MAGIC_BYTES)
        }

        fun isEncryptedArchiveBytes(bytes: ByteArray): Boolean {
            if (bytes.size < MAGIC_BYTES.size + 28) return false
            return bytes.copyOfRange(0, MAGIC_BYTES.size).contentEquals(MAGIC_BYTES)
        }

        private fun deriveAesKey(passphrase: String, salt: ByteArray): SecretKeySpec {
            require(passphrase.length >= MIN_PASSPHRASE_LENGTH) {
                "Backup passphrase must be at least $MIN_PASSPHRASE_LENGTH characters."
            }
            val spec = PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val keyBytes = factory.generateSecret(spec).encoded
            spec.clearPassword()
            return SecretKeySpec(keyBytes, "AES")
        }

        private fun encryptEnvelope(plainZipBytes: ByteArray, passphrase: String): ByteArray {
            val rng = SecureRandom()
            val salt = ByteArray(16).also { rng.nextBytes(it) }
            val iv = ByteArray(12).also { rng.nextBytes(it) }
            val key = deriveAesKey(passphrase, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(MAGIC_BYTES)
            val cipherText = cipher.doFinal(plainZipBytes)
            val out = ByteArrayOutputStream(MAGIC_BYTES.size + salt.size + iv.size + cipherText.size)
            out.write(MAGIC_BYTES)
            out.write(salt)
            out.write(iv)
            out.write(cipherText)
            return out.toByteArray()
        }

        private fun decryptEnvelope(envelope: ByteArray, passphrase: String): ByteArray {
            require(isEncryptedArchiveBytes(envelope)) {
                "File is not a valid DeliFar v2 encrypted backup archive."
            }
            val salt = envelope.copyOfRange(8, 24)
            val iv = envelope.copyOfRange(24, 36)
            val cipherText = envelope.copyOfRange(36, envelope.size)
            val key = deriveAesKey(passphrase, salt)
            return try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                cipher.updateAAD(MAGIC_BYTES)
                cipher.doFinal(cipherText)
            } catch (_: GeneralSecurityException) {
                throw SecurityException("Incorrect backup passphrase or corrupted encrypted archive.")
            }
        }

        private fun sanitizeIdSegment(raw: String): String {
            return raw.replace(Regex("[^A-Za-z0-9_-]"), "_").take(120).ifBlank { "unknown" }
        }
    }

    private data class DecodedArchive(
        val exportedAt: String,
        val dbSchemaVersion: Int,
        val company: CompanyEntity?,
        val backupCompanyId: String,
        val backupCompanyName: String,
        val stores: List<StoreWithHours>,
        val jobs: List<JobWithItems>,
        val invoices: List<InvoiceWithPages>,
        val preferences: List<PreferenceEntity>,
        val attachments: Map<String, ByteArray>
    )

    /**
     * Exports a complete, AES-256-GCM encrypted `.delifarbak` backup archive containing
     * company operational settings, stores + hours, jobs + items, invoices + pages metadata,
     * and all local binary attachments (original photos, edited pages, signatures, signed PDFs).
     */
    suspend fun exportEncryptedArchive(
        activeCompanyId: String,
        passphrase: String,
        outputFile: File
    ): Result<ArchiveExportSummary> = runCatching {
        require(passphrase.length >= MIN_PASSPHRASE_LENGTH) {
            "Passphrase must be at least $MIN_PASSPHRASE_LENGTH characters."
        }
        val company = if (activeCompanyId.isNotBlank()) db.companyDao().getCompany(activeCompanyId) else null
        val stores = db.storeDao().getStoresWithHoursByCompany(activeCompanyId)
        val jobs = db.jobDao().getJobsWithItemsByCompany(activeCompanyId)
        val invoices = db.invoiceDao().getInvoicesWithPagesByCompany(activeCompanyId)
        val allPrefs = db.preferenceDao().getAll().filter { it.key in SAFE_PREF_KEYS }

        val attachments = linkedMapOf<String, ByteArray>()
        val attachmentHashes = linkedMapOf<String, String>()
        val warnings = mutableListOf<String>()

        fun packLocalFile(invoiceId: String, fileName: String, localPath: String?, label: String): String? {
            if (localPath.isNullOrBlank()) return null
            val f = File(localPath)
            if (!f.exists() || !f.isFile || !f.canRead() || f.length() <= 0L) {
                warnings += "Skipped missing local attachment ($label)."
                return null
            }
            val relPath = "files/${sanitizeIdSegment(invoiceId)}/$fileName"
            val bytes = f.readBytes()
            val sha = InvoiceSyncValidator.sha256Bytes(bytes)
            attachments[relPath] = bytes
            attachmentHashes[relPath] = sha
            return relPath
        }

        val dataRoot = JSONObject()
        if (company != null) {
            dataRoot.put(
                "company",
                JSONObject().apply {
                    put("id", company.id)
                    put("name", company.name)
                    put("timezone", company.timezone)
                    put("defaultArea", company.defaultArea)
                    put("storageUsedBytes", company.storageUsedBytes)
                    put("storageQuotaBytes", company.storageQuotaBytes)
                    put("created", company.created)
                    put("modified", company.modified)
                }
            )
        } else {
            dataRoot.put("company", JSONObject.NULL)
        }

        val storesArr = JSONArray()
        for (swh in stores) {
            val s = swh.store
            storesArr.put(
                JSONObject().apply {
                    put(
                        "store",
                        JSONObject().apply {
                            put("id", s.id)
                            put("name", s.name)
                            put("address", s.address)
                            put("lat", s.lat)
                            put("lon", s.lon)
                            put("providerId", s.providerId ?: JSONObject.NULL)
                            put("phone", s.phone)
                            put("created", s.created)
                            put("modified", s.modified)
                            put("archived", s.archived)
                            put("version", s.version)
                            put("locationConfirmed", s.locationConfirmed)
                            put("geocodeSource", s.geocodeSource)
                        }
                    )
                    put(
                        "hours",
                        JSONArray().apply {
                            swh.hours.sortedBy { it.day }.forEach { h ->
                                put(
                                    JSONObject().apply {
                                        put("storeId", h.storeId)
                                        put("day", h.day)
                                        put("mode", h.mode)
                                        put("windows", h.windows)
                                    }
                                )
                            }
                        }
                    )
                }
            )
        }
        dataRoot.put("stores", storesArr)

        val jobsArr = JSONArray()
        for (jwi in jobs) {
            val j = jwi.job
            jobsArr.put(
                JSONObject().apply {
                    put(
                        "job",
                        JSONObject().apply {
                            put("id", j.id)
                            put("storeId", j.storeId)
                            put("type", j.type)
                            put("date", j.date)
                            put("time", j.time ?: JSONObject.NULL)
                            put("status", j.status)
                            put("issue", j.issue ?: JSONObject.NULL)
                            put("issueNote", j.issueNote)
                            put("snapshotName", j.snapshotName)
                            put("snapshotAddress", j.snapshotAddress)
                            put("snapshotLat", j.snapshotLat)
                            put("snapshotLon", j.snapshotLon)
                            put("created", j.created)
                            put("modified", j.modified)
                            put("completed", j.completed ?: JSONObject.NULL)
                            put("reminderVersion", j.reminderVersion)
                            put("reminderEnabled", j.reminderEnabled)
                            put("assignedDriverUid", j.assignedDriverUid ?: JSONObject.NULL)
                            put("assignedDriverName", j.assignedDriverName)
                            put("notes", j.notes)
                            put("version", j.version)
                        }
                    )
                    put(
                        "items",
                        JSONArray().apply {
                            jwi.sortedItems.forEach { item ->
                                put(
                                    JSONObject().apply {
                                        put("id", item.id)
                                        put("jobId", item.jobId)
                                        put("position", item.position)
                                        put("name", item.name)
                                        put("planned", item.planned)
                                        put("unit", item.unit)
                                        put("actual", item.actual ?: JSONObject.NULL)
                                    }
                                )
                            }
                        }
                    )
                }
            )
        }
        dataRoot.put("jobs", jobsArr)

        val invoicesArr = JSONArray()
        for (iwp in invoices) {
            val inv = iwp.invoice
            val sigArchivePath = packLocalFile(
                inv.id,
                "signature.png",
                inv.signatureLocalPath,
                "invoice #${inv.invoiceNumber} signature"
            )
            val pdfArchivePath = packLocalFile(
                inv.id,
                "signed_v${inv.version}.pdf",
                inv.signedPdfLocalPath,
                "invoice #${inv.invoiceNumber} signed PDF"
            )
            val pagesArr = JSONArray()
            for (pg in iwp.sortedPages) {
                val origExt = if (pg.mimeType.equals("image/png", ignoreCase = true)) "png" else "jpg"
                val origArchivePath = packLocalFile(
                    inv.id,
                    "orig_${sanitizeIdSegment(pg.id)}.$origExt",
                    pg.originalLocalPath,
                    "invoice #${inv.invoiceNumber} page ${pg.pageIndex + 1} original"
                )
                val editArchivePath = packLocalFile(
                    inv.id,
                    "edit_${sanitizeIdSegment(pg.id)}.jpg",
                    pg.editedLocalPath,
                    "invoice #${inv.invoiceNumber} page ${pg.pageIndex + 1} edited"
                )
                pagesArr.put(
                    JSONObject().apply {
                        put("id", pg.id)
                        put("invoiceId", pg.invoiceId)
                        put("pageIndex", pg.pageIndex)
                        put("originalArchivePath", origArchivePath ?: JSONObject.NULL)
                        put("editedArchivePath", editArchivePath ?: JSONObject.NULL)
                        put("originalStoragePath", pg.originalStoragePath ?: JSONObject.NULL)
                        put("editedStoragePath", pg.editedStoragePath ?: JSONObject.NULL)
                        put("originalSha256", pg.originalSha256)
                        put("editedSha256", pg.editedSha256)
                        put("rotationDegrees", pg.rotationDegrees)
                        put("contrastBoost", pg.contrastBoost)
                        put("cropInsetPercent", pg.cropInsetPercent)
                        put("byteSize", pg.byteSize)
                        put("mimeType", pg.mimeType)
                    }
                )
            }
            invoicesArr.put(
                JSONObject().apply {
                    put(
                        "invoice",
                        JSONObject().apply {
                            put("id", inv.id)
                            put("jobId", inv.jobId ?: JSONObject.NULL)
                            put("storeId", inv.storeId)
                            put("storeName", inv.storeName)
                            put("invoiceNumber", inv.invoiceNumber)
                            put("notes", inv.notes)
                            put("status", inv.status)
                            put("version", inv.version)
                            put("supersedesInvoiceId", inv.supersedesInvoiceId ?: JSONObject.NULL)
                            put("voidedReason", inv.voidedReason ?: JSONObject.NULL)
                            put("createdByName", inv.createdByName)
                            put("createdAt", inv.createdAt)
                            put("modifiedAt", inv.modifiedAt)
                            put("signed", inv.signed)
                            put("recipientName", inv.recipientName)
                            put("recipientTitle", inv.recipientTitle)
                            put("acknowledgmentConfirmed", inv.acknowledgmentConfirmed)
                            put("signatureArchivePath", sigArchivePath ?: JSONObject.NULL)
                            put("signatureStoragePath", inv.signatureStoragePath ?: JSONObject.NULL)
                            put("signaturePageIndex", inv.signaturePageIndex)
                            put("signaturePosX", inv.signaturePosX.toDouble())
                            put("signaturePosY", inv.signaturePosY.toDouble())
                            put("signedLocalAt", inv.signedLocalAt ?: JSONObject.NULL)
                            put("signedServerAt", inv.signedServerAt ?: JSONObject.NULL)
                            put("signedPdfArchivePath", pdfArchivePath ?: JSONObject.NULL)
                            put("signedPdfStoragePath", inv.signedPdfStoragePath ?: JSONObject.NULL)
                            put("signedPdfSha256", inv.signedPdfSha256 ?: JSONObject.NULL)
                            put("totalBytes", inv.totalBytes)
                        }
                    )
                    put("pages", pagesArr)
                }
            )
        }
        dataRoot.put("invoices", invoicesArr)

        val prefsArr = JSONArray()
        allPrefs.sortedBy { it.key }.forEach { pref ->
            prefsArr.put(
                JSONObject().apply {
                    put("key", pref.key)
                    put("value", pref.value)
                }
            )
        }
        dataRoot.put("preferences", prefsArr)

        val dataBytes = dataRoot.toString(2).toByteArray(Charsets.UTF_8)
        val dataSha256 = InvoiceSyncValidator.sha256Bytes(dataBytes)
        val exportedIso = Instant.now().toString()

        val manifestObj = JSONObject().apply {
            put("format", ARCHIVE_FORMAT)
            put("version", ARCHIVE_VERSION)
            put("dbSchemaVersion", CURRENT_DB_SCHEMA_VERSION)
            put("exported", exportedIso)
            put("companyId", company?.id ?: activeCompanyId)
            put("companyName", company?.name ?: "Local Workspace")
            put("dataSha256", dataSha256)
            put(
                "files",
                JSONObject().apply {
                    attachmentHashes.forEach { (relPath, sha) -> put(relPath, sha) }
                }
            )
        }
        val manifestBytes = manifestObj.toString(2).toByteArray(Charsets.UTF_8)

        // Assemble deterministic ZIP archive in memory
        val zipOutStream = ByteArrayOutputStream()
        ZipOutputStream(zipOutStream).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(manifestBytes)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("data.json"))
            zos.write(dataBytes)
            zos.closeEntry()

            attachments.forEach { (relPath, bytes) ->
                zos.putNextEntry(ZipEntry(relPath))
                zos.write(bytes)
                zos.closeEntry()
            }
        }

        val encryptedBytes = encryptEnvelope(zipOutStream.toByteArray(), passphrase)
        outputFile.parentFile?.mkdirs()
        val tmpFile = File(outputFile.parentFile, "${outputFile.name}.tmp")
        FileOutputStream(tmpFile).use { it.write(encryptedBytes) }
        if (!tmpFile.renameTo(outputFile)) {
            outputFile.writeBytes(encryptedBytes)
            tmpFile.delete()
        }

        ArchiveExportSummary(
            outputFile = outputFile,
            byteSize = outputFile.length(),
            companyName = company?.name ?: "Local Workspace",
            storeCount = stores.size,
            jobCount = jobs.size,
            invoiceCount = invoices.size,
            attachmentCount = attachments.size,
            warnings = warnings
        )
    }

    /**
     * Decrypts, verifies integrity (AES-GCM tag, ZipSlip paths, manifest SHA-256 for data.json &
     * every attachment, relational consistency), and builds a conflict preview without modifying data.
     */
    suspend fun inspectEncryptedArchive(
        archiveFile: File,
        passphrase: String,
        activeCompanyId: String = ""
    ): Result<ArchiveImportPreview> = runCatching {
        val decoded = decodeAndVerifyArchive(archiveFile, passphrase)
        val isCleanDevice = activeCompanyId.isBlank() &&
            db.storeDao().getAllStoresWithHours().isEmpty() &&
            db.jobDao().getAllJobsWithItems().isEmpty() &&
            db.invoiceDao().getAllInvoicesWithPages().isEmpty()
        val isCrossCompany = activeCompanyId.isNotBlank() &&
            decoded.backupCompanyId.isNotBlank() &&
            decoded.backupCompanyId != activeCompanyId

        val compareCompanyId = if (isCleanDevice) decoded.backupCompanyId else activeCompanyId
        val existingStores = db.storeDao().getStoresWithHoursByCompany(compareCompanyId).associateBy { it.store.id }
        val existingJobs = db.jobDao().getJobsWithItemsByCompany(compareCompanyId).associateBy { it.job.id }
        val existingInvoices = db.invoiceDao().getInvoicesWithPagesByCompany(compareCompanyId).associateBy { it.invoice.id }

        var newStores = 0
        var identicalStores = 0
        var conflictingStores = 0
        for (incoming in decoded.stores) {
            val cur = existingStores[incoming.store.id]
            when {
                cur == null -> newStores++
                cur.store.name == incoming.store.name &&
                    cur.store.address == incoming.store.address &&
                    cur.store.lat == incoming.store.lat &&
                    cur.store.lon == incoming.store.lon &&
                    cur.store.archived == incoming.store.archived -> identicalStores++
                else -> conflictingStores++
            }
        }

        var newJobs = 0
        var identicalJobs = 0
        var conflictingJobs = 0
        for (incoming in decoded.jobs) {
            val cur = existingJobs[incoming.job.id]
            when {
                cur == null -> newJobs++
                cur.job.status == incoming.job.status &&
                    cur.job.date == incoming.job.date &&
                    cur.job.time == incoming.job.time &&
                    cur.sortedItems == incoming.sortedItems -> identicalJobs++
                else -> conflictingJobs++
            }
        }

        var newInvoices = 0
        var identicalInvoices = 0
        var conflictingInvoices = 0
        for (incoming in decoded.invoices) {
            val cur = existingInvoices[incoming.invoice.id]
            when {
                cur == null -> newInvoices++
                cur.invoice.invoiceNumber == incoming.invoice.invoiceNumber &&
                    cur.invoice.signed == incoming.invoice.signed &&
                    cur.invoice.status == incoming.invoice.status &&
                    cur.invoice.version == incoming.invoice.version &&
                    cur.invoice.signedPdfSha256 == incoming.invoice.signedPdfSha256 -> identicalInvoices++
                else -> conflictingInvoices++
            }
        }

        ArchiveImportPreview(
            exportedAt = decoded.exportedAt,
            dbSchemaVersion = decoded.dbSchemaVersion,
            backupCompanyId = decoded.backupCompanyId,
            backupCompanyName = decoded.backupCompanyName,
            isCrossCompany = isCrossCompany,
            isCleanDeviceRestore = isCleanDevice,
            newStores = newStores,
            identicalStores = identicalStores,
            conflictingStores = conflictingStores,
            newJobs = newJobs,
            identicalJobs = identicalJobs,
            conflictingJobs = conflictingJobs,
            newInvoices = newInvoices,
            identicalInvoices = identicalInvoices,
            conflictingInvoices = conflictingInvoices,
            attachmentCount = decoded.attachments.size,
            preferenceCount = decoded.preferences.size
        )
    }

    /**
     * Transactionally restores an encrypted `.delifarbak` backup archive (records + binary invoice files).
     * Never deletes existing user data. Rolls back both Room and staged files if any failure occurs.
     */
    suspend fun restoreEncryptedArchive(
        archiveFile: File,
        passphrase: String,
        targetFilesDir: File,
        replaceConflicts: Boolean,
        restorePreferences: Boolean,
        activeCompanyId: String = "",
        allowCrossCompanyRestore: Boolean = false,
        beforeCommitHook: (() -> Unit)? = null
    ): Result<ArchiveRestoreResult> = runCatching {
        val decoded = decodeAndVerifyArchive(archiveFile, passphrase)

        val isCrossCompany = activeCompanyId.isNotBlank() &&
            decoded.backupCompanyId.isNotBlank() &&
            decoded.backupCompanyId != activeCompanyId
        if (isCrossCompany && !allowCrossCompanyRestore) {
            throw SecurityException(
                "Cross-company restore blocked: backup belongs to '${decoded.backupCompanyName}' (${decoded.backupCompanyId}), " +
                    "while active company is '$activeCompanyId'."
            )
        }

        val targetCompanyId = when {
            activeCompanyId.isNotBlank() -> activeCompanyId
            decoded.backupCompanyId.isNotBlank() -> decoded.backupCompanyId
            else -> ""
        }

        val stagingDir = File(targetFilesDir, "restore_staging_${UUID.randomUUID().toString().take(8)}")
        val movedFiles = mutableListOf<File>()
        val backedUpOriginals = mutableListOf<Pair<File, File>>()

        try {
            // 1. Stage all attachments and verify every written byte matches expected SHA-256
            stagingDir.mkdirs()
            val stagedMap = mutableMapOf<String, File>()
            for ((relPath, bytes) in decoded.attachments) {
                val subPath = relPath.removePrefix("files/")
                val stagedFile = File(stagingDir, subPath)
                val canonicalStaging = stagingDir.canonicalPath
                require(stagedFile.canonicalPath.startsWith("$canonicalStaging${File.separator}")) {
                    "Unsafe staging path rejected: $relPath"
                }
                stagedFile.parentFile?.mkdirs()
                stagedFile.writeBytes(bytes)
                stagedMap[relPath] = stagedFile
            }

            // 2. Resolve destination paths under targetFilesDir/invoices/
            val invoicesRoot = File(targetFilesDir, "invoices").apply { mkdirs() }
            val resolvedPaths = mutableMapOf<String, String>()
            val pendingMoves = mutableListOf<Pair<File, File>>()
            for ((relPath, stagedFile) in stagedMap) {
                val subPath = relPath.removePrefix("files/")
                val destFile = File(invoicesRoot, subPath)
                val canonicalInvoices = invoicesRoot.canonicalPath
                require(destFile.canonicalPath.startsWith("$canonicalInvoices${File.separator}")) {
                    "Unsafe destination path rejected: $relPath"
                }
                resolvedPaths[relPath] = destFile.absolutePath
                pendingMoves += stagedFile to destFile
            }

            // 3. Move staged files into destination (preserving rollback copies if an existing file is replaced)
            for ((src, dst) in pendingMoves) {
                dst.parentFile?.mkdirs()
                if (dst.exists()) {
                    val backupCopy = File(stagingDir, "rollback_${UUID.randomUUID().toString().take(8)}_${dst.name}")
                    dst.copyTo(backupCopy, overwrite = true)
                    backedUpOriginals += dst to backupCopy
                }
                src.copyTo(dst, overwrite = true)
                movedFiles += dst
            }

            var companyRestored = false
            var storesWritten = 0
            var jobsWritten = 0
            var invoicesWritten = 0
            var prefsWritten = 0

            // 4. Transactional Room merge
            db.withTransaction {
                if (decoded.company != null) {
                    val existingCompany = db.companyDao().getCompany(targetCompanyId.ifBlank { decoded.company.id })
                    if (existingCompany == null) {
                        db.companyDao().upsert(
                            decoded.company.copy(id = targetCompanyId.ifBlank { decoded.company.id })
                        )
                        companyRestored = true
                    } else if (replaceConflicts) {
                        db.companyDao().upsert(
                            existingCompany.copy(
                                name = decoded.company.name,
                                timezone = decoded.company.timezone,
                                defaultArea = decoded.company.defaultArea,
                                modified = maxOf(existingCompany.modified, decoded.company.modified)
                            )
                        )
                        companyRestored = true
                    }
                    if (activeCompanyId.isBlank() && targetCompanyId.isNotBlank()) {
                        db.preferenceDao().upsert(PreferenceEntity(PrefKeys.ACTIVE_COMPANY_ID, targetCompanyId))
                    }
                }

                val existingStores = db.storeDao().getStoresWithHoursByCompany(targetCompanyId).associateBy { it.store.id }
                val existingJobs = db.jobDao().getJobsWithItemsByCompany(targetCompanyId).associateBy { it.job.id }
                val existingInvoices = db.invoiceDao().getInvoicesWithPagesByCompany(targetCompanyId).associateBy { it.invoice.id }

                val finalStores = existingStores.mapValues { it.value.store }.toMutableMap()
                val storesToWrite = mutableListOf<StoreWithHours>()
                for (incoming in decoded.stores) {
                    val cur = existingStores[incoming.store.id]
                    val shouldWrite = when {
                        cur == null -> true
                        cur.store.name == incoming.store.name &&
                            cur.store.address == incoming.store.address &&
                            cur.store.lat == incoming.store.lat &&
                            cur.store.lon == incoming.store.lon &&
                            cur.store.archived == incoming.store.archived &&
                            cur.hours.sortedBy { it.day } == incoming.hours.sortedBy { it.day } -> false
                        else -> replaceConflicts
                    }
                    if (shouldWrite) {
                        val merged = incoming.store.copy(
                            companyId = targetCompanyId,
                            syncState = SyncState.PENDING
                        )
                        finalStores[merged.id] = merged
                        storesToWrite += incoming.copy(store = merged)
                    }
                }

                // Check scoped providerId uniqueness
                val seenProviders = mutableSetOf<String>()
                for (st in finalStores.values) {
                    val pid = st.providerId
                    if (!pid.isNullOrBlank()) {
                        require(seenProviders.add("${st.companyId}|$pid")) {
                            "Merge conflict: duplicate providerId '$pid' across different stores."
                        }
                    }
                }

                val jobsToWrite = mutableListOf<JobWithItems>()
                for (incoming in decoded.jobs) {
                    val cur = existingJobs[incoming.job.id]
                    val shouldWrite = when {
                        cur == null -> true
                        cur.job.status == incoming.job.status &&
                            cur.job.date == incoming.job.date &&
                            cur.job.time == incoming.job.time &&
                            cur.sortedItems == incoming.sortedItems -> false
                        else -> replaceConflicts
                    }
                    if (shouldWrite) {
                        val st = finalStores[incoming.job.storeId]
                            ?: throw IllegalStateException("Missing store ${incoming.job.storeId} for job ${incoming.job.id}")
                        if (incoming.job.status in JobStatus.ACTIVE && st.archived) {
                            throw IllegalStateException("Cannot restore active job into archived store '${st.name}'.")
                        }
                        jobsToWrite += incoming.copy(
                            job = incoming.job.copy(
                                companyId = targetCompanyId,
                                syncState = SyncState.PENDING
                            )
                        )
                    }
                }

                val invoicesToWrite = mutableListOf<InvoiceWithPages>()
                for (incoming in decoded.invoices) {
                    val cur = existingInvoices[incoming.invoice.id]
                    val shouldWrite = when {
                        cur == null -> true
                        cur.invoice.signed && !replaceConflicts -> false
                        cur.invoice.invoiceNumber == incoming.invoice.invoiceNumber &&
                            cur.invoice.signed == incoming.invoice.signed &&
                            cur.invoice.status == incoming.invoice.status &&
                            cur.invoice.version == incoming.invoice.version &&
                            cur.invoice.signedPdfSha256 == incoming.invoice.signedPdfSha256 -> false
                        else -> replaceConflicts
                    }
                    if (shouldWrite) {
                        val mappedInv = incoming.invoice.copy(
                            companyId = targetCompanyId,
                            signatureLocalPath = incoming.invoice.signatureLocalPath?.let { resolvedPaths[it] ?: it },
                            signedPdfLocalPath = incoming.invoice.signedPdfLocalPath?.let { resolvedPaths[it] ?: it },
                            signatureStoragePath = if (isCrossCompany) null else incoming.invoice.signatureStoragePath,
                            signedPdfStoragePath = if (isCrossCompany) null else incoming.invoice.signedPdfStoragePath,
                            syncState = SyncState.PENDING
                        )
                        val mappedPages = incoming.sortedPages.map { pg ->
                            pg.copy(
                                originalLocalPath = resolvedPaths[pg.originalLocalPath] ?: pg.originalLocalPath,
                                editedLocalPath = resolvedPaths[pg.editedLocalPath] ?: pg.editedLocalPath,
                                originalStoragePath = if (isCrossCompany) null else pg.originalStoragePath,
                                editedStoragePath = if (isCrossCompany) null else pg.editedStoragePath
                            )
                        }
                        invoicesToWrite += InvoiceWithPages(mappedInv, mappedPages)
                    }
                }

                // Allow tests to simulate an interruption before transaction commit
                beforeCommitHook?.invoke()

                for (swh in storesToWrite) {
                    db.storeDao().upsertStore(swh.store)
                    db.storeDao().deleteHoursForStore(swh.store.id)
                    db.storeDao().upsertHours(swh.hours)
                    storesWritten++
                }
                for (jwi in jobsToWrite) {
                    db.jobDao().upsertJob(jwi.job)
                    db.jobDao().deleteItemsForJob(jwi.job.id)
                    db.jobDao().upsertItems(jwi.sortedItems)
                    jobsWritten++
                }
                for (iwp in invoicesToWrite) {
                    db.invoiceDao().upsertInvoice(iwp.invoice)
                    db.invoiceDao().deletePagesForInvoice(iwp.invoice.id)
                    db.invoiceDao().upsertPages(iwp.sortedPages)
                    invoicesWritten++
                }
                if (restorePreferences && decoded.preferences.isNotEmpty()) {
                    db.preferenceDao().upsertAll(decoded.preferences)
                    prefsWritten = decoded.preferences.size
                }
            }

            ArchiveRestoreResult(
                companyRestored = companyRestored,
                storesWritten = storesWritten,
                jobsWritten = jobsWritten,
                invoicesWritten = invoicesWritten,
                attachmentsRestored = resolvedPaths.size,
                preferencesRestored = prefsWritten,
                restoredCompanyId = targetCompanyId
            )
        } catch (e: Exception) {
            // Rollback newly created destination files and restore any overwritten originals
            val restoredOriginals = backedUpOriginals.map { it.first }.toSet()
            movedFiles.filterNot { it in restoredOriginals }.forEach { runCatching { it.delete() } }
            backedUpOriginals.forEach { (orig, bak) ->
                runCatching { bak.copyTo(orig, overwrite = true) }
            }
            throw e
        } finally {
            runCatching { stagingDir.deleteRecursively() }
        }
    }

    private fun decodeAndVerifyArchive(archiveFile: File, passphrase: String): DecodedArchive {
        require(archiveFile.exists() && archiveFile.isFile && archiveFile.canRead()) {
            "Backup archive file does not exist or cannot be read."
        }
        require(archiveFile.length() in 36L..MAX_ARCHIVE_BYTES) {
            "Backup archive size is invalid or exceeds 512 MiB limit."
        }
        val encrypted = archiveFile.readBytes()
        val zipBytes = decryptEnvelope(encrypted, passphrase)

        val entries = linkedMapOf<String, ByteArray>()
        var totalUncompressed = 0L
        var entryCount = 0

        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                entryCount++
                require(entryCount <= MAX_ZIP_ENTRIES) { "Backup archive exceeds maximum entry count." }
                require(!entry.isDirectory) { "Unexpected directory entry in backup archive: ${entry.name}" }
                val name = entry.name
                require(
                    !name.contains("..") &&
                        !name.contains("\\") &&
                        !name.startsWith("/") &&
                        SAFE_ENTRY_PATH_REGEX.matches(name)
                ) {
                    "Unsafe archive entry path rejected (ZipSlip protection): $name"
                }
                require(!entries.containsKey(name)) { "Duplicate entry in backup archive: $name" }
                val baos = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val read = zis.read(buf)
                    if (read <= 0) break
                    totalUncompressed += read
                    require(totalUncompressed <= MAX_UNCOMPRESSED_BYTES) {
                        "Backup archive exceeds uncompressed size limit."
                    }
                    baos.write(buf, 0, read)
                }
                entries[name] = baos.toByteArray()
                zis.closeEntry()
            }
        }

        val manifestBytes = entries["manifest.json"]
            ?: throw IllegalArgumentException("Corrupted archive: missing manifest.json.")
        val dataBytes = entries["data.json"]
            ?: throw IllegalArgumentException("Corrupted archive: missing data.json.")

        val manifest = JSONObject(String(manifestBytes, Charsets.UTF_8))
        require(manifest.optString("format") == ARCHIVE_FORMAT) { "Unsupported backup archive format." }
        require(manifest.optInt("version", -1) == ARCHIVE_VERSION) { "Unsupported backup archive version." }
        val dbSchemaVersion = manifest.optInt("dbSchemaVersion", -1)
        require(dbSchemaVersion in 1..CURRENT_DB_SCHEMA_VERSION) {
            "Backup requires a newer DeliFar database version ($dbSchemaVersion)."
        }
        val exportedAt = manifest.optString("exported").takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Manifest missing exported timestamp.")
        runCatching { Instant.parse(exportedAt) }.getOrElse {
            throw IllegalArgumentException("Invalid exported timestamp in manifest.")
        }

        // Verify data.json SHA-256
        val expectedDataSha = manifest.optString("dataSha256")
        val actualDataSha = InvoiceSyncValidator.sha256Bytes(dataBytes)
        require(expectedDataSha.isNotBlank() && expectedDataSha.equals(actualDataSha, ignoreCase = true)) {
            "Archive integrity check failed: data.json checksum mismatch."
        }

        // Verify every attachment listed in manifest exists and matches its SHA-256
        val manifestFilesObj = manifest.optJSONObject("files") ?: JSONObject()
        val verifiedAttachments = linkedMapOf<String, ByteArray>()
        val fileKeysIter = manifestFilesObj.keys()
        while (fileKeysIter.hasNext()) {
            val relPath = fileKeysIter.next()
            require(SAFE_ENTRY_PATH_REGEX.matches(relPath) && relPath.startsWith("files/")) {
                "Invalid attachment path in manifest: $relPath"
            }
            val expectedSha = manifestFilesObj.optString(relPath)
            val fileBytes = entries[relPath]
                ?: throw IllegalArgumentException("Archive is missing expected attachment: $relPath")
            val actualSha = InvoiceSyncValidator.sha256Bytes(fileBytes)
            require(expectedSha.equals(actualSha, ignoreCase = true)) {
                "Attachment integrity check failed for $relPath (SHA-256 mismatch)."
            }
            verifiedAttachments[relPath] = fileBytes
        }

        val backupCompanyId = manifest.optString("companyId", "").trim()
        val backupCompanyName = manifest.optString("companyName", "Local Workspace").trim()

        val dataRoot = JSONObject(String(dataBytes, Charsets.UTF_8))
        val companyObj = if (dataRoot.isNull("company")) null else dataRoot.optJSONObject("company")
        val parsedCompany = companyObj?.let { c ->
            val cid = c.getString("id").trim()
            require(cid.isNotBlank()) { "Company id cannot be blank." }
            CompanyEntity(
                id = cid,
                name = c.getString("name").trim().ifBlank { "DeliFar Company" },
                ownerUid = "",
                timezone = BusinessRules.resolveZone(c.optString("timezone", "America/Los_Angeles")).id,
                defaultArea = c.optString("defaultArea", SearchArea.LA).takeIf { it in SearchArea.ALL } ?: SearchArea.LA,
                storageUsedBytes = c.optLong("storageUsedBytes", 0L).coerceAtLeast(0L),
                storageQuotaBytes = c.optLong("storageQuotaBytes", 5L * 1024L * 1024L * 1024L),
                created = c.optLong("created", System.currentTimeMillis()),
                modified = c.optLong("modified", System.currentTimeMillis())
            )
        }

        // Parse stores + hours
        val storesArr = dataRoot.getJSONArray("stores")
        val parsedStores = mutableListOf<StoreWithHours>()
        val storeIds = mutableSetOf<String>()
        val providerIds = mutableSetOf<String>()
        for (i in 0 until storesArr.length()) {
            val w = storesArr.getJSONObject(i)
            val sObj = w.getJSONObject("store")
            val id = sObj.getString("id").trim()
            require(id.isNotBlank() && storeIds.add(id)) { "Invalid or duplicate store ID: $id" }
            val name = sObj.getString("name").trim()
            val address = sObj.getString("address").trim()
            require(name.isNotBlank() && address.isNotBlank()) { "Store $id missing name or address." }
            val lat = sObj.getDouble("lat")
            val lon = sObj.getDouble("lon")
            require(lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0) {
                "Invalid coordinates for store $id"
            }
            val providerId = if (sObj.isNull("providerId")) null else sObj.optString("providerId").ifBlank { null }
            if (providerId != null) {
                require(providerIds.add(providerId)) { "Duplicate providerId in archive: $providerId" }
            }
            val created = sObj.getLong("created")
            val modified = sObj.getLong("modified")
            require(modified >= created) { "Store $id modified < created" }

            val hoursArr = w.getJSONArray("hours")
            val hoursList = mutableListOf<StoreHoursEntity>()
            val seenDays = mutableSetOf<Int>()
            for (hIdx in 0 until hoursArr.length()) {
                val h = hoursArr.getJSONObject(hIdx)
                val day = h.getInt("day")
                require(day in 1..7 && seenDays.add(day)) { "Invalid or duplicate weekday $day for store $id" }
                val mode = h.getString("mode")
                require(mode in HoursMode.ALL) { "Invalid hours mode '$mode' for store $id" }
                val windows = h.optString("windows", "")
                val canonical = if (mode == HoursMode.OPEN) {
                    val parsed = BusinessRules.parseAndValidateWindows(windows)
                        ?: throw IllegalArgumentException("Invalid windows for store $id day $day")
                    require(parsed.isNotEmpty()) { "Open day $day requires windows for store $id" }
                    BusinessRules.formatWindowsCanonical(parsed)
                } else ""
                hoursList += StoreHoursEntity(id, day, mode, canonical)
            }

            parsedStores += StoreWithHours(
                StoreEntity(
                    id = id,
                    name = name,
                    address = address,
                    lat = lat,
                    lon = lon,
                    providerId = providerId,
                    phone = sObj.optString("phone", ""),
                    created = created,
                    modified = modified,
                    archived = sObj.optBoolean("archived", false),
                    companyId = backupCompanyId,
                    version = sObj.optInt("version", 1).coerceAtLeast(1),
                    locationConfirmed = sObj.optBoolean("locationConfirmed", true),
                    geocodeSource = sObj.optString("geocodeSource", "legacy")
                ),
                hoursList.sortedBy { it.day }
            )
        }

        // Parse jobs + items
        val jobsArr = dataRoot.getJSONArray("jobs")
        val parsedJobs = mutableListOf<JobWithItems>()
        val jobIds = mutableSetOf<String>()
        val itemIds = mutableSetOf<String>()
        for (i in 0 until jobsArr.length()) {
            val w = jobsArr.getJSONObject(i)
            val jObj = w.getJSONObject("job")
            val id = jObj.getString("id").trim()
            require(id.isNotBlank() && jobIds.add(id)) { "Invalid or duplicate job ID: $id" }
            val storeId = jObj.getString("storeId").trim()
            require(storeId in storeIds) { "Job $id references unknown store $storeId" }
            val type = jObj.getString("type")
            require(type in JobType.ALL) { "Invalid job type: $type" }
            val date = jObj.getString("date")
            require(BusinessRules.parseIsoDate(date) != null) { "Invalid job date: $date" }
            val time = if (jObj.isNull("time")) null else jObj.optString("time").ifBlank { null }
            if (time != null) require(BusinessRules.parseIsoTime(time) != null) { "Invalid job time: $time" }
            val status = jObj.getString("status")
            require(status in JobStatus.ALL) { "Invalid job status: $status" }
            val issue = if (jObj.isNull("issue")) null else jObj.optString("issue").ifBlank { null }
            if (issue != null) require(issue in JobIssue.ALL) { "Invalid job issue: $issue" }
            val created = jObj.getLong("created")
            val modified = jObj.getLong("modified")
            require(modified >= created) { "Job $id modified < created" }
            val completed = if (jObj.isNull("completed")) null else jObj.getLong("completed")

            val itemsArr = w.getJSONArray("items")
            require(itemsArr.length() in 1..50) { "Job $id must have 1..50 items." }
            val itemsList = mutableListOf<JobItemEntity>()
            for (k in 0 until itemsArr.length()) {
                val iObj = itemsArr.getJSONObject(k)
                val itemId = iObj.getString("id").trim()
                require(itemId.isNotBlank() && itemIds.add(itemId)) { "Invalid or duplicate item ID: $itemId" }
                require(iObj.getString("jobId") == id) { "Item $itemId jobId mismatch." }
                val plannedNorm = BusinessRules.normalizeQuantity(iObj.getString("planned"), allowZero = false)
                    ?: throw IllegalArgumentException("Invalid planned quantity on item $itemId")
                val actualRaw = if (iObj.isNull("actual")) null else iObj.optString("actual").ifBlank { null }
                val actualNorm = actualRaw?.let {
                    BusinessRules.normalizeQuantity(it, allowZero = true)
                        ?: throw IllegalArgumentException("Invalid actual quantity on item $itemId")
                }
                itemsList += JobItemEntity(
                    id = itemId,
                    jobId = id,
                    position = iObj.getInt("position"),
                    name = iObj.getString("name").trim(),
                    planned = plannedNorm,
                    unit = iObj.getString("unit").trim(),
                    actual = actualNorm
                )
            }

            parsedJobs += JobWithItems(
                JobEntity(
                    id = id,
                    storeId = storeId,
                    type = type,
                    date = date,
                    time = time,
                    status = status,
                    issue = issue,
                    issueNote = jObj.optString("issueNote", ""),
                    snapshotName = jObj.getString("snapshotName"),
                    snapshotAddress = jObj.getString("snapshotAddress"),
                    snapshotLat = jObj.getDouble("snapshotLat"),
                    snapshotLon = jObj.getDouble("snapshotLon"),
                    created = created,
                    modified = modified,
                    completed = completed,
                    reminderVersion = jObj.optString("reminderVersion", "v1").ifBlank { "v1" },
                    reminderEnabled = jObj.optBoolean("reminderEnabled", true),
                    companyId = backupCompanyId,
                    assignedDriverUid = if (jObj.isNull("assignedDriverUid")) null else jObj.optString("assignedDriverUid").ifBlank { null },
                    assignedDriverName = jObj.optString("assignedDriverName", ""),
                    notes = jObj.optString("notes", ""),
                    version = jObj.optInt("version", 1).coerceAtLeast(1)
                ),
                itemsList.sortedBy { it.position }
            )
        }

        // Parse invoices + pages & verify referenced attachments are present in archive
        val invoicesArr = dataRoot.getJSONArray("invoices")
        val parsedInvoices = mutableListOf<InvoiceWithPages>()
        val invoiceIds = mutableSetOf<String>()
        val pageIds = mutableSetOf<String>()
        for (i in 0 until invoicesArr.length()) {
            val w = invoicesArr.getJSONObject(i)
            val invObj = w.getJSONObject("invoice")
            val id = invObj.getString("id").trim()
            require(id.isNotBlank() && invoiceIds.add(id)) { "Invalid or duplicate invoice ID: $id" }
            val storeId = invObj.getString("storeId").trim()
            require(storeId in storeIds) { "Invoice $id references unknown store $storeId" }
            val jobId = if (invObj.isNull("jobId")) null else invObj.optString("jobId").ifBlank { null }
            if (jobId != null) {
                require(jobId in jobIds) { "Invoice $id references unknown job $jobId" }
            }
            val status = invObj.getString("status")
            require(status in InvoiceStatus.ALL) { "Invalid invoice status: $status" }
            val signed = invObj.optBoolean("signed", false)
            val sigArchivePath = if (invObj.isNull("signatureArchivePath")) null else invObj.optString("signatureArchivePath").ifBlank { null }
            val pdfArchivePath = if (invObj.isNull("signedPdfArchivePath")) null else invObj.optString("signedPdfArchivePath").ifBlank { null }
            if (sigArchivePath != null) {
                require(sigArchivePath in verifiedAttachments) {
                    "Missing signature attachment in archive for invoice $id: $sigArchivePath"
                }
            }
            if (pdfArchivePath != null) {
                require(pdfArchivePath in verifiedAttachments) {
                    "Missing signed PDF attachment in archive for invoice $id: $pdfArchivePath"
                }
            }
            if (signed && (sigArchivePath == null || pdfArchivePath == null)) {
                throw IllegalArgumentException(
                    "Signed invoice #${invObj.optString("invoiceNumber")} is missing required signature or signed PDF attachment."
                )
            }

            val pagesArr = w.getJSONArray("pages")
            require(pagesArr.length() >= 1) { "Invoice $id must contain at least one page." }
            val pagesList = mutableListOf<InvoicePageEntity>()
            for (pIdx in 0 until pagesArr.length()) {
                val pObj = pagesArr.getJSONObject(pIdx)
                val pid = pObj.getString("id").trim()
                require(pid.isNotBlank() && pageIds.add(pid)) { "Invalid or duplicate invoice page ID: $pid" }
                require(pObj.getString("invoiceId") == id) { "Page $pid invoiceId mismatch." }
                val origArchivePath = if (pObj.isNull("originalArchivePath")) null else pObj.optString("originalArchivePath").ifBlank { null }
                val editArchivePath = if (pObj.isNull("editedArchivePath")) null else pObj.optString("editedArchivePath").ifBlank { null }
                require(origArchivePath != null && origArchivePath in verifiedAttachments) {
                    "Missing original scan attachment in archive for invoice $id page ${pIdx + 1}."
                }
                require(editArchivePath != null && editArchivePath in verifiedAttachments) {
                    "Missing edited scan attachment in archive for invoice $id page ${pIdx + 1}."
                }
                pagesList += InvoicePageEntity(
                    id = pid,
                    invoiceId = id,
                    pageIndex = pObj.getInt("pageIndex"),
                    originalLocalPath = origArchivePath,
                    editedLocalPath = editArchivePath,
                    originalStoragePath = if (pObj.isNull("originalStoragePath")) null else pObj.optString("originalStoragePath").ifBlank { null },
                    editedStoragePath = if (pObj.isNull("editedStoragePath")) null else pObj.optString("editedStoragePath").ifBlank { null },
                    originalSha256 = pObj.optString("originalSha256", ""),
                    editedSha256 = pObj.optString("editedSha256", ""),
                    rotationDegrees = pObj.optInt("rotationDegrees", 0),
                    contrastBoost = pObj.optBoolean("contrastBoost", false),
                    cropInsetPercent = pObj.optInt("cropInsetPercent", 0),
                    byteSize = pObj.optLong("byteSize", 0L),
                    mimeType = pObj.optString("mimeType", "image/jpeg")
                )
            }

            parsedInvoices += InvoiceWithPages(
                InvoiceEntity(
                    id = id,
                    companyId = backupCompanyId,
                    jobId = jobId,
                    storeId = storeId,
                    storeName = invObj.getString("storeName"),
                    invoiceNumber = invObj.getString("invoiceNumber"),
                    notes = invObj.optString("notes", ""),
                    status = status,
                    version = invObj.optInt("version", 1).coerceAtLeast(1),
                    supersedesInvoiceId = if (invObj.isNull("supersedesInvoiceId")) null else invObj.optString("supersedesInvoiceId").ifBlank { null },
                    voidedReason = if (invObj.isNull("voidedReason")) null else invObj.optString("voidedReason").ifBlank { null },
                    createdByUid = "",
                    createdByName = invObj.optString("createdByName", ""),
                    createdAt = invObj.getLong("createdAt"),
                    modifiedAt = invObj.getLong("modifiedAt"),
                    signed = signed,
                    recipientName = invObj.optString("recipientName", ""),
                    recipientTitle = invObj.optString("recipientTitle", ""),
                    acknowledgmentConfirmed = invObj.optBoolean("acknowledgmentConfirmed", false),
                    signatureLocalPath = sigArchivePath,
                    signatureStoragePath = if (invObj.isNull("signatureStoragePath")) null else invObj.optString("signatureStoragePath").ifBlank { null },
                    signaturePageIndex = invObj.optInt("signaturePageIndex", 0),
                    signaturePosX = invObj.optDouble("signaturePosX", 0.5).toFloat(),
                    signaturePosY = invObj.optDouble("signaturePosY", 0.82).toFloat(),
                    signedLocalAt = if (invObj.isNull("signedLocalAt")) null else invObj.getLong("signedLocalAt"),
                    signedServerAt = if (invObj.isNull("signedServerAt")) null else invObj.getLong("signedServerAt"),
                    signedPdfLocalPath = pdfArchivePath,
                    signedPdfStoragePath = if (invObj.isNull("signedPdfStoragePath")) null else invObj.optString("signedPdfStoragePath").ifBlank { null },
                    signedPdfSha256 = if (invObj.isNull("signedPdfSha256")) null else invObj.optString("signedPdfSha256").ifBlank { null },
                    totalBytes = invObj.optLong("totalBytes", 0L),
                    syncState = SyncState.PENDING
                ),
                pagesList.sortedBy { it.pageIndex }
            )
        }

        // Parse safe preferences only (never auth credentials or signing secrets)
        val prefsArr = dataRoot.optJSONArray("preferences") ?: JSONArray()
        val parsedPrefs = mutableListOf<PreferenceEntity>()
        val seenKeys = mutableSetOf<String>()
        for (i in 0 until prefsArr.length()) {
            val pObj = prefsArr.getJSONObject(i)
            val key = pObj.getString("key").trim()
            val value = pObj.getString("value")
            if (key in SAFE_PREF_KEYS && seenKeys.add(key)) {
                parsedPrefs += PreferenceEntity(key, value)
            }
        }

        return DecodedArchive(
            exportedAt = exportedAt,
            dbSchemaVersion = dbSchemaVersion,
            company = parsedCompany,
            backupCompanyId = backupCompanyId,
            backupCompanyName = backupCompanyName,
            stores = parsedStores,
            jobs = parsedJobs,
            invoices = parsedInvoices,
            preferences = parsedPrefs,
            attachments = verifiedAttachments
        )
    }
}
