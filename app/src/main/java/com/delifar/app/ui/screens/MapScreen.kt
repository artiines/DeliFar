package com.delifar.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.delifar.app.R
import com.delifar.app.data.AppPreferences
import com.delifar.app.data.JobListRow
import com.delifar.app.data.JobStatus
import com.delifar.app.data.SearchArea
import com.delifar.app.data.StoreWithHours
import com.delifar.app.domain.BusinessRules
import com.delifar.app.domain.RolePermissions
import com.delifar.app.network.GeoPoint
import com.delifar.app.network.GeoValidation
import com.delifar.app.ui.DeliFarViewModel
import com.delifar.app.ui.components.DeliFarMapLibreView
import com.delifar.app.ui.components.JobCard
import com.delifar.app.ui.components.MapStorePin
import com.delifar.app.ui.components.StatusTag
import com.delifar.app.ui.components.lStr
import com.delifar.app.ui.components.launchDialer
import com.delifar.app.ui.components.launchDirections
import com.delifar.app.ui.components.todayHoursSummary
import com.delifar.app.ui.theme.TerracottaContainer
import com.delifar.app.ui.theme.TerracottaError
import java.time.ZoneId

enum class MapDateFilter { TODAY, OVERDUE, WEEK, ALL }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    stores: List<StoreWithHours>,
    jobRows: List<JobListRow>,
    defaultArea: String,
    zoneId: ZoneId,
    userRole: String,
    viewModel: DeliFarViewModel,
    onOpenStore: (String) -> Unit,
    onOpenJob: (String) -> Unit,
    onNewJobForStore: (String) -> Unit
) {
    val context = LocalContext.current
    val prefs by viewModel.preferences.collectAsState()
    val routeState by viewModel.routeState.collectAsState()
    val today = remember(zoneId) { BusinessRules.todayInZone(zoneId) }
    val (weekStart, weekEnd) = remember(today) { BusinessRules.weekRange(today) }

    var filter by remember { mutableStateOf(MapDateFilter.TODAY) }
    var listMode by remember { mutableStateOf(false) }
    var selectedStoreId by remember { mutableStateOf<String?>(null) }
    var minimalStyle by remember { mutableStateOf(false) }

    val activeStores = remember(stores) { stores.filter { !it.store.archived } }

    val filteredJobs = remember(jobRows, filter, today, weekStart, weekEnd) {
        val activeOnly = jobRows.filter { it.job.status in JobStatus.ACTIVE }
        when (filter) {
            MapDateFilter.TODAY -> activeOnly.filter { it.job.date == today.toString() }
            MapDateFilter.OVERDUE -> activeOnly.filter { BusinessRules.isOverdue(it.job.date, it.job.status, today) }
            MapDateFilter.WEEK -> activeOnly.filter {
                val d = BusinessRules.parseIsoDate(it.job.date)
                d != null && !d.isBefore(weekStart) && !d.isAfter(weekEnd)
            }
            MapDateFilter.ALL -> activeOnly
        }
    }

    val jobsByStore = remember(filteredJobs) { filteredJobs.groupBy { it.job.storeId } }
    val visibleStores = remember(activeStores, jobsByStore, filter) {
        if (filter == MapDateFilter.ALL) activeStores
        else activeStores.filter { jobsByStore.containsKey(it.store.id) }.ifEmpty { activeStores }
    }

    val defaultCenter = remember(defaultArea) {
        if (defaultArea == SearchArea.SAC) GeoPoint(38.5816, -121.4944) else GeoPoint(34.0522, -118.2437)
    }

    val mapPins = remember(visibleStores, jobsByStore, today) {
        visibleStores.filter { GeoValidation.isValidCoordinate(it.store.lat, it.store.lon) }.map { swh ->
            val jobsHere = jobsByStore[swh.store.id].orEmpty()
            val hasOverdue = jobsHere.any { BusinessRules.isOverdue(it.job.date, it.job.status, today) }
            val colorHex = when {
                hasOverdue -> "#DC2626"
                jobsHere.isNotEmpty() -> "#0284C7"
                else -> "#183A37"
            }
            MapStorePin(
                storeId = swh.store.id,
                title = swh.store.name,
                lat = swh.store.lat,
                lon = swh.store.lon,
                colorHex = colorHex,
                activeJobCount = jobsHere.size
            )
        }
    }

    val activeStyleUrl = remember(prefs.mapStyleUrl, minimalStyle) {
        if (minimalStyle) AppPreferences.OPENFREEMAP_POSITRON_STYLE_URL
        else prefs.mapStyleUrl.ifBlank { AppPreferences.DEFAULT_MAP_STYLE_URL }
    }

    val selectedStore = remember(visibleStores, selectedStoreId) {
        visibleStores.find { it.store.id == selectedStoreId }
    }
    val invalidCoordMsg = lStr(R.string.invalid_store_coordinates)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(lStr(R.string.map_title), style = MaterialTheme.typography.titleLarge) },
                actions = {
                    if (!listMode) {
                        IconButton(onClick = { minimalStyle = !minimalStyle }) {
                            Icon(
                                imageVector = Icons.Default.Layers,
                                contentDescription = lStr(R.string.map_style_toggle),
                                tint = if (minimalStyle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    TextButton(onClick = { listMode = !listMode }) {
                        Icon(
                            imageVector = if (listMode) Icons.Default.Map else Icons.Default.FormatListBulleted,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(if (listMode) lStr(R.string.map_canvas_view) else lStr(R.string.map_list_view))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = filter == MapDateFilter.TODAY,
                    onClick = { filter = MapDateFilter.TODAY; viewModel.clearRoutePlan() },
                    label = { Text(lStr(R.string.today)) }
                )
                FilterChip(
                    selected = filter == MapDateFilter.OVERDUE,
                    onClick = { filter = MapDateFilter.OVERDUE; viewModel.clearRoutePlan() },
                    label = { Text(lStr(R.string.overdue)) }
                )
                FilterChip(
                    selected = filter == MapDateFilter.WEEK,
                    onClick = { filter = MapDateFilter.WEEK; viewModel.clearRoutePlan() },
                    label = { Text(lStr(R.string.this_week)) }
                )
                FilterChip(
                    selected = filter == MapDateFilter.ALL,
                    onClick = { filter = MapDateFilter.ALL; viewModel.clearRoutePlan() },
                    label = { Text(lStr(R.string.all)) }
                )
            }

            if (listMode) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (visibleStores.isEmpty()) {
                        item {
                            Text(
                                text = lStr(R.string.no_stores_yet),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        items(visibleStores, key = { it.store.id }) { swh ->
                            val jobsHere = jobsByStore[swh.store.id].orEmpty()
                            StoreMapListCard(
                                swh = swh,
                                jobsHere = jobsHere,
                                zoneId = zoneId,
                                onOpenStore = { onOpenStore(swh.store.id) },
                                onDirections = {
                                    val launched = launchDirections(
                                        context,
                                        swh.store.lat,
                                        swh.store.lon,
                                        swh.store.name,
                                        swh.store.address
                                    )
                                    if (!launched) viewModel.notifyMessage(invalidCoordMsg)
                                },
                                onCall = { launchDialer(context, swh.store.phone) }
                            )
                        }
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    DeliFarMapLibreView(
                        styleUrl = activeStyleUrl,
                        pins = mapPins,
                        selectedStoreId = selectedStoreId,
                        routePoints = routeState.route?.polylinePoints.orEmpty(),
                        isStreetRoute = routeState.route?.isStreetRoute ?: false,
                        defaultCenter = defaultCenter,
                        fitTriggerKey = filter,
                        onSelectStore = { selectedStoreId = it },
                        modifier = Modifier.fillMaxSize()
                    )

                    // Optimize Multi-Stop Route overlay (OSRM when configured, or Offline Stop Order)
                    if (mapPins.size >= 2) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                            shadowElevation = 4.dp,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (routeState.loading) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(
                                        Icons.Default.Route,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                val r = routeState.route
                                if (r != null) {
                                    val mi = r.distanceMeters / 1609.34
                                    val mins = r.durationSeconds / 60
                                    Column {
                                        Text(
                                            text = "%.1f mi • %d min".format(mi, mins),
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = r.sourceLabel,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(
                                        onClick = { viewModel.clearRoutePlan() },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = lStr(R.string.clear), modifier = Modifier.size(16.dp))
                                    }
                                } else {
                                    Text(
                                        text = lStr(R.string.map_optimize_route),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.clickable {
                                            val stops = mapPins.map { GeoPoint(it.lat, it.lon) }
                                            viewModel.optimizeRouteForStops(stops)
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Attribution badge when bottom card is closed
                    if (selectedStore == null) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(10.dp)
                        ) {
                            Text(
                                text = lStr(R.string.map_attribution),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    // Selected Store Bottom Card
                    if (selectedStore != null) {
                        val jobsHere = jobsByStore[selectedStore.store.id].orEmpty()
                        Card(
                            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = selectedStore.store.name,
                                            style = MaterialTheme.typography.titleLarge,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = selectedStore.store.address,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2
                                        )
                                    }
                                    IconButton(onClick = { selectedStoreId = null }) {
                                        Icon(Icons.Default.Close, contentDescription = lStr(R.string.close))
                                    }
                                }

                                Text(
                                    text = "${lStr(R.string.receiving_hours)}: ${todayHoursSummary(selectedStore.hours, zoneId)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            val ok = launchDirections(
                                                context,
                                                selectedStore.store.lat,
                                                selectedStore.store.lon,
                                                selectedStore.store.name,
                                                selectedStore.store.address
                                            )
                                            if (!ok) viewModel.notifyMessage(invalidCoordMsg)
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.Directions, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(lStr(R.string.directions))
                                    }
                                    if (selectedStore.store.phone.isNotBlank()) {
                                        OutlinedButton(
                                            onClick = { launchDialer(context, selectedStore.store.phone) }
                                        ) {
                                            Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text(lStr(R.string.call_store))
                                        }
                                    }
                                    OutlinedButton(onClick = { onOpenStore(selectedStore.store.id) }) {
                                        Text(lStr(R.string.store_details))
                                    }
                                }

                                if (jobsHere.isNotEmpty()) {
                                    Text(
                                        text = lStr(R.string.map_jobs_at_store, jobsHere.size),
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    jobsHere.take(2).forEach { row ->
                                        JobCard(row = row, zoneId = zoneId, onClick = { onOpenJob(row.job.id) })
                                    }
                                } else if (RolePermissions.canManageStoresAndJobs(userRole)) {
                                    OutlinedButton(
                                        onClick = { onNewJobForStore(selectedStore.store.id) },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(lStr(R.string.new_job))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StoreMapListCard(
    swh: StoreWithHours,
    jobsHere: List<JobListRow>,
    zoneId: ZoneId,
    onOpenStore: () -> Unit,
    onDirections: () -> Unit,
    onCall: () -> Unit
) {
    val today = remember(zoneId) { BusinessRules.todayInZone(zoneId) }
    val hasOverdue = jobsHere.any { BusinessRules.isOverdue(it.job.date, it.job.status, today) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenStore),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    swh.store.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (jobsHere.isNotEmpty()) {
                    StatusTag(
                        text = "${jobsHere.size}",
                        bg = if (hasOverdue) TerracottaContainer else MaterialTheme.colorScheme.primaryContainer,
                        fg = if (hasOverdue) TerracottaError else MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Text(
                swh.store.address,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "${lStr(R.string.receiving_hours)}: ${todayHoursSummary(swh.hours, zoneId)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDirections) {
                    Icon(Icons.Default.Directions, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(lStr(R.string.directions))
                }
                if (swh.store.phone.isNotBlank()) {
                    OutlinedButton(onClick = onCall) {
                        Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(lStr(R.string.call_store))
                    }
                }
            }
        }
    }
}
