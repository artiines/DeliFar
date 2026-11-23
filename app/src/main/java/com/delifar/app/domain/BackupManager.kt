package com.delifar.app.domain

import androidx.room.withTransaction
import com.delifar.app.data.DeliFarDatabase
import com.delifar.app.data.HoursMode
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
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

data class ParsedBackup(
    val exported: String,
    val stores: List<StoreWithHours>,
    val jobs: List<JobWithItems>,
    val preferences: List<PreferenceEntity>
)

data class ImportPreview(
    val backup: ParsedBackup,
    val newStores: Int,
    val identicalStores: Int,
    val conflictingStores: Int,
    val newJobs: Int,
    val identicalJobs: Int,
    val conflictingJobs: Int,
    val preferenceCount: Int
)

class BackupManager(private val db: DeliFarDatabase) {

    companion object {
        const val MAX_BYTES = 50L * 1024L * 1024L
        private val TOP_KEYS = setOf("format", "version", "exported", "stores", "jobs", "preferences")
        private val STORE_WRAPPER_KEYS = setOf("store", "hours")
        private val STORE_KEYS = setOf(
            "id", "name", "address", "lat", "lon", "providerId", "phone", "created", "modified", "archived"
        )
        private val HOUR_KEYS = setOf("storeId", "day", "mode", "windows")
        private val JOB_WRAPPER_KEYS = setOf("job", "items")
        private val JOB_KEYS = setOf(
            "id", "storeId", "type", "date", "time", "status", "issue", "issueNote",
            "snapshotName", "snapshotAddress", "snapshotLat", "snapshotLon",
            "created", "modified", "completed", "reminderVersion", "reminderEnabled"
        )
        private val ITEM_KEYS = setOf("id", "jobId", "position", "name", "planned", "unit", "actual")
        private val PREF_KEYS = setOf("key", "value")
    }

    suspend fun exportJson(): String {
        val stores = db.storeDao().getAllStoresWithHours()
        val jobs = db.jobDao().getAllJobsWithItems()
        val prefsMap = db.preferenceDao().getAll().associate { it.key to it.value }

        val root = JSONObject()
        root.put("format", "DeliFar")
        root.put("version", 1)
        root.put("exported", Instant.now().toString())

        val storesArr = JSONArray()
        for (swh in stores) {
            val s = swh.store
            val storeObj = JSONObject().apply {
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
            }
            val hoursArr = JSONArray()
            swh.hours.sortedBy { it.day }.forEach { h ->
                hoursArr.put(
                    JSONObject().apply {
                        put("storeId", h.storeId)
                        put("day", h.day)
                        put("mode", h.mode)
                        put("windows", h.windows)
                    }
                )
            }
            storesArr.put(JSONObject().apply {
                put("store", storeObj)
                put("hours", hoursArr)
            })
        }
        root.put("stores", storesArr)

        val jobsArr = JSONArray()
        for (jwi in jobs) {
            val j = jwi.job
            val jobObj = JSONObject().apply {
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
            }
            val itemsArr = JSONArray()
            jwi.sortedItems.forEach { item ->
                itemsArr.put(
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
            jobsArr.put(JSONObject().apply {
                put("job", jobObj)
                put("items", itemsArr)
            })
        }
        root.put("jobs", jobsArr)

        val prefsArr = JSONArray()
        val exportDefaults = linkedMapOf(
            PrefKeys.LANGUAGE to (prefsMap[PrefKeys.LANGUAGE] ?: "system"),
            PrefKeys.AREA to (prefsMap[PrefKeys.AREA] ?: SearchArea.LA),
            PrefKeys.DAILY to (prefsMap[PrefKeys.DAILY] ?: "true"),
            PrefKeys.SUMMARY_MINUTE to (prefsMap[PrefKeys.SUMMARY_MINUTE] ?: "420"),
            PrefKeys.JOB_REMINDERS to (prefsMap[PrefKeys.JOB_REMINDERS] ?: "true"),
            PrefKeys.ADVANCE to (prefsMap[PrefKeys.ADVANCE] ?: "60")
        )
        exportDefaults.forEach { (k, v) ->
            prefsArr.put(JSONObject().apply {
                put("key", k)
                put("value", v)
            })
        }
        root.put("preferences", prefsArr)
        return root.toString(2)
    }

    fun parseAndValidate(rawJson: String): Result<ParsedBackup> {
        return runCatching {
            val bytes = rawJson.toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_BYTES) { "Backup exceeds 50 MiB limit." }
            val root = JSONObject(rawJson)
            requireExactKeys(root, TOP_KEYS, "root")
            require(root.get("format") == "DeliFar") { "Unsupported backup format." }
            val version = root.get("version")
            require(version is Int && version == 1) { "Unsupported backup version." }
            val exported = requireNonEmptyString(root, "exported")
            runCatching { Instant.parse(exported) }.getOrElse {
                throw IllegalArgumentException("Invalid exported timestamp.")
            }

            val storesArr = root.getJSONArray("stores")
            val parsedStores = mutableListOf<StoreWithHours>()
            val storeIds = mutableSetOf<String>()
            val providerIds = mutableSetOf<String>()

            for (i in 0 until storesArr.length()) {
                val wrapper = storesArr.getJSONObject(i)
                requireExactKeys(wrapper, STORE_WRAPPER_KEYS, "stores[$i]")
                val sObj = wrapper.getJSONObject("store")
                requireExactKeys(sObj, STORE_KEYS, "stores[$i].store")

                val id = requireNonEmptyString(sObj, "id")
                require(storeIds.add(id)) { "Duplicate store ID: $id" }
                val name = requireNonEmptyString(sObj, "name")
                val address = requireNonEmptyString(sObj, "address")
                val lat = requireFiniteDouble(sObj, "lat")
                val lon = requireFiniteDouble(sObj, "lon")
                require(lat in -90.0..90.0 && lon in -180.0..180.0) { "Coordinates out of bounds for store $id" }
                val providerId = requireNullableString(sObj, "providerId")
                if (providerId != null) {
                    require(providerId.isNotBlank()) { "Blank providerId for store $id" }
                    require(providerIds.add(providerId)) { "Duplicate providerId: $providerId" }
                }
                val phone = requireString(sObj, "phone")
                val created = requireLong(sObj, "created")
                val modified = requireLong(sObj, "modified")
                require(modified >= created) { "Store $id modified < created" }
                val archived = requireBoolean(sObj, "archived")

                val hoursArr = wrapper.getJSONArray("hours")
                val hoursList = mutableListOf<StoreHoursEntity>()
                val seenDays = mutableSetOf<Int>()
                for (hIdx in 0 until hoursArr.length()) {
                    val hObj = hoursArr.getJSONObject(hIdx)
                    requireExactKeys(hObj, HOUR_KEYS, "stores[$i].hours[$hIdx]")
                    val hStoreId = requireNonEmptyString(hObj, "storeId")
                    require(hStoreId == id) { "Hours storeId mismatch for store $id" }
                    val day = requireInt(hObj, "day")
                    require(day in 1..7 && seenDays.add(day)) { "Invalid or duplicate weekday $day" }
                    val mode = requireNonEmptyString(hObj, "mode")
                    require(mode in HoursMode.ALL) { "Invalid hours mode: $mode" }
                    val windows = requireString(hObj, "windows")
                    val canonical = if (mode == HoursMode.OPEN) {
                        val parsed = BusinessRules.parseAndValidateWindows(windows)
                            ?: throw IllegalArgumentException("Invalid windows for store $id day $day")
                        require(parsed.isNotEmpty()) { "Open day requires windows for store $id day $day" }
                        BusinessRules.formatWindowsCanonical(parsed)
                    } else {
                        require(windows.isEmpty()) { "Closed/unknown day must have empty windows" }
                        ""
                    }
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
                        phone = phone,
                        created = created,
                        modified = modified,
                        archived = archived
                    ),
                    hoursList.sortedBy { it.day }
                )
            }

            val jobsArr = root.getJSONArray("jobs")
            val parsedJobs = mutableListOf<JobWithItems>()
            val jobIds = mutableSetOf<String>()
            val allItemIds = mutableSetOf<String>()

            for (i in 0 until jobsArr.length()) {
                val wrapper = jobsArr.getJSONObject(i)
                requireExactKeys(wrapper, JOB_WRAPPER_KEYS, "jobs[$i]")
                val jObj = wrapper.getJSONObject("job")
                requireExactKeys(jObj, JOB_KEYS, "jobs[$i].job")

                val id = requireNonEmptyString(jObj, "id")
                require(jobIds.add(id)) { "Duplicate job ID: $id" }
                val storeId = requireNonEmptyString(jObj, "storeId")
                require(storeId in storeIds) { "Job $id references unknown store $storeId" }
                val type = requireNonEmptyString(jObj, "type")
                require(type in JobType.ALL) { "Invalid job type: $type" }
                val date = requireNonEmptyString(jObj, "date")
                require(BusinessRules.parseIsoDate(date) != null) { "Invalid date $date" }
                val time = requireNullableString(jObj, "time")
                if (time != null) {
                    require(BusinessRules.parseIsoTime(time) != null) { "Invalid time $time" }
                }
                val status = requireNonEmptyString(jObj, "status")
                require(status in JobStatus.ALL) { "Invalid job status: $status" }
                val issue = requireNullableString(jObj, "issue")
                if (issue != null) require(issue in JobIssue.ALL) { "Invalid job issue: $issue" }
                val issueNote = requireString(jObj, "issueNote")
                if (issue == null) require(issueNote.isEmpty()) { "issueNote must be empty when issue is null" }
                val snapshotName = requireNonEmptyString(jObj, "snapshotName")
                val snapshotAddress = requireNonEmptyString(jObj, "snapshotAddress")
                val snapshotLat = requireFiniteDouble(jObj, "snapshotLat")
                val snapshotLon = requireFiniteDouble(jObj, "snapshotLon")
                require(snapshotLat in -90.0..90.0 && snapshotLon in -180.0..180.0) { "Invalid snapshot coords" }
                val created = requireLong(jObj, "created")
                val modified = requireLong(jObj, "modified")
                require(modified >= created) { "Job $id modified < created" }
                val completed = requireNullableLong(jObj, "completed")
                if (status == JobStatus.COMPLETED) {
                    require(completed != null) { "Completed job $id missing completed timestamp" }
                } else {
                    require(completed == null) { "Non-completed job $id must have null completed timestamp" }
                }
                val reminderVersion = requireNonEmptyString(jObj, "reminderVersion")
                val reminderEnabled = requireBoolean(jObj, "reminderEnabled")

                val itemsArr = wrapper.getJSONArray("items")
                require(itemsArr.length() in 1..50) { "Job $id must contain 1..50 product lines" }
                val itemsList = mutableListOf<JobItemEntity>()
                for (k in 0 until itemsArr.length()) {
                    val iObj = itemsArr.getJSONObject(k)
                    requireExactKeys(iObj, ITEM_KEYS, "jobs[$i].items[$k]")
                    val itemId = requireNonEmptyString(iObj, "id")
                    require(allItemIds.add(itemId)) { "Duplicate item ID: $itemId" }
                    val itemJobId = requireNonEmptyString(iObj, "jobId")
                    require(itemJobId == id) { "Item $itemId jobId mismatch" }
                    val pos = requireInt(iObj, "position")
                    val itemName = requireNonEmptyString(iObj, "name")
                    val plannedRaw = requireNonEmptyString(iObj, "planned")
                    val plannedNorm = BusinessRules.normalizeQuantity(plannedRaw, allowZero = false)
                        ?: throw IllegalArgumentException("Invalid planned quantity '$plannedRaw'")
                    val unit = requireNonEmptyString(iObj, "unit")
                    val actualRaw = requireNullableString(iObj, "actual")
                    val actualNorm = if (issue == JobIssue.PARTIAL) {
                        require(actualRaw != null) { "Partial job $id requires actual qty on all lines" }
                        BusinessRules.normalizeQuantity(actualRaw, allowZero = true)
                            ?: throw IllegalArgumentException("Invalid actual quantity '$actualRaw'")
                    } else {
                        require(actualRaw == null) { "Non-partial job $id must have null actual quantities" }
                        null
                    }
                    itemsList += JobItemEntity(itemId, id, pos, itemName, plannedNorm, unit, actualNorm)
                }
                val sortedItems = itemsList.sortedBy { it.position }
                sortedItems.forEachIndexed { idx, item ->
                    require(item.position == idx) { "Job $id positions must be contiguous 0..${sortedItems.size - 1}" }
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
                        issueNote = issueNote,
                        snapshotName = snapshotName,
                        snapshotAddress = snapshotAddress,
                        snapshotLat = snapshotLat,
                        snapshotLon = snapshotLon,
                        created = created,
                        modified = modified,
                        completed = completed,
                        reminderVersion = reminderVersion,
                        reminderEnabled = reminderEnabled
                    ),
                    sortedItems
                )
            }

            val prefsArr = root.getJSONArray("preferences")
            val parsedPrefs = mutableListOf<PreferenceEntity>()
            val seenPrefKeys = mutableSetOf<String>()
            for (i in 0 until prefsArr.length()) {
                val pObj = prefsArr.getJSONObject(i)
                requireExactKeys(pObj, PREF_KEYS, "preferences[$i]")
                val key = requireNonEmptyString(pObj, "key")
                val value = requireString(pObj, "value")
                require(key in PrefKeys.BACKUP_KEYS && seenPrefKeys.add(key)) { "Invalid or duplicate preference: $key" }
                validatePreference(key, value)
                parsedPrefs += PreferenceEntity(key, value)
            }

            ParsedBackup(exported, parsedStores, parsedJobs, parsedPrefs)
        }
    }

    suspend fun buildPreview(backup: ParsedBackup): ImportPreview {
        val existingStores = db.storeDao().getAllStoresWithHours().associateBy { it.store.id }
        val existingJobs = db.jobDao().getAllJobsWithItems().associateBy { it.job.id }

        var newStores = 0
        var identicalStores = 0
        var conflictingStores = 0
        for (incoming in backup.stores) {
            val cur = existingStores[incoming.store.id]
            when {
                cur == null -> newStores++
                storesEqual(cur, incoming) -> identicalStores++
                else -> conflictingStores++
            }
        }

        var newJobs = 0
        var identicalJobs = 0
        var conflictingJobs = 0
        for (incoming in backup.jobs) {
            val cur = existingJobs[incoming.job.id]
            when {
                cur == null -> newJobs++
                jobsEqual(cur, incoming) -> identicalJobs++
                else -> conflictingJobs++
            }
        }

        return ImportPreview(
            backup = backup,
            newStores = newStores,
            identicalStores = identicalStores,
            conflictingStores = conflictingStores,
            newJobs = newJobs,
            identicalJobs = identicalJobs,
            conflictingJobs = conflictingJobs,
            preferenceCount = backup.preferences.size
        )
    }

    suspend fun applyMerge(
        backup: ParsedBackup,
        replaceConflicts: Boolean,
        restorePreferences: Boolean,
        activeCompanyId: String = ""
    ): Result<Unit> {
        return runCatching {
            db.withTransaction {
                val existingStores = db.storeDao().getAllStoresWithHours().associateBy { it.store.id }
                val existingJobs = db.jobDao().getAllJobsWithItems().associateBy { it.job.id }

                val finalStores = existingStores.mapValues { it.value.store }.toMutableMap()
                val storesToWrite = mutableListOf<StoreWithHours>()

                for (incoming in backup.stores) {
                    val cur = existingStores[incoming.store.id]
                    val shouldWrite = when {
                        cur == null -> true
                        storesEqual(cur, incoming) -> false
                        else -> replaceConflicts
                    }
                    if (shouldWrite) {
                        val mergedStore = incoming.store.copy(
                            companyId = cur?.store?.companyId?.ifBlank { activeCompanyId } ?: activeCompanyId
                        )
                        finalStores[incoming.store.id] = mergedStore
                        storesToWrite += incoming.copy(store = mergedStore)
                    }
                }

                val mergedProviders = mutableSetOf<String>()
                for (store in finalStores.values) {
                    val pid = store.providerId
                    if (pid != null) {
                        require(mergedProviders.add(pid)) {
                            "Merge conflict: duplicate place providerId ($pid) across different store IDs."
                        }
                    }
                }

                val jobsToWrite = mutableListOf<JobWithItems>()
                for (incoming in backup.jobs) {
                    val cur = existingJobs[incoming.job.id]
                    val shouldWrite = when {
                        cur == null -> true
                        jobsEqual(cur, incoming) -> false
                        else -> replaceConflicts
                    }
                    if (shouldWrite) {
                        val targetStore = finalStores[incoming.job.storeId]
                            ?: throw IllegalStateException("Missing store ${incoming.job.storeId} for job ${incoming.job.id}")
                        if (incoming.job.status in JobStatus.ACTIVE && targetStore.archived) {
                            throw IllegalStateException(
                                "Cannot merge unfinished job '${incoming.job.id}' into archived store '${targetStore.name}'."
                            )
                        }
                        val mergedJob = incoming.job.copy(
                            companyId = cur?.job?.companyId?.ifBlank { activeCompanyId } ?: activeCompanyId
                        )
                        jobsToWrite += incoming.copy(job = mergedJob)
                    }
                }

                for (existingJob in existingJobs.values) {
                    val overridden = jobsToWrite.find { it.job.id == existingJob.job.id }?.job ?: existingJob.job
                    val targetStore = finalStores[overridden.storeId]
                    if (overridden.status in JobStatus.ACTIVE && targetStore?.archived == true) {
                        throw IllegalStateException(
                            "Cannot archive store '${targetStore.name}' while an active job references it."
                        )
                    }
                }

                for (swh in storesToWrite) {
                    db.storeDao().upsertStore(swh.store)
                    db.storeDao().deleteHoursForStore(swh.store.id)
                    db.storeDao().upsertHours(swh.hours)
                }
                for (jwi in jobsToWrite) {
                    db.jobDao().upsertJob(jwi.job)
                    db.jobDao().deleteItemsForJob(jwi.job.id)
                    db.jobDao().upsertItems(jwi.sortedItems)
                }
                if (restorePreferences && backup.preferences.isNotEmpty()) {
                    db.preferenceDao().upsertAll(backup.preferences)
                }
            }
        }
    }

    private fun storesEqual(a: StoreWithHours, b: StoreWithHours): Boolean {
        val sa = a.store
        val sb = b.store
        if (sa.id != sb.id || sa.name != sb.name || sa.address != sb.address ||
            sa.lat != sb.lat || sa.lon != sb.lon || sa.providerId != sb.providerId ||
            sa.phone != sb.phone || sa.created != sb.created || sa.modified != sb.modified ||
            sa.archived != sb.archived
        ) return false
        val normA = (1..7).map { d ->
            a.hours.find { it.day == d } ?: StoreHoursEntity(a.store.id, d, HoursMode.UNKNOWN, "")
        }
        val normB = (1..7).map { d ->
            b.hours.find { it.day == d } ?: StoreHoursEntity(b.store.id, d, HoursMode.UNKNOWN, "")
        }
        return normA == normB
    }

    private fun jobsEqual(a: JobWithItems, b: JobWithItems): Boolean {
        val ja = a.job
        val jb = b.job
        val sameCore = ja.id == jb.id && ja.storeId == jb.storeId && ja.type == jb.type &&
            ja.date == jb.date && ja.time == jb.time && ja.status == jb.status &&
            ja.issue == jb.issue && ja.issueNote == jb.issueNote &&
            ja.snapshotName == jb.snapshotName && ja.snapshotAddress == jb.snapshotAddress &&
            ja.snapshotLat == jb.snapshotLat && ja.snapshotLon == jb.snapshotLon &&
            ja.created == jb.created && ja.modified == jb.modified &&
            ja.completed == jb.completed && ja.reminderVersion == jb.reminderVersion &&
            ja.reminderEnabled == jb.reminderEnabled
        return sameCore && a.sortedItems == b.sortedItems
    }

    private fun validatePreference(key: String, value: String) {
        when (key) {
            PrefKeys.LANGUAGE -> require(value in setOf("system", "en", "ru", "hy")) { "Invalid language: $value" }
            PrefKeys.AREA -> require(value in SearchArea.ALL) { "Invalid area: $value" }
            PrefKeys.DAILY, PrefKeys.JOB_REMINDERS -> require(value == "true" || value == "false") { "Invalid bool: $value" }
            PrefKeys.SUMMARY_MINUTE -> require(value.toIntOrNull() in 0..1439) { "Invalid summaryMinute: $value" }
            PrefKeys.ADVANCE -> require(value.toIntOrNull() in 1..10080) { "Invalid advance: $value" }
        }
    }

    private fun requireExactKeys(obj: JSONObject, expected: Set<String>, path: String) {
        val actual = mutableSetOf<String>()
        val iter = obj.keys()
        while (iter.hasNext()) actual += iter.next()
        val missing = expected - actual
        val extra = actual - expected
        require(missing.isEmpty() && extra.isEmpty()) {
            "Field mismatch at $path (missing=$missing, unexpected=$extra)"
        }
    }

    private fun requireString(obj: JSONObject, key: String): String {
        val v = obj.get(key)
        require(v is String) { "Field '$key' must be a string." }
        return v
    }

    private fun requireNonEmptyString(obj: JSONObject, key: String): String {
        val s = requireString(obj, key)
        require(s.isNotBlank()) { "Field '$key' must be non-empty." }
        return s
    }

    private fun requireNullableString(obj: JSONObject, key: String): String? {
        val v = obj.get(key)
        if (v == JSONObject.NULL) return null
        require(v is String) { "Field '$key' must be string or null." }
        return v
    }

    private fun requireBoolean(obj: JSONObject, key: String): Boolean {
        val v = obj.get(key)
        require(v is Boolean) { "Field '$key' must be boolean." }
        return v
    }

    private fun requireInt(obj: JSONObject, key: String): Int {
        val v = obj.get(key)
        require(v is Int) { "Field '$key' must be integer." }
        return v
    }

    private fun requireLong(obj: JSONObject, key: String): Long {
        val v = obj.get(key)
        require(v is Number && v !is Double && v !is Float) { "Field '$key' must be integer timestamp." }
        return v.toLong()
    }

    private fun requireNullableLong(obj: JSONObject, key: String): Long? {
        val v = obj.get(key)
        if (v == JSONObject.NULL) return null
        require(v is Number && v !is Double && v !is Float) { "Field '$key' must be integer timestamp or null." }
        return v.toLong()
    }

    private fun requireFiniteDouble(obj: JSONObject, key: String): Double {
        val v = obj.get(key)
        require(v is Number) { "Field '$key' must be number." }
        val d = v.toDouble()
        require(d.isFinite()) { "Field '$key' must be finite." }
        return d
    }
}
