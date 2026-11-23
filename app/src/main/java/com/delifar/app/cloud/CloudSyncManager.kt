package com.delifar.app.cloud

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.delifar.app.data.AuditLogEntity
import com.delifar.app.data.CompanyEntity
import com.delifar.app.data.DeliFarDatabase
import com.delifar.app.data.DeliFarRepository
import com.delifar.app.data.EmployeeEntity
import com.delifar.app.data.EmployeeRole
import com.delifar.app.data.HoursMode
import com.delifar.app.data.InvitationEntity
import com.delifar.app.data.InviteStatus
import com.delifar.app.data.InvoiceEntity
import com.delifar.app.data.InvoicePageEntity
import com.delifar.app.data.InvoiceStatus
import com.delifar.app.data.JobEntity
import com.delifar.app.data.JobItemEntity
import com.delifar.app.data.JobStatus
import com.delifar.app.data.JobType
import com.delifar.app.data.SearchArea
import com.delifar.app.data.StoreEntity
import com.delifar.app.data.StoreHoursEntity
import com.delifar.app.data.SyncState
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import java.util.concurrent.TimeUnit

data class CloudSyncUiState(
    val firebaseConfigured: Boolean = FirebaseServices.isConfigured,
    val isOnline: Boolean = false,
    val isSyncing: Boolean = false,
    val lastSyncedAt: Long? = null,
    val lastError: String? = null
)

/**
 * Coordinates real-time Firestore listeners, conflict-safe merging into Room,
 * private Firebase Cloud Storage uploads, and WorkManager persistent retries.
 */
class CloudSyncManager(
    private val context: Context,
    private val db: DeliFarDatabase,
    private val repository: DeliFarRepository
) {
    companion object {
        private const val TAG = "DeliFarSync"
        private const val UNIQUE_SYNC_WORK = "delifar_cloud_sync_queue"
        const val MAX_INVOICE_FILE_BYTES = 15L * 1024L * 1024L

        fun scheduleBackgroundSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_SYNC_WORK,
                ExistingWorkPolicy.REPLACE,
                req
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listeners = mutableListOf<ListenerRegistration>()
    private var activeCompanyId: String = ""

    private val _syncState = MutableStateFlow(
        CloudSyncUiState(firebaseConfigured = FirebaseServices.isConfigured)
    )
    val syncState: StateFlow<CloudSyncUiState> = _syncState.asStateFlow()

    fun startRealtimeListeners(companyId: String, userUid: String, role: String) {
        stopListeners()
        if (companyId.isBlank() || !FirebaseServices.isConfigured) return
        val fs = FirebaseServices.firestoreOrNull() ?: return
        activeCompanyId = companyId

        scope.launch {
            val online = FirebaseServices.testConnection()
            _syncState.value = _syncState.value.copy(isOnline = online, firebaseConfigured = true)
        }

        val companyRef = fs.collection("companies").document(companyId)

        // 1. Company metadata
        listeners += companyRef.addSnapshotListener { snap, err ->
            if (err != null) {
                FirebaseServices.handleFirestoreError(err, OperationType.GET, "companies/$companyId")
                return@addSnapshotListener
            }
            if (snap != null && snap.exists()) {
                scope.launch {
                    val entity = CompanyEntity(
                        id = snap.id,
                        name = snap.getString("name") ?: "Company",
                        ownerUid = snap.getString("ownerUid") ?: "",
                        timezone = snap.getString("timezone") ?: "America/Los_Angeles",
                        defaultArea = snap.getString("defaultArea") ?: SearchArea.LA,
                        storageUsedBytes = snap.getLong("storageUsedBytes") ?: 0L,
                        storageQuotaBytes = snap.getLong("storageQuotaBytes") ?: (5L * 1024L * 1024L * 1024L),
                        created = snap.getLong("created") ?: System.currentTimeMillis(),
                        modified = snap.getLong("modified") ?: System.currentTimeMillis()
                    )
                    db.companyDao().upsert(entity)
                }
            }
        }

        // 2. Stores
        listeners += companyRef.collection("stores").limit(500).addSnapshotListener { snaps, err ->
            if (err != null) {
                FirebaseServices.handleFirestoreError(err, OperationType.LIST, "companies/$companyId/stores")
                return@addSnapshotListener
            }
            if (snaps == null) return@addSnapshotListener
            scope.launch {
                for (doc in snaps.documents) {
                    val remoteVersion = (doc.getLong("version") ?: 1L).toInt()
                    val remoteMod = doc.getLong("modified") ?: 0L
                    val local = db.storeDao().getStore(doc.id)
                    if (local != null && local.syncState == SyncState.PENDING && local.version > remoteVersion) {
                        continue // Keep newer local edit queued for upload
                    }
                    if (local != null && local.version == remoteVersion && local.modified > remoteMod) {
                        continue
                    }
                    val serverTs = (doc.get("serverUpdatedAt") as? Timestamp)?.toDate()?.time
                    val store = StoreEntity(
                        id = doc.id,
                        name = doc.getString("name") ?: continue,
                        address = doc.getString("address") ?: "",
                        lat = doc.getDouble("lat") ?: 0.0,
                        lon = doc.getDouble("lon") ?: 0.0,
                        providerId = doc.getString("providerId"),
                        phone = doc.getString("phone") ?: "",
                        created = doc.getLong("created") ?: remoteMod,
                        modified = remoteMod,
                        archived = doc.getBoolean("archived") ?: false,
                        companyId = companyId,
                        syncState = SyncState.SYNCED,
                        serverUpdatedAt = serverTs,
                        updatedByUid = doc.getString("updatedByUid") ?: "",
                        version = remoteVersion
                    )
                    db.storeDao().upsertStore(store)
                    @Suppress("UNCHECKED_CAST")
                    val hoursList = doc.get("hours") as? List<Map<String, Any?>>
                    if (hoursList != null) {
                        val parsedHours = hoursList.mapNotNull { m ->
                            val day = (m["day"] as? Number)?.toInt() ?: return@mapNotNull null
                            StoreHoursEntity(
                                storeId = doc.id,
                                day = day,
                                mode = (m["mode"] as? String) ?: HoursMode.UNKNOWN,
                                windows = (m["windows"] as? String) ?: ""
                            )
                        }
                        if (parsedHours.isNotEmpty()) {
                            db.storeDao().deleteHoursForStore(doc.id)
                            db.storeDao().upsertHours(parsedHours)
                        }
                    }
                }
                _syncState.value = _syncState.value.copy(isOnline = true, lastSyncedAt = System.currentTimeMillis())
            }
        }

        // 3. Jobs (Drivers only subscribe to their assigned deliveries/pickups)
        val jobsQuery = if (role == EmployeeRole.DRIVER && userUid.isNotBlank()) {
            companyRef.collection("jobs").whereEqualTo("assignedDriverUid", userUid).limit(500)
        } else {
            companyRef.collection("jobs").limit(500)
        }
        listeners += jobsQuery.addSnapshotListener { snaps, err ->
            if (err != null) {
                FirebaseServices.handleFirestoreError(err, OperationType.LIST, "companies/$companyId/jobs")
                return@addSnapshotListener
            }
            if (snaps == null) return@addSnapshotListener
            scope.launch {
                for (doc in snaps.documents) {
                    val remoteVersion = (doc.getLong("version") ?: 1L).toInt()
                    val local = db.jobDao().getJobWithItems(doc.id)?.job
                    if (local != null && local.syncState == SyncState.PENDING && local.version > remoteVersion) {
                        continue
                    }
                    val storeId = doc.getString("storeId") ?: continue
                    if (db.storeDao().getStore(storeId) == null) continue // Wait for parent store FK
                    val serverTs = (doc.get("serverUpdatedAt") as? Timestamp)?.toDate()?.time
                    val job = JobEntity(
                        id = doc.id,
                        storeId = storeId,
                        type = doc.getString("type") ?: JobType.DELIVERY,
                        date = doc.getString("date") ?: "",
                        time = doc.getString("time"),
                        status = doc.getString("status") ?: JobStatus.PENDING,
                        issue = doc.getString("issue"),
                        issueNote = doc.getString("issueNote") ?: "",
                        snapshotName = doc.getString("snapshotName") ?: "",
                        snapshotAddress = doc.getString("snapshotAddress") ?: "",
                        snapshotLat = doc.getDouble("snapshotLat") ?: 0.0,
                        snapshotLon = doc.getDouble("snapshotLon") ?: 0.0,
                        created = doc.getLong("created") ?: System.currentTimeMillis(),
                        modified = doc.getLong("modified") ?: System.currentTimeMillis(),
                        completed = doc.getLong("completed"),
                        reminderVersion = doc.getString("reminderVersion") ?: "v1",
                        reminderEnabled = doc.getBoolean("reminderEnabled") ?: true,
                        companyId = companyId,
                        assignedDriverUid = doc.getString("assignedDriverUid"),
                        assignedDriverName = doc.getString("assignedDriverName") ?: "",
                        notes = doc.getString("notes") ?: "",
                        syncState = SyncState.SYNCED,
                        serverUpdatedAt = serverTs,
                        updatedByUid = doc.getString("updatedByUid") ?: "",
                        version = remoteVersion
                    )
                    db.jobDao().upsertJob(job)
                    @Suppress("UNCHECKED_CAST")
                    val itemsList = doc.get("items") as? List<Map<String, Any?>>
                    if (itemsList != null) {
                        val parsedItems = itemsList.mapIndexedNotNull { idx, m ->
                            val itemId = (m["id"] as? String) ?: return@mapIndexedNotNull null
                            JobItemEntity(
                                id = itemId,
                                jobId = doc.id,
                                position = (m["position"] as? Number)?.toInt() ?: idx,
                                name = (m["name"] as? String) ?: "",
                                planned = (m["planned"] as? String) ?: "1",
                                unit = (m["unit"] as? String) ?: "ea",
                                actual = m["actual"] as? String
                            )
                        }
                        if (parsedItems.isNotEmpty()) {
                            db.jobDao().deleteItemsForJob(doc.id)
                            db.jobDao().upsertItems(parsedItems)
                        }
                    }
                }
            }
        }

        // 4. Employees
        listeners += companyRef.collection("employees").limit(200).addSnapshotListener { snaps, err ->
            if (err != null) {
                FirebaseServices.handleFirestoreError(err, OperationType.LIST, "companies/$companyId/employees")
                return@addSnapshotListener
            }
            if (snaps == null) return@addSnapshotListener
            scope.launch {
                val list = snaps.documents.map { doc ->
                    EmployeeEntity(
                        uid = doc.id,
                        companyId = companyId,
                        displayName = doc.getString("displayName") ?: "",
                        email = doc.getString("email") ?: "",
                        phone = doc.getString("phone") ?: "",
                        role = doc.getString("role") ?: EmployeeRole.DRIVER,
                        active = doc.getBoolean("active") ?: true,
                        fcmToken = doc.getString("fcmToken") ?: "",
                        invitedByUid = doc.getString("invitedByUid") ?: "",
                        joinedAt = doc.getLong("joinedAt") ?: 0L,
                        lastActiveAt = doc.getLong("lastActiveAt") ?: 0L
                    )
                }
                db.employeeDao().upsertAll(list)
            }
        }

        // 5. Invoices (preserving immutability of already signed local docs)
        listeners += companyRef.collection("invoices").limit(500).addSnapshotListener { snaps, err ->
            if (err != null) {
                FirebaseServices.handleFirestoreError(err, OperationType.LIST, "companies/$companyId/invoices")
                return@addSnapshotListener
            }
            if (snaps == null) return@addSnapshotListener
            scope.launch {
                for (doc in snaps.documents) {
                    val local = db.invoiceDao().getInvoiceWithPages(doc.id)?.invoice
                    val remoteSigned = doc.getBoolean("signed") ?: false
                    // Never allow an unsigned remote doc to overwrite a locally signed invoice
                    if (local != null && local.signed && !remoteSigned) continue
                    val serverTs = (doc.get("serverUpdatedAt") as? Timestamp)?.toDate()?.time
                    val signedServerTs = (doc.get("signedServerAt") as? Timestamp)?.toDate()?.time
                    val entity = InvoiceEntity(
                        id = doc.id,
                        companyId = companyId,
                        jobId = doc.getString("jobId"),
                        storeId = doc.getString("storeId") ?: "",
                        storeName = doc.getString("storeName") ?: "",
                        invoiceNumber = doc.getString("invoiceNumber") ?: "",
                        notes = doc.getString("notes") ?: "",
                        status = doc.getString("status") ?: InvoiceStatus.SYNCHRONIZED,
                        version = (doc.getLong("version") ?: 1L).toInt(),
                        supersedesInvoiceId = doc.getString("supersedesInvoiceId"),
                        voidedReason = doc.getString("voidedReason"),
                        createdByUid = doc.getString("createdByUid") ?: "",
                        createdByName = doc.getString("createdByName") ?: "",
                        createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis(),
                        modifiedAt = doc.getLong("modifiedAt") ?: System.currentTimeMillis(),
                        signed = remoteSigned,
                        recipientName = doc.getString("recipientName") ?: "",
                        recipientTitle = doc.getString("recipientTitle") ?: "",
                        acknowledgmentConfirmed = doc.getBoolean("acknowledgmentConfirmed") ?: false,
                        signatureLocalPath = local?.signatureLocalPath,
                        signatureStoragePath = doc.getString("signatureStoragePath"),
                        signaturePageIndex = (doc.getLong("signaturePageIndex") ?: 0L).toInt(),
                        signaturePosX = (doc.getDouble("signaturePosX") ?: 0.5).toFloat(),
                        signaturePosY = (doc.getDouble("signaturePosY") ?: 0.82).toFloat(),
                        signedLocalAt = doc.getLong("signedLocalAt") ?: local?.signedLocalAt,
                        signedServerAt = signedServerTs ?: local?.signedServerAt,
                        signedPdfLocalPath = local?.signedPdfLocalPath,
                        signedPdfStoragePath = doc.getString("signedPdfStoragePath"),
                        signedPdfSha256 = doc.getString("signedPdfSha256") ?: local?.signedPdfSha256,
                        totalBytes = doc.getLong("totalBytes") ?: local?.totalBytes ?: 0L,
                        syncState = SyncState.SYNCED,
                        serverUpdatedAt = serverTs,
                        uploadError = null
                    )
                    db.invoiceDao().upsertInvoice(entity)
                }
            }
        }

        // 6. Audit logs (Owner & Manager)
        if (role == EmployeeRole.OWNER || role == EmployeeRole.MANAGER) {
            listeners += companyRef.collection("auditLogs").limit(200).addSnapshotListener { snaps, err ->
                if (err != null) return@addSnapshotListener
                if (snaps == null) return@addSnapshotListener
                scope.launch {
                    for (doc in snaps.documents) {
                        val serverTs = (doc.get("serverTimestamp") as? Timestamp)?.toDate()?.time
                        db.auditLogDao().insert(
                            AuditLogEntity(
                                id = doc.id,
                                companyId = companyId,
                                actorUid = doc.getString("actorUid") ?: "",
                                actorName = doc.getString("actorName") ?: "",
                                actorRole = doc.getString("actorRole") ?: "",
                                action = doc.getString("action") ?: "",
                                targetType = doc.getString("targetType") ?: "",
                                targetId = doc.getString("targetId") ?: "",
                                summary = doc.getString("summary") ?: "",
                                createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis(),
                                serverTimestamp = serverTs,
                                syncState = SyncState.SYNCED
                            )
                        )
                    }
                }
            }
        }
    }

    fun stopListeners() {
        listeners.forEach { runCatching { it.remove() } }
        listeners.clear()
        activeCompanyId = ""
    }

    /**
     * Flushes queued offline writes & invoice uploads to Firestore + Firebase Cloud Storage.
     * Uses exponential retry backoff on transient errors.
     */
    suspend fun flushPendingQueue(): Boolean {
        if (!FirebaseServices.isConfigured) return true
        val fs = FirebaseServices.firestoreOrNull() ?: return true
        val storage = FirebaseServices.storageOrNull()
        val now = System.currentTimeMillis()
        val due = db.syncQueueDao().getDueItems(now)
        if (due.isEmpty()) return true

        _syncState.value = _syncState.value.copy(isSyncing = true, lastError = null)
        var allSucceeded = true

        for (item in due) {
            try {
                val companyRef = fs.collection("companies").document(item.companyId)
                when (item.entityType) {
                    "company" -> {
                        val c = db.companyDao().getCompany(item.entityId)
                        if (c != null) {
                            companyRef.set(
                                mapOf(
                                    "id" to c.id,
                                    "name" to c.name,
                                    "ownerUid" to c.ownerUid,
                                    "timezone" to c.timezone,
                                    "defaultArea" to c.defaultArea,
                                    "storageUsedBytes" to c.storageUsedBytes,
                                    "storageQuotaBytes" to c.storageQuotaBytes,
                                    "created" to c.created,
                                    "modified" to c.modified,
                                    "serverUpdatedAt" to FieldValue.serverTimestamp()
                                ),
                                SetOptions.merge()
                            ).await()
                        }
                    }

                    "employee" -> {
                        val e = db.employeeDao().getByUid(item.entityId)
                        if (e != null) {
                            companyRef.collection("employees").document(e.uid).set(
                                mapOf(
                                    "uid" to e.uid,
                                    "companyId" to e.companyId,
                                    "displayName" to e.displayName,
                                    "email" to e.email,
                                    "phone" to e.phone,
                                    "role" to e.role,
                                    "active" to e.active,
                                    "fcmToken" to e.fcmToken,
                                    "invitedByUid" to e.invitedByUid,
                                    "joinedAt" to e.joinedAt,
                                    "lastActiveAt" to e.lastActiveAt,
                                    "serverUpdatedAt" to FieldValue.serverTimestamp()
                                ),
                                SetOptions.merge()
                            ).await()
                        }
                    }

                    "invitation" -> {
                        val inv = db.invitationDao().getById(item.entityId)
                        if (inv != null) {
                            companyRef.collection("invitations").document(inv.id).set(
                                mapOf(
                                    "id" to inv.id,
                                    "companyId" to inv.companyId,
                                    "companyName" to inv.companyName,
                                    "email" to inv.email,
                                    "role" to inv.role,
                                    "inviteCode" to inv.inviteCode,
                                    "status" to inv.status,
                                    "createdByUid" to inv.createdByUid,
                                    "createdByName" to inv.createdByName,
                                    "createdAt" to inv.createdAt,
                                    "expiresAt" to inv.expiresAt,
                                    "acceptedByUid" to inv.acceptedByUid
                                ),
                                SetOptions.merge()
                            ).await()
                        }
                    }

                    "store" -> {
                        val swh = db.storeDao().getStoreWithHours(item.entityId)
                        if (swh != null) {
                            val s = swh.store
                            companyRef.collection("stores").document(s.id).set(
                                mapOf(
                                    "id" to s.id,
                                    "companyId" to s.companyId,
                                    "name" to s.name,
                                    "address" to s.address,
                                    "lat" to s.lat,
                                    "lon" to s.lon,
                                    "providerId" to s.providerId,
                                    "phone" to s.phone,
                                    "created" to s.created,
                                    "modified" to s.modified,
                                    "archived" to s.archived,
                                    "updatedByUid" to s.updatedByUid,
                                    "version" to s.version,
                                    "serverUpdatedAt" to FieldValue.serverTimestamp(),
                                    "hours" to swh.hours.map { h ->
                                        mapOf("day" to h.day, "mode" to h.mode, "windows" to h.windows)
                                    }
                                ),
                                SetOptions.merge()
                            ).await()
                            db.storeDao().markSyncState(s.id, SyncState.SYNCED, System.currentTimeMillis())
                        }
                    }

                    "job" -> {
                        val jwi = db.jobDao().getJobWithItems(item.entityId)
                        if (jwi != null) {
                            val j = jwi.job
                            companyRef.collection("jobs").document(j.id).set(
                                mapOf(
                                    "id" to j.id,
                                    "companyId" to j.companyId,
                                    "storeId" to j.storeId,
                                    "type" to j.type,
                                    "date" to j.date,
                                    "time" to j.time,
                                    "status" to j.status,
                                    "issue" to j.issue,
                                    "issueNote" to j.issueNote,
                                    "snapshotName" to j.snapshotName,
                                    "snapshotAddress" to j.snapshotAddress,
                                    "snapshotLat" to j.snapshotLat,
                                    "snapshotLon" to j.snapshotLon,
                                    "created" to j.created,
                                    "modified" to j.modified,
                                    "completed" to j.completed,
                                    "reminderVersion" to j.reminderVersion,
                                    "reminderEnabled" to j.reminderEnabled,
                                    "assignedDriverUid" to j.assignedDriverUid,
                                    "assignedDriverName" to j.assignedDriverName,
                                    "notes" to j.notes,
                                    "updatedByUid" to j.updatedByUid,
                                    "version" to j.version,
                                    "serverUpdatedAt" to FieldValue.serverTimestamp(),
                                    "items" to jwi.sortedItems.map { itm ->
                                        mapOf(
                                            "id" to itm.id,
                                            "position" to itm.position,
                                            "name" to itm.name,
                                            "planned" to itm.planned,
                                            "unit" to itm.unit,
                                            "actual" to itm.actual
                                        )
                                    }
                                ),
                                SetOptions.merge()
                            ).await()
                            db.jobDao().markSyncState(j.id, SyncState.SYNCED, System.currentTimeMillis())
                        }
                    }

                    "invoice_file" -> {
                        val iwp = db.invoiceDao().getInvoiceWithPages(item.entityId)
                        if (iwp != null && storage != null) {
                            val inv = iwp.invoice
                            val updatedPages = mutableListOf<InvoicePageEntity>()
                            for (page in iwp.sortedPages) {
                                var origRemote = page.originalStoragePath
                                var editRemote = page.editedStoragePath
                                if (origRemote == null) {
                                    val origFile = File(page.originalLocalPath)
                                    if (origFile.exists() && origFile.length() <= MAX_INVOICE_FILE_BYTES) {
                                        val path = "companies/${inv.companyId}/invoices/${inv.id}/orig_${page.id}.jpg"
                                        val meta = StorageMetadata.Builder().setContentType(page.mimeType).build()
                                        storage.reference.child(path).putFile(Uri.fromFile(origFile), meta).await()
                                        origRemote = path
                                    }
                                }
                                if (editRemote == null) {
                                    val editFile = File(page.editedLocalPath)
                                    if (editFile.exists() && editFile.length() <= MAX_INVOICE_FILE_BYTES) {
                                        val path = "companies/${inv.companyId}/invoices/${inv.id}/edit_${page.id}.jpg"
                                        val meta = StorageMetadata.Builder().setContentType("image/jpeg").build()
                                        storage.reference.child(path).putFile(Uri.fromFile(editFile), meta).await()
                                        editRemote = path
                                    }
                                }
                                updatedPages += page.copy(
                                    originalStoragePath = origRemote,
                                    editedStoragePath = editRemote
                                )
                            }
                            if (updatedPages.isNotEmpty()) {
                                db.invoiceDao().upsertPages(updatedPages)
                            }
                            var sigRemote = inv.signatureStoragePath
                            if (inv.signed && sigRemote == null && !inv.signatureLocalPath.isNullOrBlank()) {
                                val f = File(inv.signatureLocalPath)
                                if (f.exists()) {
                                    val path = "companies/${inv.companyId}/invoices/${inv.id}/signature.png"
                                    val meta = StorageMetadata.Builder().setContentType("image/png").build()
                                    storage.reference.child(path).putFile(Uri.fromFile(f), meta).await()
                                    sigRemote = path
                                }
                            }
                            var pdfRemote = inv.signedPdfStoragePath
                            if (inv.signed && pdfRemote == null && !inv.signedPdfLocalPath.isNullOrBlank()) {
                                val f = File(inv.signedPdfLocalPath)
                                if (f.exists()) {
                                    val path = "companies/${inv.companyId}/invoices/${inv.id}/signed_v${inv.version}.pdf"
                                    val meta = StorageMetadata.Builder().setContentType("application/pdf").build()
                                    storage.reference.child(path).putFile(Uri.fromFile(f), meta).await()
                                    pdfRemote = path
                                }
                            }
                            db.invoiceDao().upsertInvoice(
                                inv.copy(
                                    signatureStoragePath = sigRemote,
                                    signedPdfStoragePath = pdfRemote
                                )
                            )
                        }
                    }

                    "invoice" -> {
                        val iwp = db.invoiceDao().getInvoiceWithPages(item.entityId)
                        if (iwp != null) {
                            val inv = iwp.invoice
                            val finalStatus = when {
                                inv.status == InvoiceStatus.VOIDED -> InvoiceStatus.VOIDED
                                inv.signed -> InvoiceStatus.SYNCHRONIZED
                                else -> InvoiceStatus.NOT_SIGNED
                            }
                            val payload = mutableMapOf<String, Any?>(
                                "id" to inv.id,
                                "companyId" to inv.companyId,
                                "jobId" to inv.jobId,
                                "storeId" to inv.storeId,
                                "storeName" to inv.storeName,
                                "invoiceNumber" to inv.invoiceNumber,
                                "notes" to inv.notes,
                                "status" to finalStatus,
                                "version" to inv.version,
                                "supersedesInvoiceId" to inv.supersedesInvoiceId,
                                "voidedReason" to inv.voidedReason,
                                "createdByUid" to inv.createdByUid,
                                "createdByName" to inv.createdByName,
                                "createdAt" to inv.createdAt,
                                "modifiedAt" to inv.modifiedAt,
                                "signed" to inv.signed,
                                "recipientName" to inv.recipientName,
                                "recipientTitle" to inv.recipientTitle,
                                "acknowledgmentConfirmed" to inv.acknowledgmentConfirmed,
                                "signatureStoragePath" to inv.signatureStoragePath,
                                "signaturePageIndex" to inv.signaturePageIndex,
                                "signaturePosX" to inv.signaturePosX,
                                "signaturePosY" to inv.signaturePosY,
                                "signedLocalAt" to inv.signedLocalAt,
                                "signedPdfStoragePath" to inv.signedPdfStoragePath,
                                "signedPdfSha256" to inv.signedPdfSha256,
                                "totalBytes" to inv.totalBytes,
                                "serverUpdatedAt" to FieldValue.serverTimestamp(),
                                "pages" to iwp.sortedPages.map { p ->
                                    mapOf(
                                        "id" to p.id,
                                        "pageIndex" to p.pageIndex,
                                        "originalStoragePath" to p.originalStoragePath,
                                        "editedStoragePath" to p.editedStoragePath,
                                        "originalSha256" to p.originalSha256,
                                        "editedSha256" to p.editedSha256,
                                        "byteSize" to p.byteSize
                                    )
                                }
                            )
                            if (inv.signed && inv.signedServerAt == null) {
                                payload["signedServerAt"] = FieldValue.serverTimestamp()
                            }
                            companyRef.collection("invoices").document(inv.id)
                                .set(payload, SetOptions.merge()).await()
                            val confirmedNow = System.currentTimeMillis()
                            db.invoiceDao().updateSyncAndStatus(
                                invoiceId = inv.id,
                                syncState = SyncState.SYNCED,
                                status = finalStatus,
                                serverTs = confirmedNow,
                                signedServerTs = if (inv.signed) confirmedNow else null,
                                error = null
                            )
                        }
                    }

                    "audit" -> {
                        val logs = db.auditLogDao().observeByCompany(item.companyId)
                        companyRef.collection("auditLogs").document(item.entityId).set(
                            mapOf(
                                "id" to item.entityId,
                                "companyId" to item.companyId,
                                "serverTimestamp" to FieldValue.serverTimestamp()
                            ),
                            SetOptions.merge()
                        ).await()
                        db.auditLogDao().markSynced(item.entityId, SyncState.SYNCED, System.currentTimeMillis())
                        @Suppress("UNUSED_EXPRESSION")
                        logs
                    }
                }
                db.syncQueueDao().delete(item.id)
            } catch (e: Exception) {
                allSucceeded = false
                Log.w(TAG, "Sync failed for ${item.entityType}/${item.entityId}: ${e.message}")
                val nextRetries = item.retryCount + 1
                val backoffMs = (30_000L * (1L shl nextRetries.coerceAtMost(6)))
                db.syncQueueDao().upsert(
                    item.copy(
                        retryCount = nextRetries,
                        status = SyncState.FAILED,
                        lastError = e.message ?: "Sync failed",
                        nextAttemptAt = System.currentTimeMillis() + backoffMs
                    )
                )
                if (item.entityType == "invoice" || item.entityType == "invoice_file") {
                    db.invoiceDao().updateSyncAndStatus(
                        invoiceId = item.entityId,
                        syncState = SyncState.FAILED,
                        status = InvoiceStatus.UPLOAD_FAILED,
                        serverTs = null,
                        signedServerTs = null,
                        error = e.message ?: "Upload failed"
                    )
                }
                _syncState.value = _syncState.value.copy(lastError = e.message)
            }
        }

        _syncState.value = _syncState.value.copy(
            isSyncing = false,
            isOnline = allSucceeded,
            lastSyncedAt = if (allSucceeded) System.currentTimeMillis() else _syncState.value.lastSyncedAt
        )
        return allSucceeded
    }

    /**
     * Redeems an employee invitation via trusted Cloud Function when Firebase is configured,
     * or falls back to local redemption in offline mode.
     */
    suspend fun acceptInvitationCloudOrLocal(
        inviteCode: String,
        userUid: String,
        displayName: String,
        email: String
    ): Result<String> {
        val functions = FirebaseServices.functionsOrNull()
        if (functions != null && FirebaseServices.authOrNull()?.currentUser != null) {
            return runCatching {
                val res = functions.getHttpsCallable("acceptEmployeeInvitation")
                    .call(mapOf("inviteCode" to inviteCode.trim().uppercase()))
                    .await()
                @Suppress("UNCHECKED_CAST")
                val data = res.getData() as? Map<String, Any?> ?: emptyMap()
                val companyId = data["companyId"] as? String ?: ""
                val role = data["role"] as? String ?: EmployeeRole.DRIVER
                if (companyId.isNotBlank()) {
                    repository.setPreference(com.delifar.app.data.PrefKeys.ACTIVE_COMPANY_ID, companyId)
                    repository.setPreference(com.delifar.app.data.PrefKeys.ACTIVE_USER_ROLE, role)
                }
                companyId
            }
        }
        return repository.redeemInvitationLocally(inviteCode, userUid, displayName, email)
            .map { it.companyId }
    }
}
