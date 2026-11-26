package com.delifar.app.ui

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.delifar.app.R
import com.delifar.app.cloud.CloudSyncManager
import com.delifar.app.cloud.CloudSyncStatus
import com.delifar.app.cloud.CloudSyncUiState
import com.delifar.app.cloud.FirebaseServices
import com.delifar.app.cloud.InvoiceSyncValidator
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
import com.delifar.app.data.SyncQueueEntity
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
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.UserProfileChangeRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
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
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Locale

data class PlaceSearchUiState(
    val loading: Boolean = false,
    val results: List<GeocodeCandidate> = emptyList(),
    val error: String? = null,
    val searched: Boolean = false
)

data class RoutePlanUiState(
    val loading: Boolean = false,
    val route: ComputedRouteResult? = null,
    val error: String? = null,
    val optimizedStopStoreIds: List<String> = emptyList()
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

    val failedSyncItems: StateFlow<List<SyncQueueEntity>> = activeCompanyIdFlow
        .flatMapLatest { companyId -> repository.observeFailedSyncItems(companyId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    val invoiceAttachmentIssues: StateFlow<Map<String, List<String>>> = invoices
        .map { list ->
            list.associate { iwp ->
                iwp.invoice.id to InvoiceSyncValidator.inspectLocalAttachmentIssues(iwp)
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val vmExceptionHandler = CoroutineExceptionHandler { _, err ->
        Log.e("DeliFarViewModel", "Unhandled coroutine exception", err)
        notifyMessage(err.message ?: lStr(R.string.vm_unexpected_error))
    }

    private fun safeLaunch(
        dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
        block: suspend CoroutineScope.() -> Unit
    ) {
        viewModelScope.launch(dispatcher + vmExceptionHandler, block = block)
    }

    private fun effectiveActorUid(prefs: AppPreferences): String =
        FirebaseServices.currentAuthUid ?: prefs.activeUserUid

    private var authStateListener: FirebaseAuth.AuthStateListener? = null

    init {
        rebuildReminders()
        FirebaseServices.authOrNull()?.let { auth ->
            val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
                safeLaunch(Dispatchers.IO) {
                    val prefs = repository.getPreferences()
                    syncManager.refreshAuthAndConfigState(
                        persistedLastSyncedAt = prefs.lastSyncedAt,
                        persistedLastError = prefs.lastSyncError
                    )
                    val uid = firebaseAuth.currentUser?.uid
                    if (!uid.isNullOrBlank() && prefs.activeCompanyId.isNotBlank()) {
                        syncManager.startRealtimeListeners(
                            companyId = prefs.activeCompanyId,
                            userUid = uid,
                            role = prefs.activeUserRole
                        )
                    } else if (uid.isNullOrBlank()) {
                        syncManager.stopListeners()
                    }
                }
            }
            auth.addAuthStateListener(listener)
            authStateListener = listener
        }
        safeLaunch {
            preferences
                .distinctUntilChangedBy { Triple(it.activeCompanyId, it.activeUserUid, it.activeUserRole) }
                .collect { currentPrefs ->
                    val companyId = currentPrefs.activeCompanyId
                    val userUid = currentPrefs.activeUserUid
                    val role = currentPrefs.activeUserRole
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
        safeLaunch(Dispatchers.IO) {
            val prefs = repository.getPreferences()
            refreshCompanyListsInternal(FirebaseServices.currentAuthUid ?: prefs.activeUserUid)
        }
    }

    override fun onCleared() {
        authStateListener?.let { listener ->
            runCatching { FirebaseServices.authOrNull()?.removeAuthStateListener(listener) }
        }
        authStateListener = null
        _pendingEncryptedArchiveFile.value?.let { runCatching { it.delete() } }
        syncManager.shutdown()
        super.onCleared()
    }

    fun observeStore(storeId: String): Flow<StoreWithHours?> =
        activeCompanyIdFlow.flatMapLatest { companyId ->
            repository.observeStore(storeId, companyId)
        }

    fun observeJob(jobId: String): Flow<JobWithItems?> =
        activeCompanyIdFlow.flatMapLatest { companyId ->
            repository.observeJobWithItems(jobId, companyId)
        }

    fun observeInvoicesForJob(jobId: String): Flow<List<InvoiceWithPages>> =
        activeCompanyIdFlow.flatMapLatest { companyId ->
            repository.observeInvoicesForJob(jobId, companyId)
        }

    fun observeInvoice(invoiceId: String): Flow<InvoiceWithPages?> =
        activeCompanyIdFlow.flatMapLatest { companyId ->
            repository.observeInvoice(invoiceId, companyId)
        }

    private fun localizedContext(languageCode: String = preferences.value.language): Context {
        if (languageCode == "system" || languageCode.isBlank()) return appContext
        val cfg = Configuration(appContext.resources.configuration)
        cfg.setLocale(Locale(languageCode))
        return appContext.createConfigurationContext(cfg)
    }

    private fun lStr(@StringRes resId: Int, vararg args: Any): String {
        val ctx = localizedContext()
        return if (args.isEmpty()) ctx.getString(resId) else ctx.getString(resId, *args)
    }

    private fun notifyLocalized(@StringRes resId: Int, vararg args: Any) {
        notifyMessage(lStr(resId, *args))
    }

    fun notifyMessage(msg: String) {
        _messages.tryEmit(msg)
    }

    fun rebuildReminders() {
        safeLaunch(Dispatchers.IO) {
            runCatching { ReminderScheduler.rebuildAndDispatchIfDue(appContext) }
        }
    }

    fun triggerSyncNow() {
        safeLaunch(Dispatchers.IO) {
            val freshPrefs = repository.getPreferences()
            val activeCompanyId = freshPrefs.activeCompanyId
            if (!FirebaseServices.isConfigured) {
                syncManager.refreshAuthAndConfigState(freshPrefs.lastSyncedAt, freshPrefs.lastSyncError)
                notifyLocalized(R.string.vm_sync_offline_saved)
                return@safeLaunch
            }
            if (!FirebaseServices.isAuthenticated) {
                syncManager.refreshAuthAndConfigState(freshPrefs.lastSyncedAt, freshPrefs.lastSyncError)
                notifyLocalized(R.string.vm_sync_not_signed_in)
                return@safeLaunch
            }
            // Flush directly under the process-wide queue mutex (resetting failed items atomically inside the lock).
            // Only enqueue background WorkManager retry if this foreground flush is offline or transiently failed,
            // preventing a competing SyncWorker from processing the same queue items concurrently.
            val outcome = syncManager.flushPendingQueueDetailed(
                companyIdOverride = activeCompanyId,
                retryFailedFirst = true
            )
            when (outcome) {
                SyncFlushOutcome.SUCCEEDED ->
                    notifyLocalized(R.string.vm_sync_succeeded)
                SyncFlushOutcome.OFFLINE -> {
                    CloudSyncManager.scheduleBackgroundSync(appContext)
                    notifyLocalized(R.string.vm_sync_offline_queued)
                }
                SyncFlushOutcome.ACCESS_DENIED_OR_UNCONFIGURED ->
                    notifyMessage(syncManager.syncState.value.lastError ?: lStr(R.string.vm_sync_access_denied))
                SyncFlushOutcome.NOT_SIGNED_IN ->
                    notifyLocalized(R.string.vm_sync_auth_required)
                SyncFlushOutcome.NOT_CONFIGURED ->
                    notifyLocalized(R.string.vm_sync_not_configured)
                SyncFlushOutcome.FAILED_TRANSIENT -> {
                    CloudSyncManager.scheduleBackgroundSync(appContext)
                    notifyMessage(syncManager.syncState.value.lastError ?: lStr(R.string.vm_sync_failed_transient))
                }
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
            notifyLocalized(R.string.vm_company_claimed, claimed.name)
        }
        return res
    }

    suspend fun switchCompany(companyId: String): Result<CompanyEntity> {
        val uid = FirebaseServices.currentAuthUid ?: preferences.value.activeUserUid
        val res = repository.switchActiveCompany(companyId, uid)
        res.onSuccess { switched ->
            refreshCompanyListsInternal(uid)
            rebuildReminders()
            notifyLocalized(R.string.vm_company_switched, switched.name)
        }
        res.onFailure { notifyMessage(it.message ?: lStr(R.string.vm_company_switch_failed)) }
        return res
    }

    fun selectOfflineRole(role: String) {
        if (role !in EmployeeRole.ALL) return
        safeLaunch {
            repository.setPreference(PrefKeys.ACTIVE_USER_ROLE, role)
        }
    }

    fun dismissOnboarding() {
        safeLaunch {
            repository.setPreference(PrefKeys.ONBOARDING_DISMISSED, "true")
        }
    }

    fun signOut() {
        safeLaunch {
            val lastSyncedAt = repository.getPreferences().lastSyncedAt
            syncManager.stopListeners()
            runCatching { FirebaseServices.authOrNull()?.signOut() }
            repository.setPreference(PrefKeys.ACTIVE_COMPANY_ID, "")
            repository.setPreference(PrefKeys.ACTIVE_USER_UID, "")
            repository.setPreference(PrefKeys.ACTIVE_USER_EMAIL, "")
            repository.setPreference(PrefKeys.ACTIVE_USER_NAME, "")
            repository.setPreference(PrefKeys.ACTIVE_USER_ROLE, EmployeeRole.OWNER)
            repository.setPreference(PrefKeys.LAST_SYNC_ERROR, "")
            syncManager.refreshAuthAndConfigState(lastSyncedAt, null)
            refreshCompanyListsInternal("")
            clearSearchState()
            clearRoutePlan()
            rebuildReminders()
            notifyLocalized(R.string.vm_signed_out)
        }
    }

    suspend fun registerCompany(
        companyName: String,
        ownerName: String,
        ownerEmail: String,
        timezone: String,
        defaultArea: String
    ): Result<CompanyEntity> {
        val prefs = repository.getPreferences()
        val uid = effectiveActorUid(prefs)
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
            notifyLocalized(R.string.vm_company_registered, it.name)
        }
        return res
    }

    suspend fun updateCompanySettings(name: String, timezone: String, defaultArea: String): Result<CompanyEntity> {
        val prefs = repository.getPreferences()
        val res = repository.updateCompanySettings(
            companyId = prefs.activeCompanyId,
            name = name,
            timezone = timezone,
            defaultArea = defaultArea,
            actorUid = effectiveActorUid(prefs),
            actorName = prefs.activeUserName,
            actorRole = prefs.activeUserRole
        )
        res.onSuccess {
            CloudSyncManager.scheduleBackgroundSync(appContext)
            rebuildReminders()
            notifyLocalized(R.string.vm_company_settings_saved)
        }
        return res
    }

    suspend fun inviteEmployee(email: String, role: String): Result<InvitationEntity> {
        val prefs = repository.getPreferences()
        val company = activeCompany.value
            ?: prefs.activeCompanyId.takeIf { it.isNotBlank() }?.let { repository.getCompany(it) }
            ?: return Result.failure(IllegalStateException("Register a company first."))
        val res = repository.createInvitation(
            companyId = company.id,
            companyName = company.name,
            email = email,
            role = role,
            actorUid = effectiveActorUid(prefs),
            actorName = prefs.activeUserName,
            actorRole = prefs.activeUserRole
        )
        res.onSuccess {
            CloudSyncManager.scheduleBackgroundSync(appContext)
            notifyLocalized(R.string.vm_invite_created, it.inviteCode)
        }
        return res
    }

    fun revokeInvitation(inviteId: String) {
        safeLaunch {
            val p = repository.getPreferences()
            val res = repository.revokeInvitation(inviteId, effectiveActorUid(p), p.activeUserName, p.activeUserRole)
            res.onFailure { notifyMessage(it.message ?: lStr(R.string.vm_invite_revoke_failed)) }
            res.onSuccess {
                CloudSyncManager.scheduleBackgroundSync(appContext)
                notifyLocalized(R.string.vm_invite_revoked)
            }
        }
    }

    suspend fun joinCompanyWithInvite(code: String, displayName: String, email: String): Result<String> {
        val p = repository.getPreferences()
        val uid = effectiveActorUid(p)
        val res = syncManager.acceptInvitationCloudOrLocal(
            inviteCode = code,
            userUid = uid,
            displayName = displayName.ifBlank { p.activeUserName },
            email = email.ifBlank { p.activeUserEmail }
        )
        res.onSuccess {
            refreshCompanyListsInternal(uid)
            CloudSyncManager.scheduleBackgroundSync(appContext)
            notifyLocalized(R.string.vm_joined_company)
        }
        return res
    }

    fun updateEmployeeRole(targetUid: String, newRole: String) {
        safeLaunch {
            val p = repository.getPreferences()
            val res = repository.updateEmployeeRole(
                targetUid, newRole, effectiveActorUid(p), p.activeUserName, p.activeUserRole, p.activeCompanyId
            )
            res.onFailure { notifyMessage(it.message ?: lStr(R.string.vm_role_update_failed)) }
            res.onSuccess {
                CloudSyncManager.scheduleBackgroundSync(appContext)
                notifyLocalized(R.string.vm_role_updated)
            }
        }
    }

    fun setEmployeeActive(targetUid: String, active: Boolean) {
        safeLaunch {
            val p = repository.getPreferences()
            val res = repository.setEmployeeActive(
                targetUid, active, effectiveActorUid(p), p.activeUserName, p.activeUserRole, p.activeCompanyId
            )
            res.onFailure { notifyMessage(it.message ?: lStr(R.string.vm_employee_status_failed)) }
            res.onSuccess {
                CloudSyncManager.scheduleBackgroundSync(appContext)
                notifyLocalized(if (active) R.string.vm_employee_activated else R.string.vm_employee_deactivated)
            }
        }
    }

    fun checkUnmigratedLocalData() {
        safeLaunch {
            val p = repository.getPreferences()
            val summary = repository.getUnmigratedLocalSummary()
            if (summary.totalCount > 0 && p.activeCompanyId.isNotBlank() &&
                p.activeUserRole == EmployeeRole.OWNER
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
        safeLaunch {
            val p = repository.getPreferences()
            val res = repository.migrateLocalDataToCompany(
                companyId = p.activeCompanyId,
                actorUid = effectiveActorUid(p),
                actorName = p.activeUserName,
                actorRole = p.activeUserRole
            )
            _pendingMigration.value = null
            res.fold(
                onSuccess = {
                    CloudSyncManager.scheduleBackgroundSync(appContext)
                    notifyLocalized(R.string.vm_migrated_summary, it.storesCount, it.jobsCount, it.invoicesCount)
                },
                onFailure = { notifyMessage(it.message ?: lStr(R.string.vm_migration_failed)) }
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
        safeLaunch {
            _searchState.value = PlaceSearchUiState(loading = true)
            val p = repository.getPreferences()
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
        optimizeRouteForStores(stops.mapIndexed { idx, pt -> idx.toString() to pt })
    }

    fun optimizeRouteForStores(storeStops: List<Pair<String, GeoPoint>>) {
        val validPairs = storeStops.filter { it.second.isValid() }
        if (validPairs.size < 2) {
            notifyLocalized(R.string.vm_route_need_two_stops)
            return
        }
        safeLaunch {
            _routeState.value = RoutePlanUiState(loading = true)
            val result = routesClient.computeRoute(
                stops = validPairs.map { it.second },
                routingBaseUrl = preferences.value.routingUrl,
                optimizeIntermediates = true
            )
            result.fold(
                onSuccess = { computed ->
                    val orderedIds = computed.optimizedStopIndices.mapNotNull { idx ->
                        validPairs.getOrNull(idx)?.first
                    }
                    _routeState.value = RoutePlanUiState(
                        loading = false,
                        route = computed,
                        optimizedStopStoreIds = orderedIds
                    )
                    val miles = computed.distanceMeters / 1609.34
                    val mins = (computed.durationSeconds / 60).toInt()
                    notifyLocalized(
                        if (computed.isStreetRoute) R.string.map_street_route_summary else R.string.map_offline_route_summary,
                        miles,
                        mins
                    )
                },
                onFailure = { err ->
                    _routeState.value = RoutePlanUiState(loading = false, error = err.message)
                    notifyMessage(err.message ?: lStr(R.string.vm_route_failed))
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
        notifyLocalized(R.string.vm_map_settings_saved)
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
        val p = repository.getPreferences()
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
            actorUid = effectiveActorUid(p),
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
        safeLaunch {
            val p = repository.getPreferences()
            val res = repository.archiveStore(storeId, effectiveActorUid(p), p.activeUserName, p.activeUserRole, p.activeCompanyId)
            if (res.isFailure) {
                notifyMessage(if (res.exceptionOrNull()?.message == "ACTIVE_JOBS") blockedMsg else res.exceptionOrNull()?.message ?: blockedMsg)
            } else {
                CloudSyncManager.scheduleBackgroundSync(appContext)
            }
        }
    }

    fun restoreStore(storeId: String) {
        safeLaunch {
            val p = repository.getPreferences()
            repository.restoreStore(storeId, effectiveActorUid(p), p.activeUserName, p.activeUserRole, p.activeCompanyId)
            CloudSyncManager.scheduleBackgroundSync(appContext)
        }
    }

    // --- Drafts & Jobs ---

    fun saveDraftAsync(draft: DraftEntity) {
        safeLaunch(Dispatchers.IO) { repository.saveDraft(draft) }
    }

    fun discardDraft(jobId: String) {
        safeLaunch(Dispatchers.IO) { repository.clearDraft(jobId) }
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
        val p = repository.getPreferences()
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
            actorUid = effectiveActorUid(p),
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
        safeLaunch {
            val p = repository.getPreferences()
            val res = repository.assignDriverToJob(
                jobId, driverUid, driverName, effectiveActorUid(p), p.activeUserName, p.activeUserRole, p.activeCompanyId
            )
            res.onFailure { notifyMessage(it.message ?: lStr(R.string.vm_driver_assign_failed)) }
            res.onSuccess {
                CloudSyncManager.scheduleBackgroundSync(appContext)
                if (driverUid.isNullOrBlank()) notifyLocalized(R.string.vm_driver_unassigned)
                else notifyLocalized(R.string.vm_driver_assigned, driverName)
            }
        }
    }

    fun updateJobStatus(jobId: String, newStatus: String) {
        safeLaunch {
            val p = repository.getPreferences()
            val res = repository.updateJobStatus(
                jobId, newStatus, effectiveActorUid(p), p.activeUserName, p.activeUserRole, p.activeCompanyId
            )
            res.onFailure { notifyMessage(it.message ?: lStr(R.string.vm_job_update_failed)) }
            res.onSuccess {
                CloudSyncManager.scheduleBackgroundSync(appContext)
                rebuildReminders()
                val msgRes = when (newStatus) {
                    com.delifar.app.data.JobStatus.PROGRESS -> R.string.vm_job_started
                    com.delifar.app.data.JobStatus.COMPLETED -> R.string.vm_job_completed
                    com.delifar.app.data.JobStatus.CANCELED -> R.string.vm_job_canceled
                    else -> R.string.vm_job_updated
                }
                notifyLocalized(msgRes, it.snapshotName)
            }
        }
    }

    fun quickStartJob(jobId: String) = updateJobStatus(jobId, com.delifar.app.data.JobStatus.PROGRESS)

    fun quickCompleteJob(jobId: String) = updateJobStatus(jobId, com.delifar.app.data.JobStatus.COMPLETED)

    fun rescheduleJob(jobId: String, newDate: String, newTime: String?) {
        safeLaunch {
            val p = repository.getPreferences()
            val res = repository.rescheduleJob(
                jobId, newDate, newTime, effectiveActorUid(p), p.activeUserName, p.activeUserRole, p.activeCompanyId
            )
            res.onFailure { notifyMessage(it.message ?: lStr(R.string.vm_job_reschedule_failed)) }
            res.onSuccess {
                CloudSyncManager.scheduleBackgroundSync(appContext)
                rebuildReminders()
            }
        }
    }

    fun setJobReminderEnabled(jobId: String, enabled: Boolean) {
        safeLaunch {
            val p = repository.getPreferences()
            val res = repository.setJobReminderEnabled(jobId, enabled, p.activeCompanyId)
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
        safeLaunch {
            val p = repository.getPreferences()
            val res = repository.reportIssue(
                jobId = jobId,
                issueType = issueType,
                note = note,
                keepOpen = keepOpen,
                actualQuantitiesByItemId = actuals,
                actorUid = effectiveActorUid(p),
                actorName = p.activeUserName,
                actorRole = p.activeUserRole,
                companyId = p.activeCompanyId
            )
            res.onFailure { notifyMessage(it.message ?: lStr(R.string.vm_issue_save_failed)) }
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
        val p = repository.getPreferences()
        val res = repository.createInvoice(
            companyId = p.activeCompanyId,
            jobId = jobId,
            storeId = storeId,
            storeName = storeName,
            invoiceNumber = invoiceNumber,
            notes = notes,
            pages = pages,
            actorUid = effectiveActorUid(p),
            actorName = p.activeUserName.ifBlank { "DeliFar User" },
            actorRole = p.activeUserRole,
            supersedesInvoiceId = supersedesInvoiceId
        )
        res.onSuccess {
            CloudSyncManager.scheduleBackgroundSync(appContext)
            notifyLocalized(R.string.vm_invoice_saved, it.invoice.invoiceNumber)
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
        val p = repository.getPreferences()
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
            actorUid = effectiveActorUid(p),
            actorName = p.activeUserName.ifBlank { "DeliFar Driver" },
            actorRole = p.activeUserRole,
            companyId = p.activeCompanyId
        )
        saveRes.onSuccess {
            CloudSyncManager.scheduleBackgroundSync(appContext)
            notifyLocalized(R.string.vm_invoice_signed_pdf, it.invoiceNumber)
        }
        return saveRes
    }

    fun voidInvoice(invoiceId: String, reason: String) {
        safeLaunch {
            val p = repository.getPreferences()
            val res = repository.voidInvoice(
                invoiceId, reason, effectiveActorUid(p), p.activeUserName, p.activeUserRole, p.activeCompanyId
            )
            res.onFailure { notifyMessage(it.message ?: lStr(R.string.vm_invoice_void_failed)) }
            res.onSuccess {
                CloudSyncManager.scheduleBackgroundSync(appContext)
                notifyLocalized(R.string.vm_invoice_voided, it.invoiceNumber)
            }
        }
    }

    fun retryInvoiceUpload(invoiceId: String) {
        safeLaunch(Dispatchers.IO) {
            val freshPrefs = repository.getPreferences()
            val activeCompanyId = freshPrefs.activeCompanyId
            val res = repository.retryInvoiceUpload(invoiceId, activeCompanyId)
            res.onFailure { err ->
                notifyMessage(err.message ?: lStr(R.string.vm_invoice_verify_failed))
            }
            res.onSuccess {
                if (FirebaseServices.isConfigured && FirebaseServices.isAuthenticated) {
                    val outcome = syncManager.flushPendingQueueDetailed(activeCompanyId)
                    when (outcome) {
                        SyncFlushOutcome.SUCCEEDED -> notifyLocalized(R.string.vm_invoice_uploaded)
                        SyncFlushOutcome.OFFLINE -> {
                            CloudSyncManager.scheduleBackgroundSync(appContext)
                            notifyLocalized(R.string.vm_invoice_queued_online)
                        }
                        SyncFlushOutcome.FAILED_TRANSIENT -> {
                            CloudSyncManager.scheduleBackgroundSync(appContext)
                            notifyMessage(syncManager.syncState.value.lastError ?: lStr(R.string.vm_invoice_retry_failed))
                        }
                        else -> notifyMessage(syncManager.syncState.value.lastError ?: lStr(R.string.vm_invoice_retry_failed))
                    }
                } else if (!FirebaseServices.isConfigured) {
                    notifyLocalized(R.string.vm_invoice_saved_offline)
                } else {
                    notifyLocalized(R.string.vm_invoice_queued_auth)
                }
            }
        }
    }

    suspend fun createCorrectedInvoice(originalInvoiceId: String, correctionNote: String): Result<InvoiceWithPages> {
        val p = repository.getPreferences()
        val res = repository.createCorrectedInvoiceVersion(
            originalInvoiceId = originalInvoiceId,
            correctionNote = correctionNote,
            actorUid = effectiveActorUid(p),
            actorName = p.activeUserName,
            actorRole = p.activeUserRole,
            companyId = p.activeCompanyId
        )
        res.onSuccess {
            CloudSyncManager.scheduleBackgroundSync(appContext)
            notifyLocalized(R.string.vm_invoice_corrected_created, it.invoice.version, it.invoice.invoiceNumber)
        }
        return res
    }

    fun exportInvoicesCsvToUri(uri: Uri, list: List<InvoiceWithPages>) {
        safeLaunch {
            val prefs = repository.getPreferences()
            val zone = BusinessRules.resolveZone(prefs.companyTimezone)
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
                onSuccess = { notifyLocalized(R.string.vm_csv_exported, list.size) },
                onFailure = { notifyLocalized(R.string.vm_csv_export_failed, it.message.orEmpty()) }
            )
        }
    }

    // --- Preferences & JSON Backup ---

    fun updatePreference(key: String, value: String) {
        safeLaunch {
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
        safeLaunch {
            val activeCompanyId = repository.getPreferences().activeCompanyId
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    val json = backupManager.exportJson(activeCompanyId)
                    appContext.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(json.toByteArray(Charsets.UTF_8))
                    } ?: throw IllegalStateException("Cannot open destination file.")
                }
            }
            res.fold(
                onSuccess = { notifyLocalized(R.string.vm_backup_exported) },
                onFailure = { notifyLocalized(R.string.vm_export_failed, it.message.orEmpty()) }
            )
        }
    }

    fun exportEncryptedArchiveToUri(uri: Uri, passphrase: String) {
        safeLaunch {
            val activeCompanyId = repository.getPreferences().activeCompanyId
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
                    notifyLocalized(
                        R.string.vm_enc_backup_saved,
                        s.storeCount,
                        s.jobCount,
                        s.invoiceCount,
                        s.attachmentCount
                    )
                },
                onFailure = { notifyLocalized(R.string.vm_enc_backup_failed, it.message.orEmpty()) }
            )
        }
    }

    fun inspectImportUri(uri: Uri) {
        safeLaunch {
            val activeCompanyId = repository.getPreferences().activeCompanyId
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
                onFailure = { err -> notifyLocalized(R.string.vm_invalid_backup, err.message.orEmpty()) }
            )
        }
    }

    fun unlockEncryptedArchive(passphrase: String) {
        val file = _pendingEncryptedArchiveFile.value ?: return
        safeLaunch {
            val activeCompanyId = repository.getPreferences().activeCompanyId
            val res = withContext(Dispatchers.IO) {
                encryptedBackupManager.inspectEncryptedArchive(file, passphrase, activeCompanyId)
            }
            res.fold(
                onSuccess = { preview ->
                    pendingArchivePassphrase = passphrase
                    _archiveImportPreview.value = preview
                },
                onFailure = { err ->
                    notifyLocalized(R.string.vm_enc_unlock_failed, err.message.orEmpty())
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
        safeLaunch {
            val activeCompanyId = repository.getPreferences().activeCompanyId
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
                    notifyLocalized(
                        R.string.vm_restore_summary,
                        r.storesWritten,
                        r.jobsWritten,
                        r.invoicesWritten,
                        r.attachmentsRestored
                    )
                },
                onFailure = { err -> notifyLocalized(R.string.vm_restore_failed, err.message.orEmpty()) }
            )
        }
    }

    fun dismissImportPreview() {
        _importPreview.value = null
    }

    fun applyImportMerge(replaceConflicts: Boolean, restorePreferences: Boolean) {
        val preview = _importPreview.value ?: return
        safeLaunch {
            val activeCompanyId = repository.getPreferences().activeCompanyId
            val res = withContext(Dispatchers.IO) {
                backupManager.applyMerge(
                    backup = preview.backup,
                    replaceConflicts = replaceConflicts,
                    restorePreferences = restorePreferences,
                    activeCompanyId = activeCompanyId
                )
            }
            _importPreview.value = null
            res.fold(
                onSuccess = {
                    rebuildReminders()
                    CloudSyncManager.scheduleBackgroundSync(appContext)
                    notifyLocalized(R.string.vm_backup_merged)
                },
                onFailure = { err -> notifyLocalized(R.string.vm_import_rejected, err.message.orEmpty()) }
            )
        }
    }
}
