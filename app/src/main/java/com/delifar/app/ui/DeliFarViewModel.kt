package com.delifar.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.delifar.app.data.AppPreferences
import com.delifar.app.data.DeliFarDatabase
import com.delifar.app.data.DeliFarRepository
import com.delifar.app.data.DraftEntity
import com.delifar.app.data.DraftItemInput
import com.delifar.app.data.JobEntity
import com.delifar.app.data.JobListRow
import com.delifar.app.data.JobWithItems
import com.delifar.app.data.PrefKeys
import com.delifar.app.data.StoreEntity
import com.delifar.app.data.StoreHoursEntity
import com.delifar.app.data.StoreWithHours
import com.delifar.app.domain.BackupManager
import com.delifar.app.domain.BusinessRules
import com.delifar.app.domain.ImportPreview
import com.delifar.app.network.OsmTileLoader
import com.delifar.app.network.PhotonPlace
import com.delifar.app.network.PhotonSearchClient
import com.delifar.app.reminders.ReminderScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PhotonSearchUiState(
    val loading: Boolean = false,
    val results: List<PhotonPlace> = emptyList(),
    val error: String? = null,
    val searched: Boolean = false
)

class DeliFarViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val db = DeliFarDatabase.getInstance(appContext)
    val repository = DeliFarRepository(db)
    val backupManager = BackupManager(db)
    val photonClient = PhotonSearchClient(repository)
    val tileLoader = OsmTileLoader(appContext)

    val stores: StateFlow<List<StoreWithHours>> = repository.observeStores()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val jobRows: StateFlow<List<JobListRow>> = repository.observeAllJobRows()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val preferences: StateFlow<AppPreferences> = repository.observePreferences()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppPreferences())

    val latestDraft: StateFlow<DraftEntity?> = repository.observeLatestDraft()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _searchState = MutableStateFlow(PhotonSearchUiState())
    val searchState: StateFlow<PhotonSearchUiState> = _searchState.asStateFlow()

    private val _importPreview = MutableStateFlow<ImportPreview?>(null)
    val importPreview: StateFlow<ImportPreview?> = _importPreview.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    init {
        rebuildReminders()
    }

    fun observeStore(storeId: String): Flow<StoreWithHours?> = repository.observeStore(storeId)

    fun observeJob(jobId: String): Flow<JobWithItems?> = repository.observeJobWithItems(jobId)

    fun notifyMessage(msg: String) {
        _messages.tryEmit(msg)
    }

    fun rebuildReminders() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { ReminderScheduler.rebuildAndDispatchIfDue(appContext) }
        }
    }

    fun clearSearchState() {
        _searchState.value = PhotonSearchUiState()
    }

    fun searchPhoton(query: String, area: String) {
        viewModelScope.launch {
            _searchState.value = PhotonSearchUiState(loading = true)
            val result = photonClient.search(query, area, preferences.value.searchUrl)
            result.fold(
                onSuccess = { list ->
                    _searchState.value = PhotonSearchUiState(
                        loading = false,
                        results = list,
                        searched = true
                    )
                },
                onFailure = { err ->
                    _searchState.value = PhotonSearchUiState(
                        loading = false,
                        error = err.message ?: "Search failed",
                        searched = true
                    )
                }
            )
        }
    }

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
        val res = repository.saveStore(existingId, name, address, lat, lon, providerId, phone, hours)
        if (res.isSuccess) rebuildReminders()
        return res
    }

    fun archiveStore(storeId: String, blockedMsg: String) {
        viewModelScope.launch {
            val res = repository.archiveStore(storeId)
            if (res.isFailure) {
                notifyMessage(blockedMsg)
            }
        }
    }

    fun restoreStore(storeId: String) {
        viewModelScope.launch {
            repository.restoreStore(storeId)
        }
    }

    fun saveDraftAsync(draft: DraftEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.saveDraft(draft)
        }
    }

    fun discardDraft(jobId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.clearDraft(jobId)
        }
    }

    suspend fun saveJob(
        jobId: String,
        storeId: String,
        type: String,
        dateIso: String,
        timeIso: String?,
        reminderEnabled: Boolean,
        items: List<DraftItemInput>
    ): Result<JobEntity> {
        val res = repository.saveJobAtomically(
            jobId = jobId,
            storeId = storeId,
            type = type,
            dateIso = dateIso,
            timeIso = timeIso,
            reminderEnabled = reminderEnabled,
            items = items
        )
        if (res.isSuccess) rebuildReminders()
        return res
    }

    fun updateJobStatus(jobId: String, newStatus: String) {
        viewModelScope.launch {
            val res = repository.updateJobStatus(jobId, newStatus)
            res.onFailure { notifyMessage(it.message ?: "Could not update job.") }
            res.onSuccess { rebuildReminders() }
        }
    }

    fun rescheduleJob(jobId: String, newDate: String, newTime: String?) {
        viewModelScope.launch {
            val res = repository.rescheduleJob(jobId, newDate, newTime)
            res.onFailure { notifyMessage(it.message ?: "Could not reschedule job.") }
            res.onSuccess { rebuildReminders() }
        }
    }

    fun setJobReminderEnabled(jobId: String, enabled: Boolean) {
        viewModelScope.launch {
            val res = repository.setJobReminderEnabled(jobId, enabled)
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
            val res = repository.reportIssue(jobId, issueType, note, keepOpen, actuals)
            res.onFailure { notifyMessage(it.message ?: "Could not save issue.") }
            res.onSuccess {
                rebuildReminders()
                onDone()
            }
        }
    }

    fun updatePreference(key: String, value: String) {
        viewModelScope.launch {
            repository.setPreference(key, value)
            if (key in setOf(PrefKeys.DAILY, PrefKeys.SUMMARY_MINUTE, PrefKeys.JOB_REMINDERS, PrefKeys.ADVANCE)) {
                rebuildReminders()
            }
        }
    }

    fun updateServiceUrls(searchUrl: String, tileUrl: String): Boolean {
        if (!BusinessRules.isValidHttpsUrl(searchUrl) || !BusinessRules.isValidHttpsUrl(tileUrl)) {
            notifyMessage("Endpoints must be valid HTTPS URLs.")
            return false
        }
        viewModelScope.launch {
            repository.setPreference(PrefKeys.SEARCH_URL, searchUrl.trim())
            repository.setPreference(PrefKeys.TILE_URL, tileUrl.trim())
            notifyMessage("Service endpoints updated.")
        }
        return true
    }

    fun resetServiceUrls() {
        viewModelScope.launch {
            repository.setPreference(PrefKeys.SEARCH_URL, AppPreferences.DEFAULT_SEARCH_URL)
            repository.setPreference(PrefKeys.TILE_URL, AppPreferences.DEFAULT_TILE_URL)
            notifyMessage("Restored default OpenStreetMap & Photon endpoints.")
        }
    }

    fun exportBackupToUri(uri: Uri) {
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    val json = backupManager.exportJson()
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

    fun inspectImportUri(uri: Uri) {
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = appContext.contentResolver.openInputStream(uri)?.use { input ->
                        val data = input.readBytes()
                        if (data.size > BackupManager.MAX_BYTES) {
                            throw IllegalArgumentException("Backup file exceeds 50 MiB limit.")
                        }
                        data
                    } ?: throw IllegalStateException("Cannot read selected file.")
                    val parsed = backupManager.parseAndValidate(String(bytes, Charsets.UTF_8)).getOrThrow()
                    backupManager.buildPreview(parsed)
                }
            }
            res.fold(
                onSuccess = { preview -> _importPreview.value = preview },
                onFailure = { err -> notifyMessage("Invalid backup: ${err.message}") }
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
                backupManager.applyMerge(preview.backup, replaceConflicts, restorePreferences)
            }
            _importPreview.value = null
            res.fold(
                onSuccess = {
                    rebuildReminders()
                    notifyMessage("Backup merged successfully.")
                },
                onFailure = { err ->
                    notifyMessage("Import rejected: ${err.message}")
                }
            )
        }
    }
}
