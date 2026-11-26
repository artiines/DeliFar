package com.delifar.app.cloud

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
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
import com.delifar.app.data.InvoiceWithPages
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
import com.google.firebase.firestore.Source
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.io.File
import java.util.concurrent.TimeUnit

enum class CloudSyncStatus {
    OFFLINE_SAVING_LOCALLY,
    CONFIGURED_NOT_SIGNED_IN,
    AUTHENTICATION_REQUIRED,
    CONNECTING,
    SYNCHRONIZING,
    FULLY_SYNCHRONIZED,
    UPLOAD_PENDING,
    SYNCHRONIZATION_FAILED,
    ACCESS_DENIED_OR_UNCONFIGURED
}

enum class SyncFlushOutcome {
    NOT_CONFIGURED,
    NOT_SIGNED_IN,
    OFFLINE,
    ACCESS_DENIED_OR_UNCONFIGURED,
    FAILED_TRANSIENT,
    SUCCEEDED
}

data class CloudSyncUiState(
    val firebaseConfigured: Boolean = FirebaseServices.isConfigured,
    val isAuthenticated: Boolean = FirebaseServices.isAuthenticated,
    val isOnline: Boolean = false,
    val isConnecting: Boolean = false,
    val isSyncing: Boolean = false,
    val backendHealth: BackendHealth = if (FirebaseServices.isConfigured) BackendHealth.NOT_SIGNED_IN else BackendHealth.NOT_CONFIGURED,
    val lastSyncedAt: Long? = null,
    val lastError: String? = null,
    val currentlyUploadingInvoiceId: String? = null
)

/**
 * Pure, deterministic sync status resolver so the UI never displays "Synced" solely because
 * the local queue is empty or because FirebaseApp.initializeApp returned non-null.
 */
object SyncStatusResolver {
    fun resolve(
        state: CloudSyncUiState,
        pendingCount: Int,
        failedCount: Int,
        activeCompanyId: String,
        persistedLastSyncedAt: Long? = null,
        persistedLastError: String? = null
    ): CloudSyncStatus {
        val effectiveLastSyncedAt = state.lastSyncedAt ?: persistedLastSyncedAt
        val effectiveError = state.lastError ?: persistedLastError

        if (!state.firebaseConfigured) {
            return CloudSyncStatus.OFFLINE_SAVING_LOCALLY
        }
        if (state.isSyncing) {
            return CloudSyncStatus.SYNCHRONIZING
        }
        if (state.isConnecting) {
            return CloudSyncStatus.CONNECTING
        }
        if (state.backendHealth == BackendHealth.ACCESS_DENIED ||
            state.backendHealth == BackendHealth.BACKEND_NOT_CONFIGURED
        ) {
            return CloudSyncStatus.ACCESS_DENIED_OR_UNCONFIGURED
        }
        if (!state.isAuthenticated || state.backendHealth == BackendHealth.NOT_SIGNED_IN) {
            return if (activeCompanyId.isNotBlank() || pendingCount > 0 || failedCount > 0) {
                CloudSyncStatus.AUTHENTICATION_REQUIRED
            } else {
                CloudSyncStatus.CONFIGURED_NOT_SIGNED_IN
            }
        }
        if (failedCount > 0 || !effectiveError.isNullOrBlank()) {
            return CloudSyncStatus.SYNCHRONIZATION_FAILED
        }
        if (pendingCount > 0) {
            return CloudSyncStatus.UPLOAD_PENDING
        }
        if (!state.isOnline || state.backendHealth == BackendHealth.OFFLINE) {
            return CloudSyncStatus.OFFLINE_SAVING_LOCALLY
        }
        if (state.backendHealth == BackendHealth.REACHABLE && effectiveLastSyncedAt != null) {
            return CloudSyncStatus.FULLY_SYNCHRONIZED
        }
        return if (activeCompanyId.isBlank()) {
            CloudSyncStatus.OFFLINE_SAVING_LOCALLY
        } else {
            CloudSyncStatus.CONNECTING
        }
    }
}

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

        /**
         * Process-wide mutex shared across all CloudSyncManager instances (foreground
         * DeliFarViewModel triggers and background WorkManager SyncWorker runs) so two
         * workers never read or upload the same Room sync_queue item concurrently.
         */
        private val queueFlushMutex = Mutex()

        fun scheduleBackgroundSync(context: Context) {
            if (!FirebaseServices.isConfigured) return
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            runCatching {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    UNIQUE_SYNC_WORK,
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    req
                )
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listenerLock = Any()
    private val listeners = mutableListOf<ListenerRegistration>()
    @Volatile
    private var activeCompanyId: String = ""
    @Volatile
    private var listenerGeneration: Long = 0L

    private val _syncState = MutableStateFlow(
        CloudSyncUiState(
            firebaseConfigured = FirebaseServices.isConfigured,
            isAuthenticated = FirebaseServices.isAuthenticated,
            backendHealth = if (FirebaseServices.isConfigured) {
                if (FirebaseServices.isAuthenticated) BackendHealth.OFFLINE else BackendHealth.NOT_SIGNED_IN
            } else {
                BackendHealth.NOT_CONFIGURED
            }
        )
    )
    val syncState: StateFlow<CloudSyncUiState> = _syncState.asStateFlow()

    fun refreshAuthAndConfigState(persistedLastSyncedAt: Long? = null, persistedLastError: String? = null) {
        val configured = FirebaseServices.isConfigured
        val authed = FirebaseServices.isAuthenticated
        val current = _syncState.value
        val nextHealth = when {
            !configured -> BackendHealth.NOT_CONFIGURED
            !authed -> BackendHealth.NOT_SIGNED_IN
            current.backendHealth == BackendHealth.NOT_SIGNED_IN || current.backendHealth == BackendHealth.NOT_CONFIGURED ->
                BackendHealth.OFFLINE
            else -> current.backendHealth
        }
        _syncState.value = current.copy(
            firebaseConfigured = configured,
            isAuthenticated = authed,
            backendHealth = nextHealth,
            lastSyncedAt = current.lastSyncedAt ?: persistedLastSyncedAt,
            lastError = current.lastError ?: persistedLastError
        )
    }

    private fun recordListenerError(err: Throwable, op: OperationType, path: String) {
        FirebaseServices.handleFirestoreError(err, op, path)
        val classified = FirebaseServices.classifyBackendError(err)
        _syncState.value = _syncState.value.copy(
            isConnecting = false,
            isOnline = classified.health == BackendHealth.REACHABLE,
            isAuthenticated = classified.health != BackendHealth.NOT_SIGNED_IN && FirebaseServices.isAuthenticated,
            backendHealth = classified.health,
            lastError = classified.detail
        )
        scope.launch {
            runCatching {
                if (!classified.detail.isNullOrBlank()) {
                    repository.setPreference(com.delifar.app.data.PrefKeys.LAST_SYNC_ERROR, classified.detail)
                }
            }
        }
    }

    fun startRealtimeListeners(companyId: String, userUid: String, role: String) {
        stopListeners()
        if (!FirebaseServices.isConfigured) {
            _syncState.value = _syncState.value.copy(
                firebaseConfigured = false,
                isAuthenticated = false,
                isOnline = false,
                isConnecting = false,
                backendHealth = BackendHealth.NOT_CONFIGURED
            )
            return
        }
        if (!FirebaseServices.isAuthenticated) {
            _syncState.value = _syncState.value.copy(
                firebaseConfigured = true,
                isAuthenticated = false,
                isOnline = false,
                isConnecting = false,
                backendHealth = BackendHealth.NOT_SIGNED_IN
            )
            return
        }
        if (companyId.isBlank()) {
            _syncState.value = _syncState.value.copy(
                firebaseConfigured = true,
                isAuthenticated = true,
                isConnecting = false
            )
            return
        }
        val fs = FirebaseServices.firestoreOrNull() ?: return
        val gen = synchronized(listenerLock) {
            activeCompanyId = companyId
            ++listenerGeneration
        }

        _syncState.value = _syncState.value.copy(
            firebaseConfigured = true,
            isAuthenticated = true,
            isConnecting = true,
            lastError = null
        )

        scope.launch {
            val check = FirebaseServices.testConnectionDetailed(context, companyId)
            if (listenerGeneration != gen || activeCompanyId != companyId) return@launch
            val reachable = check.health == BackendHealth.REACHABLE
            val pendingNow = runCatching { db.syncQueueDao().getPendingCountNow(companyId) }.getOrDefault(0)
            val failedNow = runCatching { db.syncQueueDao().getFailedCountNow(companyId) }.getOrDefault(0)
            val now = System.currentTimeMillis()
            val verifiedSynced = reachable && pendingNow == 0 && failedNow == 0
            if (verifiedSynced) {
                runCatching {
                    repository.setPreference(com.delifar.app.data.PrefKeys.LAST_SYNCED_AT, now.toString())
                    repository.setPreference(com.delifar.app.data.PrefKeys.LAST_SYNC_ERROR, "")
                }
            } else if (!check.detail.isNullOrBlank() &&
                (check.health == BackendHealth.ACCESS_DENIED || check.health == BackendHealth.BACKEND_NOT_CONFIGURED)
            ) {
                runCatching {
                    repository.setPreference(com.delifar.app.data.PrefKeys.LAST_SYNC_ERROR, check.detail)
                }
            }
            if (listenerGeneration != gen || activeCompanyId != companyId) return@launch
            _syncState.value = _syncState.value.copy(
                firebaseConfigured = true,
                isAuthenticated = check.health != BackendHealth.NOT_SIGNED_IN && FirebaseServices.isAuthenticated,
                isConnecting = false,
                isOnline = reachable,
                backendHealth = check.health,
                lastSyncedAt = if (verifiedSynced) now else _syncState.value.lastSyncedAt,
                lastError = if (reachable) null else check.detail
            )
        }

        val companyRef = fs.collection("companies").document(companyId)
        val registered = mutableListOf<ListenerRegistration>()

        // 1. Company metadata
        registered += companyRef.addSnapshotListener { snap, err ->
            if (listenerGeneration != gen || activeCompanyId != companyId) return@addSnapshotListener
            if (err != null) {
                recordListenerError(err, OperationType.GET, "companies/$companyId")
                return@addSnapshotListener
            }
            if (snap != null && snap.exists()) {
                scope.launch {
                    if (listenerGeneration != gen || activeCompanyId != companyId) return@launch
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
        registered += companyRef.collection("stores").limit(500).addSnapshotListener { snaps, err ->
            if (listenerGeneration != gen || activeCompanyId != companyId) return@addSnapshotListener
            if (err != null) {
                recordListenerError(err, OperationType.LIST, "companies/$companyId/stores")
                return@addSnapshotListener
            }
            if (snaps == null) return@addSnapshotListener
            scope.launch {
                if (listenerGeneration != gen || activeCompanyId != companyId) return@launch
                db.withTransaction {
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
                            version = remoteVersion,
                            locationConfirmed = doc.getBoolean("locationConfirmed") ?: true,
                            geocodeSource = doc.getString("geocodeSource") ?: "cloud"
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
                }
                if (listenerGeneration == gen && activeCompanyId == companyId) {
                    _syncState.value = _syncState.value.copy(isOnline = true, lastSyncedAt = System.currentTimeMillis())
                }
            }
        }

        // 3. Jobs (Drivers only subscribe to their assigned deliveries/pickups)
        val jobsQuery = if (role == EmployeeRole.DRIVER && userUid.isNotBlank()) {
            companyRef.collection("jobs").whereEqualTo("assignedDriverUid", userUid).limit(500)
        } else {
            companyRef.collection("jobs").limit(500)
        }
        registered += jobsQuery.addSnapshotListener { snaps, err ->
            if (listenerGeneration != gen || activeCompanyId != companyId) return@addSnapshotListener
            if (err != null) {
                recordListenerError(err, OperationType.LIST, "companies/$companyId/jobs")
                return@addSnapshotListener
            }
            if (snaps == null) return@addSnapshotListener
            scope.launch {
                if (listenerGeneration != gen || activeCompanyId != companyId) return@launch
                db.withTransaction {
                    for (doc in snaps.documents) {
                        val remoteVersion = (doc.getLong("version") ?: 1L).toInt()
                        val remoteMod = doc.getLong("modified") ?: 0L
                        val local = db.jobDao().getJobWithItems(doc.id)?.job
                        if (local != null && local.syncState == SyncState.PENDING && local.version > remoteVersion) {
                            continue
                        }
                        if (local != null && local.version == remoteVersion && local.modified > remoteMod) {
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
                            modified = remoteMod.takeIf { it > 0L } ?: System.currentTimeMillis(),
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
        }

        // 4. Employees
        registered += companyRef.collection("employees").limit(200).addSnapshotListener { snaps, err ->
            if (listenerGeneration != gen || activeCompanyId != companyId) return@addSnapshotListener
            if (err != null) {
                recordListenerError(err, OperationType.LIST, "companies/$companyId/employees")
                return@addSnapshotListener
            }
            if (snaps == null) return@addSnapshotListener
            scope.launch {
                if (listenerGeneration != gen || activeCompanyId != companyId) return@launch
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

        // 5. Invoices (Drivers subscribe ONLY to invoices they created or are assigned to,
        // matching Firestore security rules so queries never fail with PERMISSION_DENIED)
        val invoiceQueries = if (role == EmployeeRole.DRIVER && userUid.isNotBlank()) {
            listOf(
                companyRef.collection("invoices").whereEqualTo("createdByUid", userUid).limit(500),
                companyRef.collection("invoices").whereEqualTo("assignedDriverUid", userUid).limit(500)
            )
        } else {
            listOf(companyRef.collection("invoices").limit(500))
        }
        for (invoiceQuery in invoiceQueries) {
            registered += invoiceQuery.addSnapshotListener { snaps, err ->
                if (listenerGeneration != gen || activeCompanyId != companyId) return@addSnapshotListener
                if (err != null) {
                    recordListenerError(err, OperationType.LIST, "companies/$companyId/invoices")
                    return@addSnapshotListener
                }
                if (snaps == null) return@addSnapshotListener
                scope.launch {
                    if (listenerGeneration != gen || activeCompanyId != companyId) return@launch
                    db.withTransaction {
                        for (doc in snaps.documents) {
                            val existingWithPages = db.invoiceDao().getInvoiceWithPages(doc.id)
                            val local = existingWithPages?.invoice
                            val remoteSigned = doc.getBoolean("signed") ?: false
                            val remoteStatus = doc.getString("status") ?: InvoiceStatus.SYNCHRONIZED
                            val remoteMod = doc.getLong("modifiedAt") ?: 0L
                            // Never allow an unsigned remote doc to overwrite a locally signed invoice
                            if (local != null && local.signed && !remoteSigned) continue
                            // Never allow a non-voided remote doc to overwrite a pending local void
                            if (local != null && local.status == InvoiceStatus.VOIDED && remoteStatus != InvoiceStatus.VOIDED) continue
                            // Preserve newer pending/failed local edits until uploaded
                            if (local != null && local.syncState != SyncState.SYNCED && local.modifiedAt > remoteMod) continue

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
                                status = remoteStatus,
                                version = (doc.getLong("version") ?: 1L).toInt(),
                                supersedesInvoiceId = doc.getString("supersedesInvoiceId"),
                                voidedReason = doc.getString("voidedReason"),
                                createdByUid = doc.getString("createdByUid") ?: "",
                                createdByName = doc.getString("createdByName") ?: "",
                                createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis(),
                                modifiedAt = remoteMod.takeIf { it > 0L } ?: System.currentTimeMillis(),
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
                                uploadError = null,
                                assignedDriverUid = doc.getString("assignedDriverUid") ?: local?.assignedDriverUid
                            )
                            db.invoiceDao().upsertInvoice(entity)

                            // Hydrate remote page metadata while preserving any local cached image paths
                            @Suppress("UNCHECKED_CAST")
                            val remotePages = doc.get("pages") as? List<Map<String, Any?>>
                            if (!remotePages.isNullOrEmpty()) {
                                val localPagesById = existingWithPages?.pages?.associateBy { it.id }.orEmpty()
                                val mergedPages = remotePages.mapIndexedNotNull { idx, m ->
                                    val pid = (m["id"] as? String)?.ifBlank { null } ?: return@mapIndexedNotNull null
                                    val lp = localPagesById[pid]
                                    InvoicePageEntity(
                                        id = pid,
                                        invoiceId = doc.id,
                                        pageIndex = (m["pageIndex"] as? Number)?.toInt() ?: idx,
                                        originalLocalPath = lp?.originalLocalPath.orEmpty(),
                                        editedLocalPath = lp?.editedLocalPath.orEmpty(),
                                        originalStoragePath = (m["originalStoragePath"] as? String) ?: lp?.originalStoragePath,
                                        editedStoragePath = (m["editedStoragePath"] as? String) ?: lp?.editedStoragePath,
                                        originalSha256 = (m["originalSha256"] as? String) ?: lp?.originalSha256.orEmpty(),
                                        editedSha256 = (m["editedSha256"] as? String) ?: lp?.editedSha256.orEmpty(),
                                        rotationDegrees = lp?.rotationDegrees ?: 0,
                                        contrastBoost = lp?.contrastBoost ?: false,
                                        cropInsetPercent = lp?.cropInsetPercent ?: 0,
                                        byteSize = (m["byteSize"] as? Number)?.toLong() ?: lp?.byteSize ?: 0L,
                                        mimeType = lp?.mimeType ?: "image/jpeg"
                                    )
                                }
                                if (mergedPages.isNotEmpty()) {
                                    db.invoiceDao().upsertPages(mergedPages)
                                }
                            }
                        }
                    }
                }
            }
        }

        // 6. Audit logs (Owner & Manager)
        if (role == EmployeeRole.OWNER || role == EmployeeRole.MANAGER) {
            registered += companyRef.collection("auditLogs").limit(200).addSnapshotListener { snaps, err ->
                if (listenerGeneration != gen || activeCompanyId != companyId) return@addSnapshotListener
                if (err != null) {
                    recordListenerError(err, OperationType.LIST, "companies/$companyId/auditLogs")
                    return@addSnapshotListener
                }
                if (snaps == null) return@addSnapshotListener
                scope.launch {
                    if (listenerGeneration != gen || activeCompanyId != companyId) return@launch
                    db.withTransaction {
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

        synchronized(listenerLock) {
            if (listenerGeneration == gen) {
                listeners.addAll(registered)
            } else {
                registered.forEach { runCatching { it.remove() } }
            }
        }
    }

    fun stopListeners() {
        synchronized(listenerLock) {
            listenerGeneration++
            listeners.forEach { runCatching { it.remove() } }
            listeners.clear()
            activeCompanyId = ""
        }
        _syncState.value = _syncState.value.copy(
            isConnecting = false,
            isAuthenticated = FirebaseServices.isAuthenticated
        )
    }

    fun shutdown() {
        stopListeners()
        scope.cancel()
    }

    /**
     * Flushes queued offline writes & invoice uploads strictly for the active company.
     * Returns true ONLY when Firebase is configured, authenticated, reachable, and all due items succeeded.
     */
    suspend fun flushPendingQueue(companyIdOverride: String? = null): Boolean {
        return flushPendingQueueDetailed(companyIdOverride) == SyncFlushOutcome.SUCCEEDED
    }

    /**
     * Detailed queue flush returning an exact [SyncFlushOutcome] so foreground UI and WorkManager
     * never misreport an unauthenticated, offline, or failed sync as successful.
     *
     * Serialized across the entire process via [queueFlushMutex] so foreground retries and
     * background [SyncWorker] runs never process the same queue item concurrently.
     */
    suspend fun flushPendingQueueDetailed(
        companyIdOverride: String? = null,
        retryFailedFirst: Boolean = false
    ): SyncFlushOutcome {
        if (!FirebaseServices.isConfigured) {
            _syncState.value = _syncState.value.copy(
                firebaseConfigured = false,
                isAuthenticated = false,
                isOnline = false,
                isSyncing = false,
                backendHealth = BackendHealth.NOT_CONFIGURED
            )
            return SyncFlushOutcome.NOT_CONFIGURED
        }
        if (!FirebaseServices.isAuthenticated) {
            _syncState.value = _syncState.value.copy(
                firebaseConfigured = true,
                isAuthenticated = false,
                isOnline = false,
                isSyncing = false,
                backendHealth = BackendHealth.NOT_SIGNED_IN,
                lastError = null
            )
            return SyncFlushOutcome.NOT_SIGNED_IN
        }
        if (!FirebaseServices.isNetworkAvailable(context)) {
            _syncState.value = _syncState.value.copy(
                firebaseConfigured = true,
                isAuthenticated = true,
                isOnline = false,
                isSyncing = false,
                backendHealth = BackendHealth.OFFLINE,
                lastError = null
            )
            return SyncFlushOutcome.OFFLINE
        }
        val fs = FirebaseServices.firestoreOrNull() ?: return SyncFlushOutcome.ACCESS_DENIED_OR_UNCONFIGURED
        val storage = FirebaseServices.storageOrNull()
        val targetCompanyId = companyIdOverride?.ifBlank { null }
            ?: activeCompanyId.ifBlank { null }
            ?: repository.getPreferences().activeCompanyId
        if (targetCompanyId.isBlank()) {
            _syncState.value = _syncState.value.copy(isSyncing = false)
            return SyncFlushOutcome.SUCCEEDED
        }

        val auditGateway = object : AuditRemoteGateway {
            override suspend fun createAudit(
                companyId: String,
                logId: String,
                payload: Map<String, Any?>
            ) {
                // Append-only create: NEVER use SetOptions.merge() on immutable audit logs.
                fs.collection("companies")
                    .document(companyId)
                    .collection("auditLogs")
                    .document(logId)
                    .set(payload)
                    .await()
            }

            override suspend fun fetchAuditFromServer(
                companyId: String,
                logId: String
            ): RemoteAuditDocument? {
                val snap = fs.collection("companies")
                    .document(companyId)
                    .collection("auditLogs")
                    .document(logId)
                    .get(Source.SERVER)
                    .await()
                if (!snap.exists()) return null
                return RemoteAuditDocument(
                    documentId = snap.id,
                    id = snap.getString("id") ?: "",
                    companyId = snap.getString("companyId") ?: "",
                    actorUid = snap.getString("actorUid") ?: "",
                    actorName = snap.getString("actorName") ?: "",
                    actorRole = snap.getString("actorRole") ?: "",
                    action = snap.getString("action") ?: "",
                    targetType = snap.getString("targetType") ?: "",
                    targetId = snap.getString("targetId") ?: "",
                    summary = snap.getString("summary") ?: "",
                    createdAt = snap.getLong("createdAt"),
                    serverTimestampMillis = (snap.get("serverTimestamp") as? Timestamp)?.toDate()?.time
                )
            }
        }

        return executeLockedQueueFlush(
            targetCompanyId = targetCompanyId,
            retryFailedFirst = retryFailedFirst,
            verifyConnectionWhenEmpty = {
                FirebaseServices.testConnectionDetailed(context, targetCompanyId)
            },
            uploadItem = { item, wasRetry ->
                uploadQueueItemToFirebase(fs, storage, auditGateway, item, wasRetry)
            }
        )
    }

    private suspend fun resolveDeferredFailureOutcome(targetCompanyId: String): SyncFlushOutcome? {
        val failedItems = db.syncQueueDao().getFailedItemsByCompany(targetCompanyId)
        if (failedItems.isEmpty()) return null
        val firstFailed = failedItems.first()
        val rawMsg = firstFailed.lastError ?: "Sync failed"
        val detail = formatQueueItemError(firstFailed, rawMsg)
        val classified = FirebaseServices.classifyBackendError(IllegalStateException(rawMsg))
        val outcome = if (classified.health == BackendHealth.ACCESS_DENIED ||
            classified.health == BackendHealth.BACKEND_NOT_CONFIGURED
        ) {
            SyncFlushOutcome.ACCESS_DENIED_OR_UNCONFIGURED
        } else {
            SyncFlushOutcome.FAILED_TRANSIENT
        }
        repository.setPreference(com.delifar.app.data.PrefKeys.LAST_SYNC_ERROR, detail)
        _syncState.value = _syncState.value.copy(
            isSyncing = false,
            currentlyUploadingInvoiceId = null,
            backendHealth = if (outcome == SyncFlushOutcome.ACCESS_DENIED_OR_UNCONFIGURED) {
                classified.health
            } else {
                _syncState.value.backendHealth
            },
            lastError = detail
        )
        return outcome
    }

    /**
     * Core serialized queue processor shared by production Firebase sync and concurrency tests.
     */
    internal suspend fun executeLockedQueueFlush(
        targetCompanyId: String,
        retryFailedFirst: Boolean = false,
        verifyConnectionWhenEmpty: suspend () -> BackendHealthCheck = {
            BackendHealthCheck(BackendHealth.REACHABLE)
        },
        uploadItem: suspend (com.delifar.app.data.SyncQueueEntity, Boolean) -> Unit
    ): SyncFlushOutcome = queueFlushMutex.withLock {
        try {
            if (retryFailedFirst && targetCompanyId.isNotBlank()) {
                repository.retryFailedSyncItems(targetCompanyId)
            }

            val now = System.currentTimeMillis()
            val rawDue = db.syncQueueDao().getDueItemsForCompany(targetCompanyId, now)
            if (rawDue.isEmpty()) {
                val pendingCount = db.syncQueueDao().getPendingCountNow(targetCompanyId)
                resolveDeferredFailureOutcome(targetCompanyId)?.let { return@withLock it }
                val check = verifyConnectionWhenEmpty()
                return@withLock when (check.health) {
                    BackendHealth.REACHABLE -> {
                        val syncedAt = if (pendingCount == 0) System.currentTimeMillis() else _syncState.value.lastSyncedAt
                        if (syncedAt != null && pendingCount == 0) {
                            repository.setPreference(com.delifar.app.data.PrefKeys.LAST_SYNCED_AT, syncedAt.toString())
                            repository.setPreference(com.delifar.app.data.PrefKeys.LAST_SYNC_ERROR, "")
                        }
                        _syncState.value = _syncState.value.copy(
                            firebaseConfigured = true,
                            isAuthenticated = true,
                            isOnline = true,
                            isSyncing = false,
                            currentlyUploadingInvoiceId = null,
                            backendHealth = BackendHealth.REACHABLE,
                            lastSyncedAt = syncedAt,
                            lastError = null
                        )
                        SyncFlushOutcome.SUCCEEDED
                    }
                    BackendHealth.ACCESS_DENIED, BackendHealth.BACKEND_NOT_CONFIGURED -> {
                        val detail = check.detail ?: "Access denied or backend not configured."
                        repository.setPreference(com.delifar.app.data.PrefKeys.LAST_SYNC_ERROR, detail)
                        _syncState.value = _syncState.value.copy(
                            isOnline = false,
                            isSyncing = false,
                            currentlyUploadingInvoiceId = null,
                            backendHealth = check.health,
                            lastError = detail
                        )
                        SyncFlushOutcome.ACCESS_DENIED_OR_UNCONFIGURED
                    }
                    BackendHealth.NOT_SIGNED_IN -> {
                        _syncState.value = _syncState.value.copy(
                            isAuthenticated = false,
                            isOnline = false,
                            isSyncing = false,
                            currentlyUploadingInvoiceId = null,
                            backendHealth = BackendHealth.NOT_SIGNED_IN
                        )
                        SyncFlushOutcome.NOT_SIGNED_IN
                    }
                    else -> {
                        _syncState.value = _syncState.value.copy(
                            isOnline = false,
                            isSyncing = false,
                            currentlyUploadingInvoiceId = null,
                            backendHealth = BackendHealth.OFFLINE,
                            lastError = check.detail
                        )
                        SyncFlushOutcome.OFFLINE
                    }
                }
            }

            val priorityOrder = mapOf(
                "company" to 0,
                "employee" to 1,
                "invitation" to 2,
                "store" to 3,
                "job" to 4,
                "invoice_file" to 5,
                "invoice" to 6,
                "audit" to 7
            )
            val due = rawDue.sortedWith(
                compareBy<com.delifar.app.data.SyncQueueEntity> { priorityOrder[it.entityType] ?: 99 }
                    .thenBy { it.createdAt }
            )

            _syncState.value = _syncState.value.copy(
                firebaseConfigured = true,
                isAuthenticated = true,
                isSyncing = true,
                lastError = null,
                currentlyUploadingInvoiceId = null
            )
            var allSucceeded = true
            var lastClassifiedHealth = BackendHealth.REACHABLE
            var lastErrorMsg: String? = null
            val failedInvoiceFileIds = mutableSetOf<String>()

            for (queuedItem in due) {
                // Re-read inside the mutex so already-deleted/synced items are never re-processed or resurrected
                val item = db.syncQueueDao().getById(queuedItem.id) ?: continue
                if (item.status == SyncState.SYNCED) {
                    db.syncQueueDao().delete(item.id)
                    continue
                }
                if (item.entityType == "invoice" && item.entityId in failedInvoiceFileIds) {
                    continue // Wait until binary files upload before writing invoice metadata
                }
                if (item.entityType == "invoice_file" || item.entityType == "invoice") {
                    _syncState.value = _syncState.value.copy(currentlyUploadingInvoiceId = item.entityId)
                }
                val wasRetry = item.retryCount > 0 || item.status == SyncState.FAILED || !item.lastError.isNullOrBlank()
                try {
                    uploadItem(item, wasRetry)
                    db.syncQueueDao().delete(item.id)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    allSucceeded = false
                    if (item.entityType == "invoice_file") {
                        failedInvoiceFileIds += item.entityId
                    }
                    val rawError = if (e is InvoiceUploadException) {
                        "[${e.code.name}] ${e.message}"
                    } else {
                        e.message ?: e.javaClass.simpleName
                    }
                    val nextRetries = item.retryCount + 1
                    val diagnosticError = formatQueueItemError(item.copy(retryCount = nextRetries), rawError)
                    Log.w(TAG, "Sync failed for $diagnosticError")

                    // Self-healing recovery: if invoice metadata ran after a crash before its binaries finished,
                    // re-enqueue the binary upload so the next sync cycle uploads missing attachments first.
                    if (item.entityType == "invoice" &&
                        e is InvoiceUploadException &&
                        e.code == InvoiceUploadErrorCode.INCOMPLETE_REMOTE_ATTACHMENTS
                    ) {
                        db.syncQueueDao().upsert(
                            com.delifar.app.data.SyncQueueEntity(
                                id = "invoice_file:${item.entityId}",
                                companyId = item.companyId,
                                entityType = "invoice_file",
                                entityId = item.entityId,
                                operation = "upload_file",
                                status = SyncState.PENDING,
                                createdAt = System.currentTimeMillis(),
                                nextAttemptAt = System.currentTimeMillis()
                            )
                        )
                    }

                    val isPermanentFileError = e is InvoiceUploadException &&
                        e.code != InvoiceUploadErrorCode.INCOMPLETE_REMOTE_ATTACHMENTS
                    val baseBackoffMs = if (isPermanentFileError) 5L * 60_000L else 30_000L
                    val backoffMs = (baseBackoffMs * (1L shl nextRetries.coerceAtMost(4))).coerceAtMost(60L * 60_000L)
                    db.syncQueueDao().upsert(
                        item.copy(
                            retryCount = nextRetries,
                            status = SyncState.FAILED,
                            lastError = rawError,
                            nextAttemptAt = System.currentTimeMillis() + backoffMs
                        )
                    )
                    if (item.entityType == "invoice" || item.entityType == "invoice_file") {
                        val currentInv = db.invoiceDao().getInvoiceWithPages(item.entityId)?.invoice
                        val preservedStatus = if (currentInv?.status == InvoiceStatus.VOIDED) {
                            InvoiceStatus.VOIDED
                        } else {
                            InvoiceStatus.UPLOAD_FAILED
                        }
                        db.invoiceDao().updateSyncAndStatus(
                            invoiceId = item.entityId,
                            syncState = SyncState.FAILED,
                            status = preservedStatus,
                            serverTs = currentInv?.serverUpdatedAt,
                            signedServerTs = null,
                            error = rawError
                        )
                    }
                    if (e !is InvoiceUploadException) {
                        val classified = FirebaseServices.classifyBackendError(e)
                        if (classified.health == BackendHealth.ACCESS_DENIED ||
                            classified.health == BackendHealth.BACKEND_NOT_CONFIGURED ||
                            classified.health == BackendHealth.NOT_SIGNED_IN ||
                            classified.health == BackendHealth.OFFLINE
                        ) {
                            lastClassifiedHealth = classified.health
                        }
                    }
                    lastErrorMsg = diagnosticError
                    _syncState.value = _syncState.value.copy(lastError = diagnosticError)
                }
            }

            val remainingPending = runCatching { db.syncQueueDao().getPendingCountNow(targetCompanyId) }.getOrDefault(0)
            val remainingFailed = runCatching { db.syncQueueDao().getFailedCountNow(targetCompanyId) }.getOrDefault(0)
            if (allSucceeded && remainingFailed > 0) {
                resolveDeferredFailureOutcome(targetCompanyId)?.let { return@withLock it }
            }
            val fullySyncedNow = allSucceeded && remainingPending == 0 && remainingFailed == 0
            val completedAt = System.currentTimeMillis()

            if (fullySyncedNow) {
                repository.setPreference(com.delifar.app.data.PrefKeys.LAST_SYNCED_AT, completedAt.toString())
                repository.setPreference(com.delifar.app.data.PrefKeys.LAST_SYNC_ERROR, "")
            } else if (!lastErrorMsg.isNullOrBlank()) {
                repository.setPreference(com.delifar.app.data.PrefKeys.LAST_SYNC_ERROR, lastErrorMsg)
            }

            _syncState.value = _syncState.value.copy(
                isSyncing = false,
                currentlyUploadingInvoiceId = null,
                isOnline = allSucceeded && lastClassifiedHealth == BackendHealth.REACHABLE,
                backendHealth = if (allSucceeded) BackendHealth.REACHABLE else lastClassifiedHealth,
                lastSyncedAt = if (fullySyncedNow) completedAt else _syncState.value.lastSyncedAt,
                lastError = if (fullySyncedNow) null else lastErrorMsg
            )

            when {
                allSucceeded -> SyncFlushOutcome.SUCCEEDED
                lastClassifiedHealth == BackendHealth.ACCESS_DENIED ||
                    lastClassifiedHealth == BackendHealth.BACKEND_NOT_CONFIGURED ->
                    SyncFlushOutcome.ACCESS_DENIED_OR_UNCONFIGURED
                lastClassifiedHealth == BackendHealth.NOT_SIGNED_IN ->
                    SyncFlushOutcome.NOT_SIGNED_IN
                lastClassifiedHealth == BackendHealth.OFFLINE ->
                    SyncFlushOutcome.OFFLINE
                else -> SyncFlushOutcome.FAILED_TRANSIENT
            }
        } finally {
            _syncState.value = _syncState.value.copy(
                isSyncing = false,
                currentlyUploadingInvoiceId = null
            )
        }
    }

    private fun formatQueueItemError(item: com.delifar.app.data.SyncQueueEntity, errorText: String): String =
        "${item.entityType} (${item.operation}) [${item.entityId}] (attempt ${item.retryCount}): $errorText"

    private suspend fun uploadQueueItemToFirebase(
        fs: com.google.firebase.firestore.FirebaseFirestore,
        storage: com.google.firebase.storage.FirebaseStorage?,
        auditGateway: AuditRemoteGateway,
        item: com.delifar.app.data.SyncQueueEntity,
        wasRetry: Boolean
    ) {
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
                                    "locationConfirmed" to s.locationConfirmed,
                                    "geocodeSource" to s.geocodeSource,
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
                        if (iwp != null) {
                            val activeStorage = storage
                                ?: throw IllegalStateException("Firebase Cloud Storage is unavailable; retry upload when connected.")
                            val inv = iwp.invoice
                            if (iwp.sortedPages.isEmpty()) {
                                throw InvoiceUploadException(
                                    InvoiceUploadErrorCode.INCOMPLETE_REMOTE_ATTACHMENTS,
                                    "Invoice #${inv.invoiceNumber} has no pages to upload."
                                )
                            }

                            suspend fun uploadVerifiedAttachment(
                                storagePath: String,
                                validated: ValidatedAttachment,
                                immutable: Boolean
                            ): String {
                                val ref = activeStorage.reference.child(storagePath)
                                if (immutable || inv.signed) {
                                    // Idempotent retry: if file already reached Storage on a prior
                                    // interrupted attempt with matching byte size & sha256, reuse it without overwriting.
                                    val existingMeta = runCatching { ref.metadata.await() }.getOrNull()
                                    if (existingMeta != null) {
                                        val remoteSha = existingMeta.getCustomMetadata("sha256")
                                        val matches = existingMeta.sizeBytes == validated.byteSize &&
                                            (remoteSha.isNullOrBlank() || remoteSha.equals(validated.sha256, ignoreCase = true))
                                        if (matches) {
                                            return storagePath
                                        }
                                        if (immutable) {
                                            throw InvoiceUploadException(
                                                InvoiceUploadErrorCode.CHECKSUM_MISMATCH,
                                                "Remote attachment $storagePath differs from local file checksum/size and cannot be overwritten."
                                            )
                                        }
                                    }
                                }
                                val meta = StorageMetadata.Builder()
                                    .setContentType(validated.contentType)
                                    .setCustomMetadata("sha256", validated.sha256)
                                    .build()
                                val snap = ref.putFile(Uri.fromFile(validated.file), meta).await()
                                if (snap.bytesTransferred < validated.byteSize) {
                                    throw IllegalStateException(
                                        "Incomplete upload for $storagePath (${snap.bytesTransferred}/${validated.byteSize} bytes)."
                                    )
                                }
                                return storagePath
                            }

                            for (page in iwp.sortedPages) {
                                var origRemote = page.originalStoragePath
                                var editRemote = page.editedStoragePath
                                if (origRemote.isNullOrBlank()) {
                                    val validatedOrig = InvoiceSyncValidator.validateLocalAttachment(
                                        localPath = page.originalLocalPath,
                                        label = "invoice #${inv.invoiceNumber} page ${page.pageIndex + 1} original",
                                        expectedMimeType = page.mimeType.ifBlank { "image/jpeg" },
                                        expectedSha256 = page.originalSha256,
                                        maxBytes = MAX_INVOICE_FILE_BYTES
                                    )
                                    val ext = if (validatedOrig.contentType == "image/png") "png" else "jpg"
                                    val path = "companies/${inv.companyId}/invoices/${inv.id}/orig_${page.id}.$ext"
                                    origRemote = uploadVerifiedAttachment(path, validatedOrig, immutable = true)
                                    db.invoiceDao().upsertPages(listOf(page.copy(originalStoragePath = origRemote)))
                                }
                                if (editRemote.isNullOrBlank()) {
                                    val (editedPath, editedMime, editedExt) = InvoiceSyncValidator.resolveEditedAttachmentSource(page)
                                    val validatedEdit = InvoiceSyncValidator.validateLocalAttachment(
                                        localPath = editedPath,
                                        label = "invoice #${inv.invoiceNumber} page ${page.pageIndex + 1} edited",
                                        expectedMimeType = editedMime,
                                        expectedSha256 = page.editedSha256.ifBlank { null },
                                        maxBytes = MAX_INVOICE_FILE_BYTES
                                    )
                                    val path = "companies/${inv.companyId}/invoices/${inv.id}/edit_${page.id}.$editedExt"
                                    editRemote = uploadVerifiedAttachment(path, validatedEdit, immutable = inv.signed)
                                    db.invoiceDao().upsertPages(
                                        listOf(page.copy(originalStoragePath = origRemote, editedStoragePath = editRemote))
                                    )
                                }
                            }

                            var sigRemote = inv.signatureStoragePath
                            if (inv.signed && sigRemote.isNullOrBlank()) {
                                val validatedSig = InvoiceSyncValidator.validateLocalAttachment(
                                    localPath = inv.signatureLocalPath,
                                    label = "invoice #${inv.invoiceNumber} signature",
                                    expectedMimeType = "image/png",
                                    expectedSha256 = null,
                                    maxBytes = MAX_INVOICE_FILE_BYTES
                                )
                                val path = "companies/${inv.companyId}/invoices/${inv.id}/signature.png"
                                sigRemote = uploadVerifiedAttachment(path, validatedSig, immutable = true)
                                val latestInv = db.invoiceDao().getInvoiceWithPages(inv.id)?.invoice ?: inv
                                db.invoiceDao().upsertInvoice(latestInv.copy(signatureStoragePath = sigRemote))
                            }

                            var pdfRemote = inv.signedPdfStoragePath
                            if (inv.signed && pdfRemote.isNullOrBlank()) {
                                val validatedPdf = InvoiceSyncValidator.validateLocalAttachment(
                                    localPath = inv.signedPdfLocalPath,
                                    label = "invoice #${inv.invoiceNumber} signed PDF",
                                    expectedMimeType = "application/pdf",
                                    expectedSha256 = inv.signedPdfSha256,
                                    maxBytes = MAX_INVOICE_FILE_BYTES
                                )
                                val path = "companies/${inv.companyId}/invoices/${inv.id}/signed_v${inv.version}.pdf"
                                pdfRemote = uploadVerifiedAttachment(path, validatedPdf, immutable = true)
                                val latestInv = db.invoiceDao().getInvoiceWithPages(inv.id)?.invoice ?: inv
                                db.invoiceDao().upsertInvoice(
                                    latestInv.copy(
                                        signatureStoragePath = sigRemote ?: latestInv.signatureStoragePath,
                                        signedPdfStoragePath = pdfRemote,
                                        signedPdfSha256 = validatedPdf.sha256
                                    )
                                )
                            }
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
                            val invoiceGateway = object : InvoiceMetadataGateway {
                                override suspend fun fetchInvoiceFromServer(
                                    companyId: String,
                                    invoiceId: String
                                ): RemoteInvoiceSnapshot? {
                                    val snap = fs.collection("companies")
                                        .document(companyId)
                                        .collection("invoices")
                                        .document(invoiceId)
                                        .get(Source.SERVER)
                                        .await()
                                    if (!snap.exists()) return null
                                    return RemoteInvoiceSnapshot(
                                        documentId = snap.id,
                                        id = snap.getString("id") ?: "",
                                        companyId = snap.getString("companyId") ?: "",
                                        storeId = snap.getString("storeId") ?: "",
                                        invoiceNumber = snap.getString("invoiceNumber") ?: "",
                                        version = (snap.getLong("version") ?: 1L).toInt(),
                                        status = snap.getString("status") ?: "",
                                        signed = snap.getBoolean("signed") ?: false,
                                        recipientName = snap.getString("recipientName") ?: "",
                                        signatureStoragePath = snap.getString("signatureStoragePath"),
                                        signedPdfStoragePath = snap.getString("signedPdfStoragePath"),
                                        signedPdfSha256 = snap.getString("signedPdfSha256"),
                                        signedServerAtMillis = (snap.get("signedServerAt") as? Timestamp)?.toDate()?.time
                                    )
                                }

                                override suspend fun createInvoice(
                                    companyId: String,
                                    invoiceId: String,
                                    payload: Map<String, Any?>
                                ) {
                                    fs.collection("companies")
                                        .document(companyId)
                                        .collection("invoices")
                                        .document(invoiceId)
                                        .set(payload)
                                        .await()
                                }

                                override suspend fun mergeUnsignedInvoice(
                                    companyId: String,
                                    invoiceId: String,
                                    payload: Map<String, Any?>
                                ) {
                                    fs.collection("companies")
                                        .document(companyId)
                                        .collection("invoices")
                                        .document(invoiceId)
                                        .set(payload, SetOptions.merge())
                                        .await()
                                }

                                override suspend fun updateSignedInvoiceFields(
                                    companyId: String,
                                    invoiceId: String,
                                    fields: Map<String, Any?>
                                ) {
                                    fs.collection("companies")
                                        .document(companyId)
                                        .collection("invoices")
                                        .document(invoiceId)
                                        .update(fields)
                                        .await()
                                }
                            }

                            val uploadResult = InvoiceSyncValidator.uploadInvoiceMetadataIdempotently(
                                iwp = iwp,
                                finalStatus = finalStatus,
                                wasRetry = wasRetry,
                                gateway = invoiceGateway,
                                buildFullPayload = ::buildFullInvoicePayload,
                                serverTimestampValue = FieldValue.serverTimestamp()
                            )

                            val confirmedNow = System.currentTimeMillis()
                            db.invoiceDao().updateSyncAndStatus(
                                invoiceId = inv.id,
                                syncState = SyncState.SYNCED,
                                status = finalStatus,
                                serverTs = confirmedNow,
                                signedServerTs = if (inv.signed) {
                                    uploadResult.signedServerAtMillis ?: inv.signedServerAt ?: confirmedNow
                                } else null,
                                error = null
                            )
                        }
                    }

                    "audit" -> {
                        val entry = db.auditLogDao().getById(item.entityId)
                        if (entry != null) {
                            val currentAuthUid = FirebaseServices.authOrNull()?.currentUser?.uid
                            val actorUidToSend = currentAuthUid?.ifBlank { null } ?: entry.actorUid
                            val uploadRes = IdempotentAuditUploader.uploadIdempotently(
                                entry = entry,
                                actorUidToSend = actorUidToSend,
                                preferVerifyFirst = wasRetry,
                                gateway = auditGateway,
                                serverTimestampValue = FieldValue.serverTimestamp()
                            )
                            db.auditLogDao().markSynced(
                                id = entry.id,
                                syncState = SyncState.SYNCED,
                                serverTs = uploadRes.serverTimestampMillis
                            )
                        }
                    }
                }
    }

    private fun buildFullInvoicePayload(iwp: InvoiceWithPages, finalStatus: String): MutableMap<String, Any?> {
        val inv = iwp.invoice
        return mutableMapOf(
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
            "assignedDriverUid" to inv.assignedDriverUid,
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
            "signedServerAt" to if (inv.signed) FieldValue.serverTimestamp() else null,
            "signedPdfStoragePath" to inv.signedPdfStoragePath,
            "signedPdfSha256" to inv.signedPdfSha256,
            "totalBytes" to inv.totalBytes,
            "serverUpdatedAt" to FieldValue.serverTimestamp(),
            "pages" to iwp.sortedPages.map { pg ->
                mapOf(
                    "id" to pg.id,
                    "pageIndex" to pg.pageIndex,
                    "originalStoragePath" to pg.originalStoragePath,
                    "editedStoragePath" to pg.editedStoragePath,
                    "originalSha256" to pg.originalSha256,
                    "editedSha256" to pg.editedSha256,
                    "rotationDegrees" to pg.rotationDegrees,
                    "contrastBoost" to pg.contrastBoost,
                    "cropInsetPercent" to pg.cropInsetPercent,
                    "byteSize" to pg.byteSize,
                    "mimeType" to pg.mimeType
                )
            }
        )
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
        val authUser = FirebaseServices.authOrNull()?.currentUser
        if (functions != null && authUser != null) {
            return runCatching {
                val res = functions.getHttpsCallable("acceptEmployeeInvitation")
                    .call(mapOf("inviteCode" to inviteCode.trim().uppercase()))
                    .await()
                @Suppress("UNCHECKED_CAST")
                val data = res.getData() as? Map<String, Any?> ?: emptyMap()
                val companyId = data["companyId"] as? String ?: ""
                val role = data["role"] as? String ?: EmployeeRole.DRIVER
                if (companyId.isNotBlank()) {
                    val effectiveName = authUser.displayName?.takeIf { it.isNotBlank() }
                        ?: displayName.trim().ifBlank { authUser.email?.substringBefore("@") ?: "Team Member" }
                    val effectiveEmail = authUser.email?.trim()?.lowercase()?.ifBlank { null }
                        ?: email.trim().lowercase()
                    val now = System.currentTimeMillis()
                    db.withTransaction {
                        repository.setPreference(com.delifar.app.data.PrefKeys.ACTIVE_COMPANY_ID, companyId)
                        repository.setPreference(com.delifar.app.data.PrefKeys.ACTIVE_USER_UID, authUser.uid)
                        repository.setPreference(com.delifar.app.data.PrefKeys.ACTIVE_USER_ROLE, role)
                        if (effectiveName.isNotBlank()) {
                            repository.setPreference(com.delifar.app.data.PrefKeys.ACTIVE_USER_NAME, effectiveName)
                        }
                        if (effectiveEmail.isNotBlank()) {
                            repository.setPreference(com.delifar.app.data.PrefKeys.ACTIVE_USER_EMAIL, effectiveEmail)
                        }
                        db.employeeDao().upsert(
                            EmployeeEntity(
                                uid = authUser.uid,
                                companyId = companyId,
                                displayName = effectiveName,
                                email = effectiveEmail,
                                role = role,
                                active = true,
                                joinedAt = now,
                                lastActiveAt = now
                            )
                        )
                    }
                }
                companyId
            }
        }
        return repository.redeemInvitationLocally(inviteCode, userUid, displayName, email)
            .map { it.companyId }
    }
}
