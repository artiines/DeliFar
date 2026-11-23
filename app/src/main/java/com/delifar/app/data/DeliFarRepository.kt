package com.delifar.app.data

import androidx.room.withTransaction
import com.delifar.app.domain.BusinessRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class DraftItemInput(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val planned: String = "",
    val unit: String = "cs"
)

class DeliFarRepository(
    val db: DeliFarDatabase
) {
    private val storeDao = db.storeDao()
    private val jobDao = db.jobDao()
    private val prefDao = db.preferenceDao()
    private val draftDao = db.draftDao()
    private val receiptDao = db.receiptDao()
    private val searchCacheDao = db.searchCacheDao()

    fun observeStores(): Flow<List<StoreWithHours>> = storeDao.observeAllStoresWithHours()

    fun observeStore(storeId: String): Flow<StoreWithHours?> = storeDao.observeStoreWithHours(storeId)

    fun observeAllJobRows(): Flow<List<JobListRow>> = jobDao.observeAllJobRows()

    fun observeJobWithItems(jobId: String): Flow<JobWithItems?> = jobDao.observeJobWithItems(jobId)

    fun observeLatestDraft(): Flow<DraftEntity?> = draftDao.observeLatestDraft()

    fun observePreferences(): Flow<AppPreferences> =
        prefDao.observeAll().map { list -> AppPreferences.fromMap(list.associate { it.key to it.value }) }

    suspend fun getPreferences(): AppPreferences {
        val list = prefDao.getAll()
        return AppPreferences.fromMap(list.associate { it.key to it.value })
    }

    suspend fun setPreference(key: String, value: String) {
        prefDao.upsert(PreferenceEntity(key, value))
    }

    suspend fun findStoreByProviderId(providerId: String): StoreEntity? =
        storeDao.findByProviderId(providerId)

    suspend fun getStoreWithHours(storeId: String): StoreWithHours? =
        storeDao.getStoreWithHours(storeId)

    suspend fun saveStore(
        existingId: String?,
        name: String,
        address: String,
        lat: Double,
        lon: Double,
        providerId: String?,
        phone: String,
        hours: List<StoreHoursEntity>
    ): Result<StoreEntity> {
        val trimmedName = name.trim()
        val trimmedAddress = address.trim()
        if (trimmedName.isEmpty() || trimmedAddress.isEmpty()) {
            return Result.failure(IllegalArgumentException("Store name and address are required."))
        }
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            return Result.failure(IllegalArgumentException("Valid coordinates are required."))
        }
        val cleanProvider = providerId?.trim()?.ifEmpty { null }
        if (cleanProvider != null) {
            val dup = storeDao.findByProviderId(cleanProvider)
            if (dup != null && dup.id != existingId) {
                return Result.failure(IllegalStateException("DUPLICATE_PROVIDER"))
            }
        }
        val now = System.currentTimeMillis()
        val existing = existingId?.let { storeDao.getStore(it) }
        val storeId = existing?.id ?: UUID.randomUUID().toString()
        val entity = StoreEntity(
            id = storeId,
            name = trimmedName,
            address = trimmedAddress,
            lat = lat,
            lon = lon,
            providerId = cleanProvider,
            phone = phone.trim(),
            created = existing?.created ?: now,
            modified = now.coerceAtLeast(existing?.created ?: now),
            archived = existing?.archived ?: false
        )
        val validatedHours = (1..7).map { day ->
            val found = hours.find { it.day == day }
            val mode = found?.mode?.takeIf { it in HoursMode.ALL } ?: HoursMode.UNKNOWN
            val canonicalWindows = if (mode == HoursMode.OPEN) {
                val parsed = BusinessRules.parseAndValidateWindows(found?.windows ?: "")
                    ?: return Result.failure(IllegalArgumentException("Invalid receiving hours on day $day."))
                if (parsed.isEmpty()) return Result.failure(IllegalArgumentException("Open day $day requires at least one window."))
                BusinessRules.formatWindowsCanonical(parsed)
            } else {
                ""
            }
            StoreHoursEntity(storeId = storeId, day = day, mode = mode, windows = canonicalWindows)
        }

        db.withTransaction {
            storeDao.upsertStore(entity)
            storeDao.deleteHoursForStore(storeId)
            storeDao.upsertHours(validatedHours)
        }
        return Result.success(entity)
    }

    suspend fun archiveStore(storeId: String): Result<Unit> {
        val activeCount = jobDao.countActiveJobsForStore(storeId)
        if (activeCount > 0) {
            return Result.failure(IllegalStateException("ACTIVE_JOBS"))
        }
        storeDao.setArchived(storeId, true, System.currentTimeMillis())
        return Result.success(Unit)
    }

    suspend fun restoreStore(storeId: String) {
        storeDao.setArchived(storeId, false, System.currentTimeMillis())
    }

    suspend fun saveDraft(draft: DraftEntity) {
        draftDao.upsert(draft.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun clearDraft(jobId: String) {
        draftDao.delete(jobId)
    }

    suspend fun clearAllDrafts() {
        draftDao.clearAll()
    }

    /**
     * Atomically commits job, product lines, and draft removal inside a single Room transaction.
     */
    suspend fun saveJobAtomically(
        jobId: String,
        storeId: String,
        type: String,
        dateIso: String,
        timeIso: String?,
        reminderEnabled: Boolean,
        items: List<DraftItemInput>
    ): Result<JobEntity> {
        val store = storeDao.getStore(storeId)
            ?: return Result.failure(IllegalArgumentException("Please select a saved store."))
        if (store.archived) {
            val existing = jobDao.getJobWithItems(jobId)?.job
            if (existing == null || existing.status in JobStatus.ACTIVE) {
                return Result.failure(IllegalArgumentException("Cannot schedule active work for an archived store."))
            }
        }
        if (type !in JobType.ALL) {
            return Result.failure(IllegalArgumentException("Invalid job type."))
        }
        if (BusinessRules.parseIsoDate(dateIso) == null) {
            return Result.failure(IllegalArgumentException("Enter a valid date (YYYY-MM-DD)."))
        }
        val cleanTime = timeIso?.trim()?.ifEmpty { null }
        if (cleanTime != null && BusinessRules.parseIsoTime(cleanTime) == null) {
            return Result.failure(IllegalArgumentException("Enter a valid 24-hour time (HH:mm) or leave blank."))
        }
        if (items.isEmpty() || items.size > 50) {
            return Result.failure(IllegalArgumentException("Add between 1 and 50 product lines."))
        }

        val normalizedItems = mutableListOf<JobItemEntity>()
        val existingWithItems = jobDao.getJobWithItems(jobId)
        val existingById = existingWithItems?.items?.associateBy { it.id }.orEmpty()
        val existingJob = existingWithItems?.job
        val isPartial = existingJob?.issue == JobIssue.PARTIAL

        items.forEachIndexed { idx, row ->
            val name = row.name.trim()
            val unit = row.unit.trim().ifEmpty { "ea" }
            if (name.isEmpty()) {
                return Result.failure(IllegalArgumentException("Line ${idx + 1} needs a product name."))
            }
            val planned = BusinessRules.normalizeQuantity(row.planned, allowZero = false)
                ?: return Result.failure(IllegalArgumentException("Line ${idx + 1} needs a positive planned quantity."))
            val existingActual = if (isPartial) existingById[row.id]?.actual ?: "0" else null
            normalizedItems += JobItemEntity(
                id = row.id.ifBlank { UUID.randomUUID().toString() },
                jobId = jobId,
                position = idx,
                name = name,
                planned = planned,
                unit = unit,
                actual = existingActual
            )
        }

        val now = System.currentTimeMillis()
        val scheduleOrReminderChanged = existingJob == null ||
            existingJob.date != dateIso ||
            existingJob.time != cleanTime ||
            existingJob.reminderEnabled != reminderEnabled
        val newReminderVersion = if (scheduleOrReminderChanged) {
            UUID.randomUUID().toString().take(12)
        } else {
            existingJob!!.reminderVersion
        }

        val entity = JobEntity(
            id = jobId,
            storeId = store.id,
            type = type,
            date = dateIso,
            time = cleanTime,
            status = existingJob?.status ?: JobStatus.PENDING,
            issue = existingJob?.issue,
            issueNote = existingJob?.issueNote ?: "",
            snapshotName = store.name,
            snapshotAddress = store.address,
            snapshotLat = store.lat,
            snapshotLon = store.lon,
            created = existingJob?.created ?: now,
            modified = now.coerceAtLeast(existingJob?.created ?: now),
            completed = existingJob?.completed,
            reminderVersion = newReminderVersion,
            reminderEnabled = reminderEnabled
        )

        db.withTransaction {
            jobDao.upsertJob(entity)
            jobDao.deleteItemsForJob(jobId)
            jobDao.upsertItems(normalizedItems)
            draftDao.delete(jobId)
        }
        return Result.success(entity)
    }

    suspend fun updateJobStatus(jobId: String, newStatus: String): Result<JobEntity> {
        val current = jobDao.getJobWithItems(jobId)
            ?: return Result.failure(IllegalArgumentException("Job not found"))
        val store = storeDao.getStore(current.job.storeId)
        val now = System.currentTimeMillis()
        val reopening = newStatus in JobStatus.ACTIVE && current.job.status !in JobStatus.ACTIVE
        if (reopening && store?.archived == true) {
            return Result.failure(IllegalStateException("Restore the archived store before reopening this job."))
        }
        val updated = current.job.copy(
            status = newStatus,
            snapshotName = store?.name ?: current.job.snapshotName,
            snapshotAddress = store?.address ?: current.job.snapshotAddress,
            snapshotLat = store?.lat ?: current.job.snapshotLat,
            snapshotLon = store?.lon ?: current.job.snapshotLon,
            modified = now.coerceAtLeast(current.job.created),
            completed = if (newStatus == JobStatus.COMPLETED) now else null,
            reminderVersion = if (reopening) UUID.randomUUID().toString().take(12) else current.job.reminderVersion
        )
        jobDao.upsertJob(updated)
        return Result.success(updated)
    }

    suspend fun rescheduleJob(jobId: String, newDate: String, newTime: String?): Result<JobEntity> {
        if (BusinessRules.parseIsoDate(newDate) == null) {
            return Result.failure(IllegalArgumentException("Invalid date."))
        }
        val cleanTime = newTime?.trim()?.ifEmpty { null }
        if (cleanTime != null && BusinessRules.parseIsoTime(cleanTime) == null) {
            return Result.failure(IllegalArgumentException("Invalid time."))
        }
        val current = jobDao.getJobWithItems(jobId)?.job
            ?: return Result.failure(IllegalArgumentException("Job not found"))
        val now = System.currentTimeMillis()
        val updated = current.copy(
            date = newDate,
            time = cleanTime,
            modified = now.coerceAtLeast(current.created),
            reminderVersion = UUID.randomUUID().toString().take(12)
        )
        jobDao.upsertJob(updated)
        return Result.success(updated)
    }

    suspend fun setJobReminderEnabled(jobId: String, enabled: Boolean): Result<JobEntity> {
        val current = jobDao.getJobWithItems(jobId)?.job
            ?: return Result.failure(IllegalArgumentException("Job not found"))
        val now = System.currentTimeMillis()
        val updated = current.copy(
            reminderEnabled = enabled,
            modified = now.coerceAtLeast(current.created),
            reminderVersion = UUID.randomUUID().toString().take(12)
        )
        jobDao.upsertJob(updated)
        return Result.success(updated)
    }

    suspend fun reportIssue(
        jobId: String,
        issueType: String,
        note: String,
        keepOpen: Boolean,
        actualQuantitiesByItemId: Map<String, String>
    ): Result<JobEntity> {
        if (issueType !in JobIssue.ALL) {
            return Result.failure(IllegalArgumentException("Invalid issue type."))
        }
        val current = jobDao.getJobWithItems(jobId)
            ?: return Result.failure(IllegalArgumentException("Job not found"))

        val updatedItems = current.sortedItems.map { item ->
            if (issueType == JobIssue.PARTIAL) {
                val raw = actualQuantitiesByItemId[item.id] ?: "0"
                val norm = BusinessRules.normalizeQuantity(raw, allowZero = true)
                    ?: return Result.failure(IllegalArgumentException("Invalid actual quantity for ${item.name}."))
                item.copy(actual = norm)
            } else {
                item.copy(actual = null)
            }
        }
        val now = System.currentTimeMillis()
        val newStatus = if (keepOpen) {
            if (current.job.status in JobStatus.ACTIVE) current.job.status else JobStatus.PROGRESS
        } else {
            JobStatus.COMPLETED
        }
        val updatedJob = current.job.copy(
            status = newStatus,
            issue = issueType,
            issueNote = note.trim(),
            modified = now.coerceAtLeast(current.job.created),
            completed = if (newStatus == JobStatus.COMPLETED) now else null
        )

        db.withTransaction {
            jobDao.upsertJob(updatedJob)
            jobDao.upsertItems(updatedItems)
        }
        return Result.success(updatedJob)
    }

    suspend fun getCachedSearch(key: String, maxAgeMs: Long): String? {
        val entry = searchCacheDao.get(key) ?: return null
        return if (System.currentTimeMillis() - entry.cachedAt <= maxAgeMs) entry.responseJson else null
    }

    suspend fun putCachedSearch(key: String, json: String) {
        val now = System.currentTimeMillis()
        searchCacheDao.upsert(SearchCacheEntity(key, json, now))
        searchCacheDao.pruneOlderThan(now - 7L * 24 * 60 * 60 * 1000)
    }

    suspend fun hasReceipt(key: String): Boolean = receiptDao.exists(key)

    suspend fun recordReceipt(key: String) {
        val now = System.currentTimeMillis()
        receiptDao.insert(ReceiptEntity(key, now))
        receiptDao.pruneOlderThan(now - 30L * 24 * 60 * 60 * 1000)
    }

    companion object {
        fun encodeDraftItems(items: List<DraftItemInput>): String {
            val arr = JSONArray()
            items.forEach { item ->
                arr.put(
                    JSONObject().apply {
                        put("id", item.id)
                        put("name", item.name)
                        put("planned", item.planned)
                        put("unit", item.unit)
                    }
                )
            }
            return arr.toString()
        }

        fun decodeDraftItems(json: String): List<DraftItemInput> {
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    DraftItemInput(
                        id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
                        name = obj.optString("name", ""),
                        planned = obj.optString("planned", ""),
                        unit = obj.optString("unit", "cs")
                    )
                }
            } catch (_: Exception) {
                listOf(DraftItemInput())
            }
        }
    }
}
