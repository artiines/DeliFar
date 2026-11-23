package com.delifar.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.delifar.app.R
import com.delifar.app.data.HoursMode
import com.delifar.app.data.JobListRow
import com.delifar.app.data.SearchArea
import com.delifar.app.data.StoreHoursEntity
import com.delifar.app.data.StoreWithHours
import com.delifar.app.domain.BusinessRules
import com.delifar.app.domain.RolePermissions
import com.delifar.app.ui.DeliFarViewModel
import com.delifar.app.ui.components.JobCard
import com.delifar.app.ui.components.lStr
import com.delifar.app.ui.components.launchDialer
import com.delifar.app.ui.components.launchDirections
import com.delifar.app.ui.components.weekdayShortLabel
import com.delifar.app.ui.theme.TerracottaContainer
import com.delifar.app.ui.theme.TerracottaError
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoreEditorScreen(
    existingStoreId: String?,
    viewModel: DeliFarViewModel,
    onBack: () -> Unit,
    onSaved: (String) -> Unit
) {
    val prefs by viewModel.preferences.collectAsState()
    val searchState by viewModel.searchState.collectAsState()
    val scope = rememberCoroutineScope()

    var area by remember { mutableStateOf(prefs.area) }
    var searchQuery by remember { mutableStateOf("") }

    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var lat by remember { mutableDoubleStateOf(if (prefs.area == SearchArea.SAC) 38.5816 else 34.0522) }
    var lon by remember { mutableDoubleStateOf(if (prefs.area == SearchArea.SAC) -121.4944 else -118.2437) }
    var providerId by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    val dayModes = remember { mutableStateMapOf<Int, String>().apply { (1..7).forEach { this[it] = HoursMode.UNKNOWN } } }
    val dayWindows = remember { mutableStateMapOf<Int, String>().apply { (1..7).forEach { this[it] = "" } } }

    LaunchedEffect(existingStoreId) {
        if (existingStoreId != null) {
            val existing = viewModel.repository.getStoreWithHours(existingStoreId)
            if (existing != null) {
                name = existing.store.name
                address = existing.store.address
                phone = existing.store.phone
                lat = existing.store.lat
                lon = existing.store.lon
                providerId = existing.store.providerId
                (1..7).forEach { d ->
                    val h = existing.hours.find { it.day == d }
                    dayModes[d] = h?.mode ?: HoursMode.UNKNOWN
                    dayWindows[d] = h?.windows ?: ""
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.clearSearchState() }
    }

    val duplicateWarn = lStr(R.string.duplicate_store_warning)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (existingStoreId == null) lStr(R.string.add_store) else lStr(R.string.edit_store),
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
            // Google Places API (New) Search Section
            item {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = area == SearchArea.LA,
                                onClick = {
                                    area = SearchArea.LA
                                    if (existingStoreId == null && providerId == null) {
                                        lat = 34.0522
                                        lon = -118.2437
                                    }
                                },
                                label = { Text(lStr(R.string.area_la)) }
                            )
                            FilterChip(
                                selected = area == SearchArea.SAC,
                                onClick = {
                                    area = SearchArea.SAC
                                    if (existingStoreId == null && providerId == null) {
                                        lat = 38.5816
                                        lon = -121.4944
                                    }
                                },
                                label = { Text(lStr(R.string.area_sac)) }
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                label = { Text(lStr(R.string.places_search_hint)) },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = { viewModel.searchPlaces(searchQuery, area) },
                                enabled = !searchState.loading && searchQuery.trim().length >= 2
                            ) {
                                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                        }

                        if (searchState.loading) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text(lStr(R.string.places_searching), style = MaterialTheme.typography.bodyMedium)
                            }
                        }

                        searchState.error?.let { err ->
                            Text(err, color = TerracottaError, style = MaterialTheme.typography.bodyMedium)
                        }

                        if (searchState.searched && !searchState.loading && searchState.results.isEmpty() && searchState.error == null) {
                            Text(
                                lStr(R.string.places_no_results),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        searchState.results.forEach { place ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        name = place.name
                                        address = place.address
                                        lat = place.lat
                                        lon = place.lon
                                        if (place.phone.isNotBlank() && phone.isBlank()) {
                                            phone = place.phone
                                        }
                                        providerId = place.placeId
                                        errorMsg = null
                                        viewModel.clearSearchState()
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.LocationOn,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(place.name, style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            place.address,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        Text(
                            text = lStr(R.string.places_attribution),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; errorMsg = null },
                    label = { Text(lStr(R.string.store_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it; errorMsg = null },
                    label = { Text(lStr(R.string.store_address)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text(lStr(R.string.store_phone)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                Text(
                    text = lStr(R.string.receiving_hours),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            items((1..7).toList()) { day ->
                val mode = dayModes[day] ?: HoursMode.UNKNOWN
                val win = dayWindows[day] ?: ""
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = weekdayShortLabel(day),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(
                                    selected = mode == HoursMode.UNKNOWN,
                                    onClick = { dayModes[day] = HoursMode.UNKNOWN; dayWindows[day] = "" },
                                    label = { Text(lStr(R.string.hours_unknown)) }
                                )
                                FilterChip(
                                    selected = mode == HoursMode.CLOSED,
                                    onClick = { dayModes[day] = HoursMode.CLOSED; dayWindows[day] = "" },
                                    label = { Text(lStr(R.string.hours_closed)) }
                                )
                                FilterChip(
                                    selected = mode == HoursMode.OPEN,
                                    onClick = {
                                        dayModes[day] = HoursMode.OPEN
                                        if ((dayWindows[day] ?: "").isBlank()) {
                                            dayWindows[day] = "08:00-16:00"
                                        }
                                    },
                                    label = { Text(lStr(R.string.hours_open)) }
                                )
                            }
                        }
                        if (mode == HoursMode.OPEN) {
                            OutlinedTextField(
                                value = win,
                                onValueChange = { dayWindows[day] = it; errorMsg = null },
                                placeholder = { Text(lStr(R.string.hours_windows_hint)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
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
                        val hours = (1..7).map { d ->
                            StoreHoursEntity(
                                storeId = existingStoreId ?: "",
                                day = d,
                                mode = dayModes[d] ?: HoursMode.UNKNOWN,
                                windows = dayWindows[d] ?: ""
                            )
                        }
                        scope.launch {
                            val res = viewModel.saveStore(
                                existingId = existingStoreId,
                                name = name,
                                address = address,
                                lat = lat,
                                lon = lon,
                                providerId = providerId,
                                phone = phone,
                                hours = hours
                            )
                            saving = false
                            res.fold(
                                onSuccess = { onSaved(it.id) },
                                onFailure = { err ->
                                    errorMsg = if (err.message == "DUPLICATE_PROVIDER") {
                                        duplicateWarn
                                    } else {
                                        err.message ?: "Could not save store."
                                    }
                                }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoreDetailScreen(
    storeId: String,
    viewModel: DeliFarViewModel,
    allJobRows: List<JobListRow>,
    onBack: () -> Unit,
    onEditStore: (String) -> Unit,
    onNewJobForStore: (String) -> Unit,
    onOpenJob: (String) -> Unit
) {
    val context = LocalContext.current
    val prefs by viewModel.preferences.collectAsState()
    val zoneId = remember(prefs.companyTimezone) { BusinessRules.resolveZone(prefs.companyTimezone) }
    val canManage = RolePermissions.canManageStoresAndJobs(prefs.activeUserRole)
    val storeWithHours: StoreWithHours? by viewModel.observeStore(storeId).collectAsState(initial = null)
    val storeJobs = remember(allJobRows, storeId) {
        BusinessRules.sortJobsForDay(allJobRows.filter { it.job.storeId == storeId })
    }
    val archiveBlockedMsg = lStr(R.string.archive_blocked_active_jobs)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(storeWithHours?.store?.name ?: lStr(R.string.store_details), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = lStr(R.string.back))
                    }
                },
                actions = {
                    if (storeWithHours != null && canManage) {
                        IconButton(onClick = { onEditStore(storeId) }) {
                            Icon(Icons.Default.Edit, contentDescription = lStr(R.string.edit))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { innerPadding ->
        val swh = storeWithHours
        if (swh == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(swh.store.name, style = MaterialTheme.typography.titleLarge)
                        Text(
                            swh.store.address,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (swh.store.phone.isNotBlank()) {
                            Text(
                                swh.store.phone,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { launchDirections(context, swh.store.lat, swh.store.lon, swh.store.name) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Directions, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(lStr(R.string.directions))
                            }
                            if (swh.store.phone.isNotBlank()) {
                                OutlinedButton(
                                    onClick = { launchDialer(context, swh.store.phone) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(lStr(R.string.call_store))
                                }
                            }
                        }

                        if (canManage) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (!swh.store.archived) {
                                    Button(
                                        onClick = { onNewJobForStore(swh.store.id) },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(lStr(R.string.new_job))
                                    }
                                    OutlinedButton(
                                        onClick = { viewModel.archiveStore(swh.store.id, archiveBlockedMsg) }
                                    ) {
                                        Icon(Icons.Default.Archive, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(lStr(R.string.archive_store))
                                    }
                                } else {
                                    Button(
                                        onClick = { viewModel.restoreStore(swh.store.id) },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.Default.Unarchive, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(lStr(R.string.restore_store))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(lStr(R.string.receiving_hours), style = MaterialTheme.typography.titleMedium)
                        (1..7).forEach { day ->
                            val entry = swh.hours.find { it.day == day }
                            val text = when (entry?.mode) {
                                HoursMode.CLOSED -> lStr(R.string.hours_closed)
                                HoursMode.OPEN -> entry.windows.ifBlank { lStr(R.string.hours_open) }
                                else -> lStr(R.string.hours_unknown)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(weekdayShortLabel(day), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (day < 7) HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                        }
                    }
                }
            }

            item {
                Text(
                    text = lStr(R.string.map_jobs_at_store, storeJobs.size),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            items(storeJobs, key = { it.job.id }) { row ->
                JobCard(row = row, zoneId = zoneId, onClick = { onOpenJob(row.job.id) })
            }
        }
    }
}
