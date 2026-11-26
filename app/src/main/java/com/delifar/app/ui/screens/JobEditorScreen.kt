package com.delifar.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.delifar.app.R
import com.delifar.app.data.DeliFarRepository
import com.delifar.app.data.DraftEntity
import com.delifar.app.data.DraftItemInput
import com.delifar.app.data.JobType
import com.delifar.app.data.StoreWithHours
import com.delifar.app.domain.BusinessRules
import com.delifar.app.domain.RolePermissions
import com.delifar.app.ui.DeliFarViewModel
import com.delifar.app.ui.components.lStr
import com.delifar.app.ui.theme.AmberAccent
import com.delifar.app.ui.theme.AmberContainer
import com.delifar.app.ui.theme.TerracottaContainer
import com.delifar.app.ui.theme.TerracottaError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobEditorScreen(
    jobIdArg: String?,
    preselectedStoreId: String?,
    resumeDraftId: String?,
    stores: List<StoreWithHours>,
    viewModel: DeliFarViewModel,
    onBack: () -> Unit,
    onSaved: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val prefs by viewModel.preferences.collectAsState()
    val employees by viewModel.employees.collectAsState()
    val zoneId = remember(prefs.companyTimezone) { BusinessRules.resolveZone(prefs.companyTimezone) }
    val activeStores = remember(stores) { stores.filter { !it.store.archived } }
    val assignableEmployees = remember(employees) { employees.filter { it.active } }

    val stableJobId = remember(jobIdArg, resumeDraftId) {
        jobIdArg ?: resumeDraftId ?: UUID.randomUUID().toString()
    }
    var initialized by remember { mutableStateOf(false) }
    var isEditingExisting by remember { mutableStateOf(jobIdArg != null) }

    var storeId by remember { mutableStateOf(preselectedStoreId ?: activeStores.firstOrNull()?.store?.id.orEmpty()) }
    var type by remember { mutableStateOf(JobType.DELIVERY) }
    var dateIso by remember { mutableStateOf(BusinessRules.todayIso(zoneId)) }
    var timeIso by remember { mutableStateOf("") }
    var reminderEnabled by remember { mutableStateOf(true) }
    var assignedDriverUid by remember { mutableStateOf<String?>(null) }
    var assignedDriverName by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    val itemRows = remember { mutableStateListOf(DraftItemInput()) }

    var storeMenuExpanded by remember { mutableStateOf(false) }
    var driverMenuExpanded by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(stableJobId) {
        val (draft, existing) = withContext(Dispatchers.IO) {
            val d = viewModel.repository.db.draftDao().getDraft(stableJobId)
            val e = if (d == null && jobIdArg != null) viewModel.repository.db.jobDao().getJobWithItems(jobIdArg) else null
            d to e
        }
        if (draft != null) {
            isEditingExisting = draft.isEditingExisting
            storeId = draft.storeId.ifBlank { preselectedStoreId ?: activeStores.firstOrNull()?.store?.id.orEmpty() }
            type = draft.type
            dateIso = draft.date.ifBlank { BusinessRules.todayIso(zoneId) }
            timeIso = draft.time
            reminderEnabled = draft.reminderEnabled
            val decoded = DeliFarRepository.decodeDraftItems(draft.itemsJson)
            itemRows.clear()
            itemRows.addAll(if (decoded.isEmpty()) listOf(DraftItemInput()) else decoded)
        } else if (existing != null) {
            isEditingExisting = true
            storeId = existing.job.storeId
            type = existing.job.type
            dateIso = existing.job.date
            timeIso = existing.job.time ?: ""
            reminderEnabled = existing.job.reminderEnabled
            assignedDriverUid = existing.job.assignedDriverUid
            assignedDriverName = existing.job.assignedDriverName
            notes = existing.job.notes
            itemRows.clear()
            itemRows.addAll(
                existing.sortedItems.map {
                    DraftItemInput(id = it.id, name = it.name, planned = it.planned, unit = it.unit)
                }
            )
        }
        initialized = true
    }

    LaunchedEffect(activeStores, initialized) {
        if (initialized && storeId.isBlank() && activeStores.isNotEmpty()) {
            storeId = preselectedStoreId ?: activeStores.first().store.id
        }
    }

    fun persistDraftNow() {
        if (!initialized || saving) return
        viewModel.saveDraftAsync(
            DraftEntity(
                jobId = stableJobId,
                isEditingExisting = isEditingExisting,
                storeId = storeId,
                type = type,
                date = dateIso,
                time = timeIso,
                reminderEnabled = reminderEnabled,
                itemsJson = DeliFarRepository.encodeDraftItems(itemRows.toList()),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    val selectedStore = remember(stores, storeId) { stores.find { it.store.id == storeId } }
    val hoursWarning = remember(selectedStore, dateIso, timeIso) {
        if (selectedStore == null || BusinessRules.parseIsoDate(dateIso) == null) null
        else BusinessRules.checkReceivingHours(selectedStore.hours, dateIso, timeIso.ifBlank { null })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (isEditingExisting) lStr(R.string.edit_job) else lStr(R.string.new_job),
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        persistDraftNow()
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = lStr(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(
                        selected = type == JobType.DELIVERY,
                        onClick = { type = JobType.DELIVERY; persistDraftNow() },
                        label = { Text(lStr(R.string.type_delivery)) }
                    )
                    FilterChip(
                        selected = type == JobType.PICKUP,
                        onClick = { type = JobType.PICKUP; persistDraftNow() },
                        label = { Text(lStr(R.string.type_pickup)) }
                    )
                }
            }

            item {
                ExposedDropdownMenuBox(
                    expanded = storeMenuExpanded,
                    onExpandedChange = { storeMenuExpanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedStore?.store?.name ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(lStr(R.string.select_store)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = storeMenuExpanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = storeMenuExpanded,
                        onDismissRequest = { storeMenuExpanded = false }
                    ) {
                        activeStores.forEach { swh ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(swh.store.name, style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            swh.store.address,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = {
                                    storeId = swh.store.id
                                    storeMenuExpanded = false
                                    errorMsg = null
                                    persistDraftNow()
                                }
                            )
                        }
                    }
                }
            }

            // Driver Assignment Dropdown (Owner / Manager when company has employees)
            if (RolePermissions.canAssignDrivers(prefs.activeUserRole) && assignableEmployees.isNotEmpty()) {
                item {
                    ExposedDropdownMenuBox(
                        expanded = driverMenuExpanded,
                        onExpandedChange = { driverMenuExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = assignedDriverName.ifBlank { lStr(R.string.unassigned_driver) },
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(lStr(R.string.assign_driver)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = driverMenuExpanded) },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = driverMenuExpanded,
                            onDismissRequest = { driverMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(lStr(R.string.unassigned_driver)) },
                                onClick = {
                                    assignedDriverUid = null
                                    assignedDriverName = ""
                                    driverMenuExpanded = false
                                }
                            )
                            assignableEmployees.forEach { emp ->
                                DropdownMenuItem(
                                    text = { Text("${emp.displayName} (${emp.role})") },
                                    onClick = {
                                        assignedDriverUid = emp.uid
                                        assignedDriverName = emp.displayName
                                        driverMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = dateIso,
                        onValueChange = { dateIso = it; errorMsg = null; persistDraftNow() },
                        label = { Text("${lStr(R.string.date_label)} (${zoneId.id.substringAfterLast("/")})") },
                        placeholder = { Text("YYYY-MM-DD") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = timeIso,
                        onValueChange = { timeIso = it; errorMsg = null; persistDraftNow() },
                        label = { Text(lStr(R.string.time_label)) },
                        placeholder = { Text("09:30") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item {
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(lStr(R.string.job_notes_label)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (hoursWarning != null && hoursWarning != BusinessRules.HoursCheckResult.OK) {
                item {
                    val warnText = when (hoursWarning) {
                        BusinessRules.HoursCheckResult.CLOSED -> lStr(R.string.hours_warning_closed)
                        BusinessRules.HoursCheckResult.OUTSIDE_WINDOWS -> lStr(R.string.hours_warning_outside)
                        else -> lStr(R.string.hours_warning_unknown)
                    }
                    Surface(
                        color = AmberContainer,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, AmberAccent.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = AmberAccent,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(warnText, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            if (timeIso.isNotBlank()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(lStr(R.string.job_reminder_switch), style = MaterialTheme.typography.bodyLarge)
                        Switch(
                            checked = reminderEnabled,
                            onCheckedChange = { reminderEnabled = it; persistDraftNow() }
                        )
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "${lStr(R.string.product_lines)} (${itemRows.size}/50)",
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (itemRows.size < 50) {
                        OutlinedButton(
                            onClick = {
                                itemRows.add(DraftItemInput())
                                persistDraftNow()
                            }
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(lStr(R.string.add_product_line))
                        }
                    }
                }
            }

            itemsIndexed(itemRows, key = { _, item -> item.id }) { idx, item ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = item.name,
                                onValueChange = {
                                    itemRows[idx] = item.copy(name = it)
                                    errorMsg = null
                                    persistDraftNow()
                                },
                                label = { Text("${lStr(R.string.product_name)} #${idx + 1}") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            if (itemRows.size > 1) {
                                IconButton(
                                    onClick = {
                                        itemRows.removeAt(idx)
                                        persistDraftNow()
                                    }
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = lStr(R.string.delete), tint = TerracottaError)
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = item.planned,
                                onValueChange = {
                                    itemRows[idx] = item.copy(planned = it)
                                    errorMsg = null
                                    persistDraftNow()
                                },
                                label = { Text(lStr(R.string.planned_qty)) },
                                placeholder = { Text("12.5") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = item.unit,
                                onValueChange = {
                                    itemRows[idx] = item.copy(unit = it)
                                    persistDraftNow()
                                },
                                label = { Text(lStr(R.string.unit_label)) },
                                placeholder = { Text("cs / kg / box") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            errorMsg?.let { err ->
                item {
                    Surface(
                        color = TerracottaContainer,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = err,
                            color = TerracottaError,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }

            item {
                Button(
                    onClick = {
                        if (saving) return@Button
                        saving = true
                        errorMsg = null
                        scope.launch {
                            val res = viewModel.saveJob(
                                jobId = stableJobId,
                                storeId = storeId,
                                type = type,
                                dateIso = dateIso.trim(),
                                timeIso = timeIso.trim().ifEmpty { null },
                                reminderEnabled = reminderEnabled,
                                items = itemRows.toList(),
                                assignedDriverUid = assignedDriverUid,
                                assignedDriverName = assignedDriverName,
                                notes = notes
                            )
                            saving = false
                            res.fold(
                                onSuccess = { onSaved(it.id) },
                                onFailure = { err -> errorMsg = err.message ?: "Could not save job." }
                            )
                        }
                    },
                    enabled = !saving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Text(lStr(R.string.save))
                }
            }
        }
    }
}
