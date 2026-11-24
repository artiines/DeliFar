package com.delifar.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.delifar.app.cloud.CloudSyncManager
import com.delifar.app.cloud.CloudSyncStatus
import com.delifar.app.cloud.CloudSyncUiState
import com.delifar.app.cloud.FirebaseServices
import com.delifar.app.cloud.SyncFlushOutcome
import com.delifar.app.cloud.SyncStatusResolver
import com.delifar.app.data.AppPreferences
import com.delifar.app.data.AuditLogEntity
import com.delifar.app.data.CompanyEntity
import com.delifar.app.data.DeliFarDatabase
import com.delifar.app.data.DeliFarRepository
import com.delifar.app.data.DraftEntity
import com.delifar.app.data.DraftItemInput
import com.delifar.app.data.EmployeeEntity
import com.delifar.app.data.EmployeeRole
import com.delifar.app.data.InvitationEntity
import com.delifar.app.data.InvoiceEntity
import com.delifar.app.data.InvoicePageEntity
import com.delifar.app.data.InvoiceWithPages
import com.delifar.app.data.JobEntity
import com.delifar.app.data.JobListRow
import com.delifar.app.data.JobWithItems
import com.delifar.app.data.MigrationSummary
import com.delifar.app.data.PrefKeys
import com.delifar.app.data.StoreEntity
import com.delifar.app.data.StoreHoursEntity
import com.delifar.app.data.StoreWithHours
import com.delifar.app.documents.SignatureStrokePoint
import com.delifar.app.documents.SignedPdfGenerator
import com.delifar.app.domain.ArchiveImportPreview
import com.delifar.app.domain.BackupManager
import com.delifar.app.domain.BusinessRules
import com.delifar.app.domain.EncryptedArchiveBackupManager
import com.delifar.app.domain.ImportPreview
import java.io.File
import com.delifar.app.network.AddressGeocoderService
import com.delifar.app.network.ComputedRouteResult
import com.delifar.app.network.GeoPoint
import com.delifar.app.network.GeoValidation
import com.delifar.app.network.GeocodeCandidate
import com.delifar.app.network.OsmRoutesClient
import com.delifar.app.reminders.ReminderScheduler
import com.google.firebase.auth.UserProfileChangeRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

data class PlaceSearchUiState(
    val loading: Boolean = false,
    val results: List<GeocodeCandidate> = emptyList(),
    val error: String? = null,
    val searched: Boolean = false
)

data class RoutePlanUiState(
    val loading: Boolean = false,
    val route: ComputedRouteResult? = null,
    val error: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class DeliFarViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val db = DeliFarDatabase.getInstance(appContext)
    val repository = DeliFarRepository(db)
    val backupManager = BackupManager(db)
    val encryptedBackupManager = EncryptedArchiveBackupManager(db)
    val syncManager = CloudSyncManager(appContext, db, repository)

    val geocoderService = AddressGeocoderService(appContext, repository)
    val routesClient = OsmRoutesClient()

    val preferences: StateFlow<AppPreferences> = repository.observePreferences()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppPreferences())

    private val activeCompanyIdFlow = preferences.map { it.activeCompanyId }.distinctUntilChanged()

    val stores: StateFlow<List<StoreWithHours>> = activeCompanyIdFlow
        .flatMapLatest { companyId -> repository.observeStores(companyId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val jobRows: StateFlow<List<JobListRow>> = activeCompanyIdFlow
        .flatMapLatest { companyId -> repository.observeJobRows(companyId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val invoices: StateFlow<List<InvoiceWithPages>> = activeCompanyIdFlow
        .flatMapLatest { companyId -> repository.observeInvoices(companyId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val latestDraft: StateFlow<DraftEntity?> = repository.observeLatestDraft()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val activeCompany: StateFlow<CompanyEntity?> = activeCompanyIdFlow
        .flatMapLatest { companyId ->
            if (companyId.isBlank()) flowOf(null) else repository.observeCompany(companyId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val employees: StateFlow<List<EmployeeEntity>> = activeCompanyIdFlow
        .flatMapLatest { companyId ->
            if (companyId.isBlank()) flowOf(emptyList()) else repository.observeEmployees(companyId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val invitations: StateFlow<List<InvitationEntity>> = activeCompanyIdFlow
        .flatMapLatest { companyId ->
            if (companyId.isBlank()) flowOf(emptyList()) else repository.observeInvitations(companyId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val auditLogs: StateFlow<List<AuditLogEntity>> = activeCompanyIdFlow
        .flatMapLatest { companyId -> repository.observeAuditLogs(companyId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val pendingSyncCount: StateFlow<Int> = activeCompanyIdFlow
        .flatMapLatest { companyId -> repository.observePendingSyncCount(companyId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val failedSyncCount: StateFlow<Int> = activeCompanyIdFlow
        .flatMapLatest { companyId -> repository.observeFailedSyncCount(companyId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val latestSyncError: StateFlow<String?> = activeCompanyIdFlow
        .flatMapLatest { companyId -> repository.observeLatestSyncError(companyId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val syncState: StateFlow<CloudSyncUiState> = syncManager.syncState

    val resolvedSyncStatus: StateFlow<CloudSyncStatus> = combine(
        syncState,
        pendingSyncCount,
        failedSyncCount,
        preferences,
        latestSyncError
    ) { state, pending, failed, prefs, queueErr ->
        SyncStatusResolver.resolve(
            state = state,
            pendingCount = pending,
            failedCount = failed,
            activeCompanyId = prefs.activeCompanyId,
            persistedLastSyncedAt = prefs.lastSyncedAt,
            persistedLastError = queueErr ?: prefs.lastSyncError
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CloudSyncStatus.OFFLINE_SAVING_LOCALLY)

    private val _offlineOwnedCompanies = MutableStateFlow<List<CompanyEntity>>(emptyList())
    val offlineOwnedCompanies: StateFlow<List<CompanyEntity>> = _offlineOwnedCompanies.asStateFlow()

    private val _userCompanies = MutableStateFlow<List<CompanyEntity>>(emptyList())
    val userCompanies: StateFlow<List<CompanyEntity>> = _userCompanies.asStateFlow()

    private val _searchState = MutableStateFlow(PlaceSearchUiState())
    val searchState: StateFlow<PlaceSearchUiState> = _searchState.asStateFlow()

    private val _routeState = MutableStateFlow(RoutePlanUiState())
    val routeState: StateFlow<RoutePlanUiState> = _routeState.asStateFlow()

    private val _importPreview = MutableStateFlow<ImportPreview?>(null)
    val importPreview: StateFlow<ImportPreview?> = _importPreview.asStateFlow()

    private val _pendingEncryptedArchiveFile = MutableStateFlow<File?>(null)
    val pendingEncryptedArchiveFile: StateFlow<File?> = _pendingEncryptedArchiveFile.asStateFlow()

    private val _archiveImportPreview = MutableStateFlow<ArchiveImportPreview?>(null)
    val archiveImportPreview: StateFlow<ArchiveImportPreview?> = _archiveImportPreview.asStateFlow()

    private var pendingArchivePassphrase: String = ""

    private val _pendingMigration = MutableStateFlow<MigrationSummary?>(null)
    val pendingMigration: StateFlow<MigrationSummary?> = _pendingMigration.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 6)
    val messages = _messages.asSharedFlow()

    init {
        rebuildReminders()
        viewModelScope.launch {
            preferences
                .map { Triple(it.activeCompanyId, it.activeUserUid, it.activeUserRole) }
                .distinctUntilChanged()
                .collect { (companyId, userUid, role) ->
                    val currentPrefs = preferences.value
                    syncManager.refreshAuthAndConfigState(
                        persistedLastSyncedAt = currentPrefs.lastSyncedAt,
                        persistedLastError = currentPrefs.lastSyncError
                    )
                    refreshCompanyListsInternal(FirebaseServices.currentAuthUid ?: userUid)
                    if (companyId.isNotBlank() && FirebaseServices.isConfigured && FirebaseServices.isAuthenticated) {
                        val authUid = FirebaseServices.currentAuthUid ?: userUid
                        syncManager.startRealtimeListeners(
                            companyId = companyId,
                            userUid = authUid,
                            role = role
                        )
                    } else {
                        syncManager.stopListeners()
                    }
                }
        }
    }

    private suspend fun refreshCompanyListsInternal(userUid: String) {
        _offlineOwnedCompanies.value = runCatching { repository.getOfflineOwnedCompanies() }.getOrDefault(emptyList())
        _userCompanies.value = runCatching { repository.getCompaniesForUser(userUid) }.getOrDefault(emptyList())
    }

    fun refreshCompanyLists() {
        viewModelScope.launch(Dispatchers.IO) {
            refreshCompanyListsInternal(FirebaseServices.currentAuthUid ?: preferences.value.activeUserUid)
        }
    }

    override fun onCleared() {
        syncManager.stopListeners()
        super.onCleared()
    }

    fun observeStore(storeId: String): Flow<StoreWithHours?> =
        repository.observeStore(storeId, preferences.value.activeCompanyId)
    fun observeJob(jobId: String): Flow<JobWithItems?> =
        repository.observeJobWithItems(jobId, preferences.value.activeCompanyId)
    fun observeInvoicesForJob(jobId: String): Flow<List<InvoiceWithPages>> =
        repository.observeInvoicesForJob(jobId, preferences.value.activeCompanyId)
    fun observeInvoice(invoiceId: String): Flow<InvoiceWithPages?> =
        repository.observeInvoice(invoiceId, preferences.value.activeCompanyId)

    fun notifyMessage(msg: String) {
        _messages.tryEmit(msg)
    }

    fun rebuildReminders() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { ReminderScheduler.rebuildAndDispatchIfDue(appContext) }
        }
    }

    fun triggerSyncNow() {
        viewModelScope.launch(Dispatchers.IO) {
            val activeCompanyId = preferences.value.activeCompanyId
            repository.retryFailedSyncItems(activeCompanyId)
            if (!FirebaseServices.isConfigured) {
                syncManager.refreshAuthAndConfigState(preferences.value.lastSyncedAt, preferences.value.lastSyncError)
                notifyMessage("Offline mode — changes are saved safely on this device.")
                return@launch
            }
            if (!FirebaseServices.isAuthenticated) {
                syncManager.refreshAuthAndConfigState(preferences.value.lastSyncedAt, preferences.value.lastSyncError)
                notifyMessage("Firebase is configured, but you are not signed in. Sign in under Account & Company to sync.")
                return@launch
            }
            CloudSyncManager.scheduleBackgroundSync(appContext)
            when (syncManager.flushPendingQueueDetailed(activeCompanyId)) {
                SyncFlushOutcome.SUCCEEDED ->
                    notifyMessage("Synchronized with Firebase cloud.")
                SyncFlushOutcome.OFFLINE ->
                    notifyMessage("Device is offline — changes are saved locally and queued for automatic upload.")
                SyncFlushOutcome.ACCESS_DENIED_OR_UNCONFIGURED ->
                    notifyMessage(syncManager.syncState.value.lastError ?: "Cloud access denied or Firestore backend not configured yet.")
                SyncFlushOutcome.NOT_SIGNED_IN ->
                    notifyMessage("Authentication required. Sign in to sync.")
                SyncFlushOutcome.NOT_CONFIGURED ->
                    notifyMessage("Offline mode — Firebase config is not bundled in this build.")
                SyncFlushOutcome.FAILED_TRANSIENT ->
                    notifyMessage(syncManager.syncState.value.lastError ?: "Some items failed to upload. Check Sync status or Invoices to retry.")
            }
        }
    }

    // --- Authentication & Company Setup ---

    suspend fun signInWithEmail(email: String, password: String): Result<Unit> {
        val auth = FirebaseServices.authOrNull()
            ?: return Result.failure(IllegalStateException("Firebase Auth requires app/google-services.json. Use Offline Mode or register a local company."))
        return runCatching {
            val res = auth.signInWithEmailAndPassword(email.trim(), password).await()
            val user = res.user ?: throw IllegalStateException("Authentication failed.")
            val displayName = user.displayName ?: email.substringBefore("@")
            repository.setPreference(PrefKeys.ACTIVE_USER_UID, user.uid)
            repository.setPreference(PrefKeys.ACTIVE_USER_EMAIL, user.email.orEmpty())
            repository.setPreference(PrefKeys.ACTIVE_USER_NAME, displayName)
            reconcileCompanyContextAfterFirebaseAuth(user.uid)
            syncManager.refreshAuthAndConfigState(preferences.value.lastSyncedAt, null)
            val activeId = repository.getPreferences().activeCompanyId
            if (activeId.isNotBlank()) {
                val activeComp = repository.getCompany(activeId)
                if (activeComp != null && !activeComp.ownerUid.startsWith("local_")) {
                    triggerSyncNow()
                }
            }
        }
    }

    suspend fun registerAccountWithEmail(name: String, email: String, password: String): Result<Unit> {
        val auth = FirebaseServices.authOrNull()
        if (auth == null) {
            // Allow local profile setup cleanly even before Firebase config is attached
            val localUid = "local_${java.util.UUID.randomUUID().toString().take(12)}"
            repository.setPreference(PrefKeys.ACTIVE_USER_UID, localUid)
            repository.setPreference(PrefKeys.ACTIVE_USER_NAME, name.trim().ifEmpty { email.substringBefore("@") })
            repository.setPreference(PrefKeys.ACTIVE_USER_EMAIL, email.trim().lowercase())
            refreshCompanyListsInternal(localUid)
            return Result.success(Unit)
        }
        return runCatching {
            val res = auth.createUserWithEmailAndPassword(email.trim(), password).await()
            val user = res.user ?: throw IllegalStateException("Registration failed.")
            val cleanName = name.trim().ifEmpty { email.substringBefore("@") }
            val req = UserProfileChangeRequest.Builder().setDisplayName(cleanName).build()
            runCatching { user.updateProfile(req).await() }
            repository.setPreference(PrefKeys.ACTIVE_USER_UID, user.uid)
            repository.setPreference(PrefKeys.ACTIVE_USER_EMAIL, user.email.orEmpty())
            repository.setPreference(PrefKeys.ACTIVE_USER_NAME, cleanName)
            reconcileCompanyContextAfterFirebaseAuth(user.uid)
            syncManager.refreshAuthAndConfigState(preferences.value.lastSyncedAt, null)
        }
    }

    /**
     * Ensures signing into a Firebase account NEVER silently assigns a locally created company
     * or another Firebase user's company to the wrong authenticated user.
     */
    private suspend fun reconcileCompanyContextAfterFirebaseAuth(authUid: String) {
        val currentPrefs = repository.getPreferences()
        val currentCompanyId = currentPrefs.activeCompanyId
        val ownedOffline = repository.getOfflineOwnedCompanies()
        val memberCompanies = repository.getCompaniesForUser(authUid)

        if (currentCompanyId.isNotBlank()) {
            val activeComp = repository.getCompany(currentCompanyId)
            val isMember = memberCompanies.any { it.id == currentCompanyId }
            if (activeComp == null || (!isMember && !activeComp.ownerUid.startsWith("local_"))) {
                // Active company belongs to a different cloud account -> clear it safely
                repository.setPreference(PrefKeys.ACTIVE_COMPANY_ID, "")
                repository.setPreference(PrefKeys.ACTIVE_USER_ROLE, EmployeeRole.OWNER)
                repository.setPreference(PrefKeys.LAST_SYNC_ERROR, "")
            } else if (activeComp.ownerUid.startsWith("local_")) {
                // Pause active cloud sync on local_* company until user explicitly confirms claiming it
                repository.setPreference(PrefKeys.ACTIVE_COMPANY_ID, "")
                repository.setPreference(PrefKeys.LAST_SYNC_ERROR, "")
            }
        }

        // If user is already an active member of a cloud company and has no active company selected, select it
        val refreshedCompanyId = repository.getPreferences().activeCompanyId
        if (refreshedCompanyId.isBlank() && memberCompanies.size == 1) {
            repository.switchActiveCompany(memberCompanies.first().id, authUid)
        }

        _offlineOwnedCompanies.value = ownedOffline
        _userCompanies.value = memberCompanies
    }

    suspend fun claimLocalCompany(localCompanyId: String): Result<CompanyEntity> {
        val authUser = FirebaseServices.authOrNull()?.currentUser
            ?: return Result.failure(IllegalStateException("Sign in to your Firebase account before claiming an offline company."))
        val prefs = preferences.value
        val res = repository.claimLocalCompanyForAuthenticatedUser(
            localCompanyId = localCompanyId,
            authUid = authUser.uid,
            authName = authUser.displayName?.takeIf { it.isNotBlank() } ?: prefs.activeUserName,
            authEmail = authUser.email?.takeIf { it.isNotBlank() } ?: prefs.activeUserEmail
        )
        res.onSuccess { claimed ->
            refreshCompanyListsInternal(authUser.uid)
            triggerSyncNow()
            notifyMessage("Offline company '${claimed.name}' linked to your cloud account.")
        }
        return res
    }

    suspend fun switchCompany(companyId: String): Result<CompanyEntity> {
        val uid = FirebaseServices.currentAuthUid ?: preferences.value.activeUserUid
        val res = repository.switchActiveCompany(companyId, uid)
        res.onSuccess { switched ->
            refreshCompanyListsInternal(uid)
            rebuildReminders()
            notifyMessage("Switched to ${switched.name}.")
        }
        res.onFailure { notifyMessage(it.message ?: "Could not switch company.") }
        return res
    }

    fun selectOfflineRole(role: String) {
        if (role !in EmployeeRole.ALL) return
        viewModelScope.launch {
            repository.setPreference(PrefKeys.ACTIVE_USER_ROLE, role)
        }
    }

    fun dismissOnboarding() {
        viewModelScope.launch {
            repository.setPreference(PrefKeys.ONBOARDING_DISMISSED, "true")
        }
    }

    fun signOut() {
        viewModelScope.launch {
            syncManager.stopListeners()
            runCatching { FirebaseServices.authOrNull()?.signOut() }
            repository.setPreference(PrefKeys.ACTIVE_COMPANY_ID, "")
            repository.setPreference(PrefKeys.ACTIVE_USER_UID, "")
            repository.setPreference(PrefKeys.ACTIVE_USER_EMAIL, "")
            repository.setPreference(PrefKeys.ACTIVE_USER_NAME, "")
            repository.setPreference(PrefKeys.ACTIVE_USER_ROLE, EmployeeRole.OWNER)
            repository.setPreference(PrefKeys.LAST_SYNC_ERROR, "")
            syncManager.refreshAuthAndConfigState(preferences.value.lastSyncedAt, null)
            refreshCompanyListsInternal("")
            clearSearchState()
            clearRoutePlan()
            rebuildReminders()
            notifyMessage("Signed out.")
        }
    }

    suspend fun registerCompany(
        companyName: String,
        ownerName: String,
        ownerEmail: String,
        timezone: String,
        defaultArea: String
    ): Result<CompanyEntity> {
        val prefs = preferences.value
        val uid = FirebaseServices.authOrNull()?.currentUser?.uid ?: prefs.activeUserUid
        val res = repository.registerCompany(
            companyName = companyName,
            ownerUid = uid,
            ownerName = ownerName.ifBlank { prefs.activeUserName },
            ownerEmail = ownerEmail.ifBlank { prefs.activeUserEmail },
            timezone = timezone,
            defaultArea = defaultArea
        )
        res.onSuccess {
            refreshCompanyListsInternal(uid)
            CloudSyncManager.scheduleBackgroundSync(appContext)
            checkUnmigratedLocalData()
            notifyMessage("Company '${it.name}' registered.")
        }
        return res
    }

    suspend fun updateCompanySettings(name: String, timezone: String, defaultArea: String): Result<CompanyEntity> {
        val prefs = preferences.value
        val res = repository.updateCompanySettings(
            companyId = prefs.activeCompanyId,
            name = name,
            timezone = timezone,
            defaultArea = defaultArea,
            actorUid = prefs.activeUserUid,
            actorName = prefs.activeUserName,
            actorRole = prefs.activeUserRole
        )
        res.onSuccess {
            CloudSyncManager.scheduleBackgroundSync(appContext)
            rebuildReminders()
            notifyMessage("Company settings saved.")
        }
        return res
    }

    suspend fun inviteEmployee(email: String, role: String): Result<InvitationEntity> {
        val prefs = preferences.value
        val company = activeCompany.value
            ?: return Result.failure(IllegalStateException("Register a company first."))
        val res = repository.createInvitation(
            companyId = company.id,
            companyName = company.name,
            email = email,
            role = role,
            actorUid = prefs.activeUserUid,
            actorName = prefs.activeUserName,
            actorRole = prefs.activeUserRole
        )
        res.onSuccess {
            CloudSyncManager.scheduleBackgroundSync(appContext)
            notifyMessage("Invite created: code ${it.inviteCode}")
        }
        return res
    }

    fun revokeInvitation(inviteId: String) {
        viewModelScope.launch {
            val p = preferences.value
            val res = repository.revokeInvitation(inviteId, p.activeUserUid, p.activeUserName, p.activeUserRole)
            res.onFailure { notifyMessage(it.message ?: "Failed to revoke invite.") }
            res.onSuccess {
                CloudSyncManager.scheduleBackgroundSync(appContext)
                notifyMessage("Invitation revoked.")
            }
        }
    }

    suspend fun joinCompanyWithInvite(code: String, displayName: String, email: String): Result<String> {
        val p = preferences.value
        val uid = FirebaseServices.authOrNull()?.currentUser?.uid ?: p.activeUserUid
        val res = syncManager.acceptInvitationCloudOrLocal(
            inviteCode = code,
            userUid = uid,
            displayName = displayName.ifBlank { p.activeUserName },
            email = email.ifBlank { p.activeUserEmail }
        )
        res.onSuccess {
            refreshCompanyListsInternal(uid)
            CloudSyncManager.scheduleBackgroundSync(appContext)
            notifyMessage("Joined company successfully.")
        }
        return res
    }

    fun updateEmployeeRole(targetUid: String, newRole: String) {
        viewModelScope.launch {
            val p = preferences.value
            val res = repository.updateEmployeeRole(
                targetUid, newRole, p.activeUserUid, p.activeUserName, p.activeUserRole, p.activeCompanyId
            )
            res.onFailure { notifyMessage(it.message ?: "Could not update role.") }
            res.onSuccess {
                CloudSyncManager.scheduleBackgroundSync(appContext)
                notifyMessage("Role updated.")
            }
        }
    }

    fun setEmployeeActive(targetUid: String, active: Boolean) {
        viewModelScope.launch {
            val p = preferences.value
            val res = repository.setEmployeeActive(
                targetUid, active, p.activeUserUid, p.activeUserName, p.activeUserRole, p.activeCompanyId
            )
            res.onFailure { notifyMessage(it.message ?: "Could not update employee status.") }
            res.onSuccess {
                CloudSyncManager.scheduleBackgroundSync(appContext)
                notifyMessage(if (active) "Employee activated." else "Employee deactivated.")
            }
        }
    }

    fun checkUnmigratedLocalData() {
        viewModelScope.launch {
            val summary = repository.getUnmigratedLocalSummary()
            if (summary.totalCount > 0 && preferences.value.activeCompanyId.isNotBlank() &&
                preferences.value.activeUserRole == EmployeeRole.OWNER
            ) {
                _pendingMigration.value = summary
            } else {
                _pendingMigration.value = null
            }
        }
    }

    fun dismissMigrationPrompt() {
        _pendingMigration.value = null
    }

    fun confirmMigrateLocalData() {
        viewModelScope.launch {
            val p = preferences.value
            val res = repository.migrateLocalDataToCompany(
                companyId = p.activeCompanyId,
                actorUid = p.activeUserUid,
                actorName = p.activeUserName,
                actorRole = p.activeUserRole
            )
            _pendingMigration.value = null
            res.fold(
                onSuccess = {
                    CloudSyncManager.scheduleBackgroundSync(appContext)
                    notifyMessage("Migrated ${it.storesCount} stores, ${it.jobsCount} jobs, ${it.invoicesCount} invoices.")
                },
                onFailure = { notifyMessage(it.message ?: "Migration failed.") }
            )
        }
    }

    // --- Free Geocoding (Android Geocoder + Configurable OSM) & OSRM Routing ---

    fun clearSearchState() {
        _searchState.value = PlaceSearchUiState()
    }

    suspend fun locateStoreAddress(rawAddress: String, area: String): com.delifar.app.network.GeocodeOutcome {
        val p = preferences.value
        return geocoderService.locateAddress(
            rawAddress = rawAddress,
            area = area,
            languageTag = p.language,
            osmBaseUrl = p.geocodingUrl,
            allowOsmFallback = p.allowOsmFallback
        )
    }

    fun searchPlaces(query: String, area: String) {
        viewModelScope.launch {
            _searchState.value = PlaceSearchUiState(loading = true)
            val p = preferences.value
            val outcome = geocoderService.searchDirectory(
                query = query,
                area = area,
                languageTag = p.language,
                osmBaseUrl = p.geocodingUrl,
                allowOsmFallback = p.allowOsmFallback
            )
            _searchState.value = PlaceSearchUiState(
                loading = false,
                results = outcome.candidates,
                error = outcome.errorMessage,
                searched = true
            )
        }
    }

    fun optimizeRouteForStops(stops: List<GeoPoint>) {
        val validStops = stops.filter { it.isValid() }
        if (validStops.size < 2) {
            notifyMessage("Need at least 2 stores with confirmed locations to optimize a route.")
            return
        }
        viewModelScope.launch {
            _routeState.value = RoutePlanUiState(loading = true)
            val result = routesClient.computeRoute(
                stops = validStops,
                routingBaseUrl = preferences.value.routingUrl,
                optimizeIntermediates = true
            )
            result.fold(
                onSuccess = { computed ->
                    _routeState.value = RoutePlanUiState(loading = false, route = computed)
                    val miles = computed.distanceMeters / 1609.34
                    val mins = computed.durationSeconds / 60
                    val prefix = if (computed.isStreetRoute) "Street route ready" else "Offline stop order"
                    notifyMessage("$prefix: %.1f mi • %d min".format(miles, mins))
                },
                onFailure = { err ->
                    _routeState.value = RoutePlanUiState(loading = false, error = err.message)
                    notifyMessage(err.message ?: "Route optimization failed.")
                }
            )
        }
    }

    fun clearRoutePlan() {
        _routeState.value = RoutePlanUiState()
    }

    suspend fun updateMappingSettings(
        geocodingUrl: String,
        routingUrl: String,
        mapStyleUrl: String,
        allowOsmFallback: Boolean
    ): Result<Unit> {
        val cleanGeo = geocodingUrl.trim()
        val cleanRoute = routingUrl.trim()
        val cleanStyle = mapStyleUrl.trim().ifBlank { AppPreferences.DEFAULT_MAP_STYLE_URL }

        if (cleanGeo.isNotEmpty() && !GeoValidation.isSafeHttpsEndpointUrl(cleanGeo)) {
            return Result.failure(IllegalArgumentException("Geocoding URL must be a valid public HTTPS URL."))
        }
        if (cleanRoute.isNotEmpty() && !GeoValidation.isSafeHttpsEndpointUrl(cleanRoute)) {
            return Result.failure(IllegalArgumentException("Routing URL must be blank or a valid public HTTPS URL."))
        }
        if (!GeoValidation.isSafeHttpsEndpointUrl(cleanStyle)) {
            return Result.failure(IllegalArgumentException("Map style URL must be a valid public HTTPS URL."))
        }

        repository.setPreference(PrefKeys.GEOCODING_URL, cleanGeo)
        repository.setPreference(PrefKeys.ROUTING_URL, cleanRoute)
        repository.setPreference(PrefKeys.MAP_STYLE_URL, cleanStyle)
        repository.setPreference(PrefKeys.ALLOW_OSM_FALLBACK, allowOsmFallback.toString())
        notifyMessage("Map & geocoding settings saved.")
        return Result.success(Unit)
    }

    // --- Stores ---

    suspend fun saveStore(
        existingId: String?,
        name: String,
        address: String,
        lat: Double,
        lon: Double,
        providerId: String?,
        phone: String,
        hours: List<StoreHoursEntity>,
        locationConfirmed: Boolean = true,
        geocodeSource: String = "manual"
    ): Result<StoreEntity> {
        val p = preferences.value
        val res = repository.saveStore(
            existingId = existingId,
            name = name,
            address = address,
            lat = lat,
            lon = lon,
            providerId = providerId,
            phone = phone,
            hours = hours,
            companyId = p.activeCompanyId,
            actorUid = p.activeUserUid,
            actorName = p.activeUserName,
            actorRole = p.activeUserRole,
            locationConfirmed = locationConfirmed,
            geocodeSource = geocodeSource
        )
        if (res.isSuccess) {
            CloudSyncManager.scheduleBackgroundSync(appContext)
            rebuildReminders()
        }
        return res
    }

    fun archiveStore(storeId: String, blockedMsg: String) {
        viewModelScope.launch {
            val p = preferences.value
            val res = repository.archiveStore(storeId, p.activeUserUid, p.activeUserName, p.activeUserRole, p.activeCompanyId)
            if (res.isFailure) {
                notifyMessage(if (res.exceptionOrNull()?.message == "ACTIVE_JOBS") blockedMsg else res.exceptionOrNull()?.message ?: blockedMsg)
            } else {
                CloudSyncManager.scheduleBackgroundSync(appContext)
            }
        }
    }

    fun restoreStore(storeId: String) {
        viewModelScope.launch {
            val p = preferences.value
            repository.restoreStore(storeId, p.activeUserUid, p.activeUserName, p.activeUserRole, p.activeCompanyId)
            CloudSyncManager.scheduleBackgroundSync(appContext)
        }
    }

    // --- Drafts & Jobs ---

    fun saveDraftAsync(draft: DraftEntity) {
        viewModelScope.launch(Dispatchers.IO) { repository.saveDraft(draft) }
    }

    fun discardDraft(jobId: String) {
        viewModelScope.launch(Dispatchers.IO) { repository.clearDraft(jobId) }
    }

    suspend fun saveJob(
        jobId: String,
        storeId: String,
        type: String,
        dateIso: String,
        timeIso: String?,
        reminderEnabled: Boolean,
        items: List<DraftItemInput>,
        assignedDriverUid: String? = null,
        assignedDriverName: String = "",
        notes: String = ""
    ): Result<JobEntity> {
        val p = preferences.value
        val res = repository.saveJobAtomically(
            jobId = jobId,
            storeId = storeId,
            type = type,
            dateIso = dateIso,
            timeIso = timeIso,
            reminderEnabled = reminderEnabled,
            items = items,
            assignedDriverUid = assignedDriverUid,
            assignedDriverName = assignedDriverName,
            notes = notes,
            companyId = p.activeCompanyId,
            actorUid = p.activeUserUid,
            actorName = p.activeUserName,
            actorRole = p.activeUserRole
        )
        if (res.isSuccess) {
            CloudSyncManager.scheduleBackgroundSync(appContext)
            rebuildReminders()
        }
        return res
    }

    fun assignDriverToJob(jobId: String, driverUid: String?, driverName: String) {
        viewModelScope.launch {
            val p = preferences.value
            val res = repository.assignDriverToJob(
                jobId, driverUid, driverName, p.activeUserUid, p.activeUserName, p.activeUserRole, p.activeCompanyId
            )
            res.onFailure { notifyMessage(it.message ?: "Could not assign driver.") }
            res.onSuccess {
                CloudSyncManager.scheduleBackgroundSync(appContext)
                notifyMessage(if (driverUid.isNullOrBlank()) "Driver unassigned." else "Assigned to $driverName.")
            }
        }
    }

    fun updateJobStatus(jobId: String, newStatus: String) {
        viewModelScope.launch {
            val p = preferences.value
            val res = repository.updateJobStatus(
                jobId, newStatus, p.activeUserUid, p.activeUserName, p.activeUserRole, p.activeCompanyId
            )
            res.onFailure { notifyMessage(it.message ?: "Could not update job.") }
            res.onSuccess {
                CloudSyncManager.scheduleBackgroundSync(appContext)
                rebuildReminders()
                val label = when (newStatus) {
                    com.delifar.app.data.JobStatus.PROGRESS -> "Started"
                    com.delifar.app.data.JobStatus.COMPLETED -> "Completed"
                    com.delifar.app.data.JobStatus.CANCELED -> "Canceled"
                    else -> "Updated"
                }
                notifyMessage("$label: ${it.snapshotName}")
            }
        }
    }

    fun quickStartJob(jobId: String) = updateJobStatus(jobId, com.delifar.app.data.JobStatus.PROGRESS)

    fun quickCompleteJob(jobId: String) = updateJobStatus(jobId, com.delifar.app.data.JobStatus.COMPLETED)

    fun rescheduleJob(jobId: String, newDate: String, newTime: String?) {
        viewModelScope.launch {
            val p = preferences.value
            val res = repository.rescheduleJob(
                jobId, newDate, newTime, p.activeUserUid, p.activeUserName, p.activeUserRole, p.activeCompanyId
            )
            res.onFailure { notifyMessage(it.message ?: "Could not reschedule job.") }
            res.onSuccess {
                CloudSyncManager.scheduleBackgroundSync(appContext)
                rebuildReminders()
            }
        }
    }

    fun setJobReminderEnabled(jobId: String, enabled: Boolean) {
        viewModelScope.launch {
            val res = repository.setJobReminderEnabled(jobId, enabled, preferences.value.activeCompanyId)
            res.onSuccess { rebuildReminders() }
        }
    }

    fun reportIssue(
        jobId: String,
        issueType: String,
        note: String,
        keepOpen: Boolean,
        actuals: Map<String, String>,
        onDone: () -> Unit = {}
    ) {
        viewModelScope.launch {
            val p = preferences.value
            val res = repository.reportIssue(
                jobId = jobId,
                issueType = issueType,
                note = note,
                keepOpen = keepOpen,
                actualQuantitiesByItemId = actuals,
                actorUid = p.activeUserUid,
                actorName = p.activeUserName,
                actorRole = p.activeUserRole,
                companyId = p.activeCompanyId
            )
            res.onFailure { notifyMessage(it.message ?: "Could not save issue.") }
            res.onSuccess {
                CloudSyncManager.scheduleBackgroundSync(appContext)
                rebuildReminders()
                onDone()
            }
        }
    }

    // --- Invoices & Electronic Signatures ---

    suspend fun saveInvoice(
        jobId: String?,
        storeId: String,
        storeName: String,
        invoiceNumber: String,
        notes: String,
        pages: List<InvoicePageEntity>,
        supersedesInvoiceId: String? = null
    ): Result<InvoiceWithPages> {
        val p = preferences.value
        val res = repository.createInvoice(
            companyId = p.activeCompanyId,
            jobId = jobId,
            storeId = storeId,
            storeName = storeName,
            invoiceNumber = invoiceNumber,
            notes = notes,
            pages = pages,
            actorUid = p.activeUserUid,
            actorName = p.activeUserName.ifBlank { "DeliFar User" },
            actorRole = p.activeUserRole,
            supersedesInvoiceId = supersedesInvoiceId
        )
        res.onSuccess {
            CloudSyncManager.scheduleBackgroundSync(appContext)
            notifyMessage("Invoice #${it.invoice.invoiceNumber} saved.")
        }
        return res
    }

    suspend fun completeElectronicSignature(
        invoiceWithPages: InvoiceWithPages,
        strokes: List<List<SignatureStrokePoint>>,
        canvasWidth: Float,
        canvasHeight: Float,
        signaturePageIndex: Int,
        signaturePosX: Float,
        signaturePosY: Float,
        recipientName: String,
        recipientTitle: String,
        acknowledgmentConfirmed: Boolean
    ): Result<InvoiceEntity> {
        val p = preferences.value
        val zone = BusinessRules.resolveZone(p.companyTimezone)
        val signedAt = System.currentTimeMillis()
        val deliveryRef = invoiceWithPages.invoice.jobId?.take(12) ?: "Standalone"

        val pdfRes = SignedPdfGenerator.generateSignedPdf(
            context = appContext,
            invoiceWithPages = invoiceWithPages,
            strokes = strokes,
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight,
            signaturePageIndex = signaturePageIndex,
            signaturePosX = signaturePosX,
            signaturePosY = signaturePosY,
            recipientName = recipientName.trim(),
            recipientTitle = recipientTitle.trim(),
            employeeName = p.activeUserName.ifBlank { "DeliFar Driver" },
            deliveryReference = deliveryRef,
            signedLocalAt = signedAt,
            zoneId = zone
        )
        val generated = pdfRes.getOrElse { return Result.failure(it) }

        val saveRes = repository.signInvoice(
            invoiceId = invoiceWithPages.invoice.id,
            recipientName = recipientName,
            recipientTitle = recipientTitle,
            acknowledgmentConfirmed = acknowledgmentConfirmed,
            signatureLocalPath = generated.signaturePngPath,
            signaturePageIndex = signaturePageIndex,
            signaturePosX = signaturePosX,
            signaturePosY = signaturePosY,
            signedPdfLocalPath = generated.signedPdfPath,
            signedPdfSha256 = generated.signedPdfSha256,
            signedPdfBytes = generated.totalBytes,
            actorUid = p.activeUserUid,
            actorName = p.activeUserName.ifBlank { "DeliFar Driver" },
            actorRole = p.activeUserRole,
            companyId = p.activeCompanyId
        )
        saveRes.onSuccess {
            CloudSyncManager.scheduleBackgroundSync(appContext)
            notifyMessage("Invoice #${it.invoiceNumber} signed & PDF generated.")
        }
        return saveRes
    }

    fun voidInvoice(invoiceId: String, reason: String) {
        viewModelScope.launch {
            val p = preferences.value
            val res = repository.voidInvoice(
                invoiceId, reason, p.activeUserUid, p.activeUserName, p.activeUserRole, p.activeCompanyId
            )
            res.onFailure { notifyMessage(it.message ?: "Cannot void invoice.") }
            res.onSuccess {
                CloudSyncManager.scheduleBackgroundSync(appContext)
                notifyMessage("Invoice #${it.invoiceNumber} marked voided.")
            }
        }
    }

    fun retryInvoiceUpload(invoiceId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val activeCompanyId = preferences.value.activeCompanyId
            val res = repository.retryInvoiceUpload(invoiceId, activeCompanyId)
            res.onFailure { err ->
                notifyMessage(err.message ?: "Invoice files could not be verified for upload.")
            }
            res.onSuccess {
                if (FirebaseServices.isConfigured && FirebaseServices.isAuthenticated) {
                    CloudSyncManager.scheduleBackgroundSync(appContext)
                    when (syncManager.flushPendingQueueDetailed(activeCompanyId)) {
                        SyncFlushOutcome.SUCCEEDED -> notifyMessage("Invoice uploaded and synchronized.")
                        SyncFlushOutcome.OFFLINE -> notifyMessage("Invoice queued — will upload automatically when online.")
                        else -> notifyMessage(syncManager.syncState.value.lastError ?: "Invoice upload retry failed.")
                    }
                } else if (!FirebaseServices.isConfigured) {
                    notifyMessage("Invoice saved locally. Firebase is not configured in this build.")
                } else {
                    notifyMessage("Invoice queued locally. Sign in under Account & Company to upload.")
                }
            }
        }
    }

    suspend fun createCorrectedInvoice(originalInvoiceId: String, correctionNote: String): Result<InvoiceWithPages> {
        val p = preferences.value
        val res = repository.createCorrectedInvoiceVersion(
            originalInvoiceId = originalInvoiceId,
            correctionNote = correctionNote,
            actorUid = p.activeUserUid,
            actorName = p.activeUserName,
            actorRole = p.activeUserRole,
            companyId = p.activeCompanyId
        )
        res.onSuccess {
            CloudSyncManager.scheduleBackgroundSync(appContext)
            notifyMessage("Created v${it.invoice.version} for invoice #${it.invoice.invoiceNumber}.")
        }
        return res
    }

    fun exportInvoicesCsvToUri(uri: Uri, list: List<InvoiceWithPages>) {
        viewModelScope.launch {
            val zone = BusinessRules.resolveZone(preferences.value.companyTimezone)
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    val csv = buildString {
                        appendLine("InvoiceNumber,Version,Store,Status,Signed,Recipient,RecipientTitle,Driver,LocalSignedAt,ServerSignedAt,Notes")
                        list.forEach { iwp ->
                            val inv = iwp.invoice
                            val localTs = inv.signedLocalAt?.let { BusinessRules.formatTimestamp(it, zone) } ?: ""
                            val serverTs = inv.signedServerAt?.let { BusinessRules.formatTimestamp(it, zone) } ?: ""
                            appendLine(
                                listOf(
                                    inv.invoiceNumber,
                                    inv.version.toString(),
                                    inv.storeName,
                                    inv.status,
                                    inv.signed.toString(),
                                    inv.recipientName,
                                    inv.recipientTitle,
                                    inv.createdByName,
                                    localTs,
                                    serverTs,
                                    inv.notes
                                ).joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" }
                            )
                        }
                    }
                    appContext.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(csv.toByteArray(Charsets.UTF_8))
                    } ?: throw IllegalStateException("Cannot write CSV file.")
                }
            }
            res.fold(
                onSuccess = { notifyMessage("Exported ${list.size} invoices to CSV.") },
                onFailure = { notifyMessage("CSV export failed: ${it.message}") }
            )
        }
    }

    // --- Preferences & JSON Backup ---

    fun updatePreference(key: String, value: String) {
        viewModelScope.launch {
            repository.setPreference(key, value)
            if (key in setOf(
                    PrefKeys.DAILY, PrefKeys.SUMMARY_MINUTE, PrefKeys.JOB_REMINDERS,
                    PrefKeys.ADVANCE, PrefKeys.COMPANY_TIMEZONE, PrefKeys.NOTIF_UPCOMING
                )
            ) {
                rebuildReminders()
            }
        }
    }

    fun exportBackupToUri(uri: Uri) {
        viewModelScope.launch {
            val activeCompanyId = preferences.value.activeCompanyId
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    val json = backupManager.exportJson(activeCompanyId)
                    appContext.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(json.toByteArray(Charsets.UTF_8))
                    } ?: throw IllegalStateException("Cannot open destination file.")
                }
            }
            res.fold(
                onSuccess = { notifyMessage("Backup exported successfully.") },
                onFailure = { notifyMessage("Export failed: ${it.message}") }
            )
        }
    }

    fun exportEncryptedArchiveToUri(uri: Uri, passphrase: String) {
        viewModelScope.launch {
            val activeCompanyId = preferences.value.activeCompanyId
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    val tmp = File(appContext.cacheDir, "delifar_export_${System.currentTimeMillis()}.delifarbak")
                    try {
                        val summary = encryptedBackupManager.exportEncryptedArchive(activeCompanyId, passphrase, tmp).getOrThrow()
                        appContext.contentResolver.openOutputStream(uri)?.use { out ->
                            tmp.inputStream().use { input -> input.copyTo(out) }
                        } ?: throw IllegalStateException("Cannot open destination file.")
                        summary
                    } finally {
                        runCatching { tmp.delete() }
                    }
                }
            }
            res.fold(
                onSuccess = { s ->
                    notifyMessage(
                        "Encrypted backup saved (${s.storeCount} stores, ${s.jobCount} jobs, ${s.invoiceCount} invoices, ${s.attachmentCount} files)."
                    )
                },
                onFailure = { notifyMessage("Encrypted backup failed: ${it.message}") }
            )
        }
    }

    fun inspectImportUri(uri: Uri) {
        viewModelScope.launch {
            val activeCompanyId = preferences.value.activeCompanyId
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = appContext.contentResolver.openInputStream(uri)?.use { input ->
                        val data = input.readBytes()
                        if (data.size > EncryptedArchiveBackupManager.MAX_ARCHIVE_BYTES) {
                            throw IllegalArgumentException("Backup file exceeds maximum size limit.")
                        }
                        data
                    } ?: throw IllegalStateException("Cannot read selected file.")

                    if (EncryptedArchiveBackupManager.isEncryptedArchiveBytes(bytes)) {
                        val tmp = File(appContext.cacheDir, "delifar_import_${System.currentTimeMillis()}.delifarbak")
                        tmp.writeBytes(bytes)
                        Pair<File?, ImportPreview?>(tmp, null)
                    } else {
                        if (bytes.size > BackupManager.MAX_BYTES) {
                            throw IllegalArgumentException("Legacy JSON backup exceeds 50 MiB limit.")
                        }
                        val parsed = backupManager.parseAndValidate(String(bytes, Charsets.UTF_8)).getOrThrow()
                        Pair<File?, ImportPreview?>(null, backupManager.buildPreview(parsed, activeCompanyId))
                    }
                }
            }
            res.fold(
                onSuccess = { (encFile, jsonPreview) ->
                    if (encFile != null) {
                        _pendingEncryptedArchiveFile.value?.let { runCatching { it.delete() } }
                        _pendingEncryptedArchiveFile.value = encFile
                    } else if (jsonPreview != null) {
                        _importPreview.value = jsonPreview
                    }
                },
                onFailure = { err -> notifyMessage("Invalid backup: ${err.message}") }
            )
        }
    }

    fun unlockEncryptedArchive(passphrase: String) {
        val file = _pendingEncryptedArchiveFile.value ?: return
        viewModelScope.launch {
            val activeCompanyId = preferences.value.activeCompanyId
            val res = withContext(Dispatchers.IO) {
                encryptedBackupManager.inspectEncryptedArchive(file, passphrase, activeCompanyId)
            }
            res.fold(
                onSuccess = { preview ->
                    pendingArchivePassphrase = passphrase
                    _archiveImportPreview.value = preview
                },
                onFailure = { err ->
                    notifyMessage("Cannot open encrypted backup: ${err.message}")
                }
            )
        }
    }

    fun dismissEncryptedImport() {
        _pendingEncryptedArchiveFile.value?.let { runCatching { it.delete() } }
        _pendingEncryptedArchiveFile.value = null
        _archiveImportPreview.value = null
        pendingArchivePassphrase = ""
    }

    fun applyEncryptedArchiveRestore(
        replaceConflicts: Boolean,
        restorePreferences: Boolean,
        allowCrossCompany: Boolean
    ) {
        val file = _pendingEncryptedArchiveFile.value ?: return
        val pass = pendingArchivePassphrase
        viewModelScope.launch {
            val activeCompanyId = preferences.value.activeCompanyId
            val res = withContext(Dispatchers.IO) {
                encryptedBackupManager.restoreEncryptedArchive(
                    archiveFile = file,
                    passphrase = pass,
                    targetFilesDir = appContext.filesDir,
                    replaceConflicts = replaceConflicts,
                    restorePreferences = restorePreferences,
                    activeCompanyId = activeCompanyId,
                    allowCrossCompanyRestore = allowCrossCompany
                )
            }
            dismissEncryptedImport()
            res.fold(
                onSuccess = { r ->
                    rebuildReminders()
                    CloudSyncManager.scheduleBackgroundSync(appContext)
                    notifyMessage(
                        "Restored ${r.storesWritten} stores, ${r.jobsWritten} jobs, ${r.invoicesWritten} invoices, and ${r.attachmentsRestored} files."
                    )
                },
                onFailure = { err -> notifyMessage("Restore failed: ${err.message}") }
            )
        }
    }

    fun dismissImportPreview() {
        _importPreview.value = null
    }

    fun applyImportMerge(replaceConflicts: Boolean, restorePreferences: Boolean) {
        val preview = _importPreview.value ?: return
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) {
                backupManager.applyMerge(
                    backup = preview.backup,
                    replaceConflicts = replaceConflicts,
                    restorePreferences = restorePreferences,
                    activeCompanyId = preferences.value.activeCompanyId
                )
            }
            _importPreview.value = null
            res.fold(
                onSuccess = {
                    rebuildReminders()
                    CloudSyncManager.scheduleBackgroundSync(appContext)
                    notifyMessage("Backup merged successfully.")
                },
                onFailure = { err -> notifyMessage("Import rejected: ${err.message}") }
            )
        }
    }
}
