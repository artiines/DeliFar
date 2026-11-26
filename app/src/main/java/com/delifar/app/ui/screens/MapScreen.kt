package com.delifar.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.material3.FilledTonalButton
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
import com.delifar.app.ui.components.ReceivingWindowStatusTag
import com.delifar.app.ui.components.StatusTag
import com.delifar.app.ui.components.lStr
import com.delifar.app.ui.components.launchDialer
import com.delifar.app.ui.components.launchDirections
import com.delifar.app.ui.components.todayHoursSummary
import com.delifar.app.ui.theme.AmberAccent
import com.delifar.app.ui.theme.AmberContainer
import com.delifar.app.ui.theme.InfoBlue
import com.delifar.app.ui.theme.InfoContainer
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
    val isShowingFallbackAllStores = remember(activeStores, jobsByStore, filter) {
        filter != MapDateFilter.ALL && activeStores.isNotEmpty() && activeStores.none { jobsByStore.containsKey(it.store.id) }
    }
    val visibleStores = remember(activeStores, jobsByStore, filter) {
        if (filter == MapDateFilter.ALL) activeStores
        else activeStores.filter { jobsByStore.containsKey(it.store.id) }.ifEmpty { activeStores }
    }

    val defaultCenter = remember(defaultArea) {
        if (defaultArea == SearchArea.SAC) GeoPoint(38.5816, -121.4944) else GeoPoint(34.0522, -118.2437)
    }

    val unconfirmedCount = remember(visibleStores) {
        visibleStores.count { !it.store.locationConfirmed }
    }

    // Order confirmed route stops by scheduled job priority first (time/status/overdue), then remaining visible stores
    val confirmedRouteStores = remember(visibleStores, filteredJobs) {
        val visibleConfirmedById = visibleStores
            .filter { it.store.locationConfirmed && GeoValidation.isValidCoordinate(it.store.lat, it.store.lon) }
            .associateBy { it.store.id }
        val sortedJobStoreIds = BusinessRules.sortJobsForDay(filteredJobs)
            .map { it.job.storeId }
            .distinct()
        val ordered = mutableListOf<StoreWithHours>()
        val seen = mutableSetOf<String>()
        sortedJobStoreIds.forEach { id ->
            visibleConfirmedById[id]?.let { s ->
                if (seen.add(id)) ordered += s
            }
        }
        visibleConfirmedById.values.forEach { s ->
            if (seen.add(s.store.id)) ordered += s
        }
        ordered
    }

    val confirmedRouteStops = remember(confirmedRouteStores) {
        confirmedRouteStores.map { GeoPoint(it.store.lat, it.store.lon) }
    }

    val orderedRouteStores = remember(confirmedRouteStores, routeState.route, routeState.optimizedStopStoreIds) {
        val byId = confirmedRouteStores.associateBy { it.store.id }
        val storeIds = routeState.optimizedStopStoreIds
        if (storeIds.isNotEmpty()) {
            storeIds.mapNotNull { id -> byId[id] }
        } else {
            val indices = routeState.route?.optimizedStopIndices.orEmpty()
            if (indices.isEmpty()) emptyList()
            else indices.mapNotNull { idx -> confirmedRouteStores.getOrNull(idx) }
        }
    }

    val stopOrderByStoreId = remember(orderedRouteStores) {
        orderedRouteStores.mapIndexed { idx, swh -> swh.store.id to (idx + 1) }.toMap()
    }

    val mapPins = remember(visibleStores, jobsByStore, today, stopOrderByStoreId) {
        visibleStores.filter { GeoValidation.isValidCoordinate(it.store.lat, it.store.lon) }.map { swh ->
            val jobsHere = jobsByStore[swh.store.id].orEmpty()
            val hasOverdue = jobsHere.any { BusinessRules.isOverdue(it.job.date, it.job.status, today) }
            val colorHex = when {
                !swh.store.locationConfirmed -> "#D97706"
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
                activeJobCount = jobsHere.size,
                stopOrder = stopOrderByStoreId[swh.store.id]
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
    val reviewPinMsg = lStr(R.string.map_review_pin_before_nav)

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
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item {
                    FilterChip(
                        selected = filter == MapDateFilter.TODAY,
                        onClick = { filter = MapDateFilter.TODAY; viewModel.clearRoutePlan() },
                        label = { Text(lStr(R.string.today)) }
                    )
                }
                item {
                    FilterChip(
                        selected = filter == MapDateFilter.OVERDUE,
                        onClick = { filter = MapDateFilter.OVERDUE; viewModel.clearRoutePlan() },
                        label = { Text(lStr(R.string.overdue)) }
                    )
                }
                item {
                    FilterChip(
                        selected = filter == MapDateFilter.WEEK,
                        onClick = { filter = MapDateFilter.WEEK; viewModel.clearRoutePlan() },
                        label = { Text(lStr(R.string.this_week)) }
                    )
                }
                item {
                    FilterChip(
                        selected = filter == MapDateFilter.ALL,
                        onClick = { filter = MapDateFilter.ALL; viewModel.clearRoutePlan() },
                        label = { Text(lStr(R.string.all)) }
                    )
                }
            }

            if (isShowingFallbackAllStores) {
                Surface(
                    color = InfoContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = lStr(R.string.map_showing_all_fallback, visibleStores.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = InfoBlue,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            if (unconfirmedCount > 0) {
                Surface(
                    color = AmberContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = lStr(R.string.map_unconfirmed_banner, unconfirmedCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = AmberAccent,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
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
                                stopOrder = stopOrderByStoreId[swh.store.id],
                                zoneId = zoneId,
                                onOpenStore = { onOpenStore(swh.store.id) },
                                onDirections = {
                                    if (!swh.store.locationConfirmed) {
                                        viewModel.notifyMessage(reviewPinMsg)
                                        onOpenStore(swh.store.id)
                                    } else {
                                        val launched = launchDirections(
                                            context,
                                            swh.store.lat,
                                            swh.store.lon,
                                            swh.store.name,
                                            swh.store.address
                                        )
                                        if (!launched) viewModel.notifyMessage(invalidCoordMsg)
                                    }
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
                    if (confirmedRouteStops.size >= 2) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                            shadowElevation = 4.dp,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(start = 12.dp, top = 12.dp, end = 68.dp)
                        ) {
                            val r = routeState.route
                            if (r != null) {
                                val mi = r.distanceMeters / 1609.34
                                val mins = (r.durationSeconds / 60).toInt()
                                Column(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Route,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = if (r.isStreetRoute) {
                                                    lStr(R.string.map_street_route_summary, mi, mins)
                                                } else {
                                                    lStr(R.string.map_offline_route_summary, mi, mins)
                                                },
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                text = if (r.isStreetRoute) r.sourceLabel else lStr(R.string.map_offline_route_note),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (r.isStreetRoute) MaterialTheme.colorScheme.onSurfaceVariant else AmberAccent
                                            )
                                        }
                                        IconButton(
                                            onClick = { viewModel.clearRoutePlan() },
                                            modifier = Modifier.size(44.dp)
                                        ) {
                                            Icon(Icons.Default.Close, contentDescription = lStr(R.string.clear), modifier = Modifier.size(18.dp))
                                        }
                                    }
                                    if (orderedRouteStores.isNotEmpty()) {
                                        Text(
                                            text = "${lStr(R.string.map_route_sequence)} " +
                                                orderedRouteStores.mapIndexed { idx, s -> "#${idx + 1} ${s.store.name}" }.joinToString(" → "),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            } else {
                                FilledTonalButton(
                                    onClick = {
                                        viewModel.optimizeRouteForStores(
                                            confirmedRouteStores.map { it.store.id to GeoPoint(it.store.lat, it.store.lon) }
                                        )
                                    },
                                    enabled = !routeState.loading,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    modifier = Modifier.defaultMinSize(minHeight = 44.dp)
                                ) {
                                    if (routeState.loading) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    } else {
                                        Icon(Icons.Default.Route, contentDescription = null, modifier = Modifier.size(18.dp))
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    Text(lStr(R.string.map_optimize_route), maxLines = 1)
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
                        val stopNum = stopOrderByStoreId[selectedStore.store.id]
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
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            if (stopNum != null) {
                                                StatusTag(
                                                    text = lStr(R.string.stop_number_badge, stopNum),
                                                    bg = MaterialTheme.colorScheme.primary,
                                                    fg = MaterialTheme.colorScheme.onPrimary
                                                )
                                            }
                                            Text(
                                                text = selectedStore.store.name,
                                                style = MaterialTheme.typography.titleLarge,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                        }
                                        Text(
                                            text = selectedStore.store.address,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2
                                        )
                                    }
                                    IconButton(
                                        onClick = { selectedStoreId = null },
                                        modifier = Modifier.size(44.dp)
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = lStr(R.string.close))
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${lStr(R.string.receiving_hours)}: ${todayHoursSummary(selectedStore.hours, zoneId)}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    ReceivingWindowStatusTag(hours = selectedStore.hours, zoneId = zoneId)
                                }

                                if (!selectedStore.store.locationConfirmed) {
                                    StatusTag(
                                        text = lStr(R.string.pin_review_required),
                                        bg = AmberContainer,
                                        fg = AmberAccent
                                    )
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            if (!selectedStore.store.locationConfirmed) {
                                                viewModel.notifyMessage(reviewPinMsg)
                                                onOpenStore(selectedStore.store.id)
                                            } else {
                                                val ok = launchDirections(
                                                    context,
                                                    selectedStore.store.lat,
                                                    selectedStore.store.lon,
                                                    selectedStore.store.name,
                                                    selectedStore.store.address
                                                )
                                                if (!ok) viewModel.notifyMessage(invalidCoordMsg)
                                            }
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .defaultMinSize(minHeight = 44.dp)
                                    ) {
                                        Icon(Icons.Default.Directions, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(if (selectedStore.store.locationConfirmed) lStr(R.string.directions) else lStr(R.string.map_review_pin))
                                    }
                                    if (selectedStore.store.phone.isNotBlank()) {
                                        OutlinedButton(
                                            onClick = { launchDialer(context, selectedStore.store.phone) },
                                            modifier = Modifier.defaultMinSize(minHeight = 44.dp)
                                        ) {
                                            Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text(lStr(R.string.call_store))
                                        }
                                    }
                                    OutlinedButton(
                                        onClick = { onOpenStore(selectedStore.store.id) },
                                        modifier = Modifier.defaultMinSize(minHeight = 44.dp)
                                    ) {
                                        Text(lStr(R.string.store_details))
                                    }
                                }

                                if (jobsHere.isNotEmpty()) {
                                    Text(
                                        text = lStr(R.string.map_jobs_at_store, jobsHere.size),
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    jobsHere.take(2).forEach { row ->
                                        JobCard(
                                            row = row,
                                            zoneId = zoneId,
                                            onClick = { onOpenJob(row.job.id) },
                                            receivingHoursToday = todayHoursSummary(selectedStore.hours, zoneId),
                                            storeHours = selectedStore.hours,
                                            stopNumber = stopNum
                                        )
                                    }
                                } else if (RolePermissions.canManageStoresAndJobs(userRole)) {
                                    OutlinedButton(
                                        onClick = { onNewJobForStore(selectedStore.store.id) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .defaultMinSize(minHeight = 44.dp)
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
    stopOrder: Int?,
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    if (stopOrder != null) {
                        StatusTag(
                            text = "#$stopOrder",
                            bg = MaterialTheme.colorScheme.primary,
                            fg = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    Text(
                        swh.store.name,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${lStr(R.string.receiving_hours)}: ${todayHoursSummary(swh.hours, zoneId)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                ReceivingWindowStatusTag(hours = swh.hours, zoneId = zoneId)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onDirections,
                    modifier = Modifier.defaultMinSize(minHeight = 44.dp)
                ) {
                    Icon(Icons.Default.Directions, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(lStr(R.string.directions))
                }
                if (swh.store.phone.isNotBlank()) {
                    OutlinedButton(
                        onClick = onCall,
                        modifier = Modifier.defaultMinSize(minHeight = 44.dp)
                    ) {
                        Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(lStr(R.string.call_store))
                    }
                }
            }
        }
    }
}
