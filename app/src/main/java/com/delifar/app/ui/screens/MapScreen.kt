package com.delifar.app.ui.screens

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.delifar.app.R
import com.delifar.app.data.JobListRow
import com.delifar.app.data.JobStatus
import com.delifar.app.data.SearchArea
import com.delifar.app.data.StoreWithHours
import com.delifar.app.domain.BusinessRules
import com.delifar.app.network.OsmProjection
import com.delifar.app.network.OsmTileLoader
import com.delifar.app.ui.components.JobCard
import com.delifar.app.ui.components.lStr
import com.delifar.app.ui.components.launchDialer
import com.delifar.app.ui.components.launchDirections
import com.delifar.app.ui.components.todayHoursSummary
import com.delifar.app.ui.theme.AmberAccent
import com.delifar.app.ui.theme.ForestPrimary
import com.delifar.app.ui.theme.TerracottaError
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

enum class MapFilter { TODAY, OVERDUE, UPCOMING, ALL_STORES }

private data class StoreMapPin(
    val store: StoreWithHours,
    val jobs: List<JobListRow>,
    val hasOverdue: Boolean
)

private data class MapCluster(
    val screenX: Float,
    val screenY: Float,
    val pins: List<StoreMapPin>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    stores: List<StoreWithHours>,
    jobRows: List<JobListRow>,
    defaultArea: String,
    tileUrlTemplate: String,
    tileLoader: OsmTileLoader,
    onOpenStore: (String) -> Unit,
    onOpenJob: (String) -> Unit,
    onNewJobForStore: (String) -> Unit
) {
    val context = LocalContext.current
    val todayLa = remember { BusinessRules.todayLa() }
    val todayIso = todayLa.toString()

    var filter by remember { mutableStateOf(MapFilter.TODAY) }
    var listMode by remember { mutableStateOf(false) }
    var selectedPin by remember { mutableStateOf<StoreMapPin?>(null) }

    // Initial center based on default area
    var centerLat by remember { mutableDoubleStateOf(if (defaultArea == SearchArea.SAC) 38.5816 else 34.0522) }
    var centerLon by remember { mutableDoubleStateOf(if (defaultArea == SearchArea.SAC) -121.4944 else -118.2437) }
    var zoom by remember { mutableIntStateOf(11) }

    val pins = remember(stores, jobRows, filter, todayIso) {
        val activeStores = stores.filter { !it.store.archived }
        val filteredJobs = when (filter) {
            MapFilter.TODAY -> jobRows.filter { it.job.date == todayIso && it.job.status in JobStatus.ACTIVE }
            MapFilter.OVERDUE -> jobRows.filter { BusinessRules.isOverdue(it.job.date, it.job.status, todayLa) }
            MapFilter.UPCOMING -> jobRows.filter { it.job.date >= todayIso && it.job.status in JobStatus.ACTIVE }
            MapFilter.ALL_STORES -> jobRows.filter { it.job.status in JobStatus.ACTIVE }
        }
        val jobsByStore = filteredJobs.groupBy { it.job.storeId }
        val targetStores = if (filter == MapFilter.ALL_STORES) {
            activeStores
        } else {
            activeStores.filter { jobsByStore.containsKey(it.store.id) }
        }
        targetStores.map { swh ->
            val sJobs = BusinessRules.sortJobsForDay(jobsByStore[swh.store.id].orEmpty())
            StoreMapPin(
                store = swh,
                jobs = sJobs,
                hasOverdue = sJobs.any { BusinessRules.isOverdue(it.job.date, it.job.status, todayLa) }
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(lStr(R.string.map_title), style = MaterialTheme.typography.titleLarge) },
                actions = {
                    FilterChip(
                        selected = listMode,
                        onClick = { listMode = !listMode },
                        label = { Text(if (listMode) lStr(R.string.map_canvas_view) else lStr(R.string.map_list_view)) },
                        leadingIcon = {
                            Icon(
                                imageVector = if (listMode) Icons.Default.Map else Icons.Default.FormatListBulleted,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        modifier = Modifier.padding(end = 8.dp)
                    )
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
            // Filter chips + area quick jump
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = filter == MapFilter.TODAY,
                    onClick = { filter = MapFilter.TODAY },
                    label = { Text(lStr(R.string.today)) }
                )
                FilterChip(
                    selected = filter == MapFilter.OVERDUE,
                    onClick = { filter = MapFilter.OVERDUE },
                    label = { Text(lStr(R.string.overdue)) }
                )
                FilterChip(
                    selected = filter == MapFilter.UPCOMING,
                    onClick = { filter = MapFilter.UPCOMING },
                    label = { Text(lStr(R.string.upcoming)) }
                )
                FilterChip(
                    selected = filter == MapFilter.ALL_STORES,
                    onClick = { filter = MapFilter.ALL_STORES },
                    label = { Text(lStr(R.string.tab_stores)) }
                )
                FilterChip(
                    selected = false,
                    onClick = {
                        centerLat = 34.0522
                        centerLon = -118.2437
                        zoom = 11
                    },
                    label = { Text(lStr(R.string.area_la)) }
                )
                FilterChip(
                    selected = false,
                    onClick = {
                        centerLat = 38.5816
                        centerLon = -121.4944
                        zoom = 11
                    },
                    label = { Text(lStr(R.string.area_sac)) }
                )
            }

            if (listMode) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (pins.isEmpty()) {
                        item {
                            Text(
                                text = lStr(R.string.no_jobs_in_filter),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 12.dp)
                            )
                        }
                    } else {
                        items(pins, key = { it.store.store.id }) { pin ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedPin = pin },
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(pin.store.store.name, style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            pin.store.store.address,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            lStr(R.string.map_jobs_at_store, pin.jobs.size),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (pin.hasOverdue) TerracottaError else MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            launchDirections(
                                                context,
                                                pin.store.store.lat,
                                                pin.store.store.lon,
                                                pin.store.store.name
                                            )
                                        }
                                    ) {
                                        Icon(Icons.Default.Directions, contentDescription = lStr(R.string.directions))
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                OsmInteractiveMap(
                    centerLat = centerLat,
                    centerLon = centerLon,
                    zoom = zoom,
                    pins = pins,
                    tileUrlTemplate = tileUrlTemplate,
                    tileLoader = tileLoader,
                    onPan = { newLat, newLon ->
                        centerLat = newLat
                        centerLon = newLon
                    },
                    onZoomChange = { newZoom -> zoom = newZoom.coerceIn(4, 18) },
                    onPinTap = { pin -> selectedPin = pin },
                    onClusterTap = { cluster ->
                        val avgLat = cluster.pins.map { it.store.store.lat }.average()
                        val avgLon = cluster.pins.map { it.store.store.lon }.average()
                        centerLat = avgLat
                        centerLon = avgLon
                        zoom = (zoom + 2).coerceAtMost(18)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }
        }
    }

    selectedPin?.let { pin ->
        ModalBottomSheet(onDismissRequest = { selectedPin = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(pin.store.store.name, style = MaterialTheme.typography.titleLarge)
                Text(
                    pin.store.store.address,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "${lStr(R.string.receiving_hours)}: ${todayHoursSummary(pin.store.hours)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            launchDirections(context, pin.store.store.lat, pin.store.store.lon, pin.store.store.name)
                        }
                    ) {
                        Icon(Icons.Default.Directions, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(lStr(R.string.directions))
                    }
                    if (pin.store.store.phone.isNotBlank()) {
                        OutlinedButton(onClick = { launchDialer(context, pin.store.store.phone) }) {
                            Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(lStr(R.string.call_store))
                        }
                    }
                    Button(
                        onClick = {
                            val id = pin.store.store.id
                            selectedPin = null
                            onOpenStore(id)
                        }
                    ) {
                        Text(lStr(R.string.store_details))
                    }
                }

                if (pin.jobs.isNotEmpty()) {
                    Text(
                        lStr(R.string.map_jobs_at_store, pin.jobs.size),
                        style = MaterialTheme.typography.titleMedium
                    )
                    pin.jobs.take(5).forEach { row ->
                        JobCard(
                            row = row,
                            onClick = {
                                selectedPin = null
                                onOpenJob(row.job.id)
                            }
                        )
                    }
                } else {
                    OutlinedButton(
                        onClick = {
                            val id = pin.store.store.id
                            selectedPin = null
                            onNewJobForStore(id)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(lStr(R.string.new_job))
                    }
                }
                Spacer(Modifier.height(18.dp))
            }
        }
    }
}

@Composable
private fun OsmInteractiveMap(
    centerLat: Double,
    centerLon: Double,
    zoom: Int,
    pins: List<StoreMapPin>,
    tileUrlTemplate: String,
    tileLoader: OsmTileLoader,
    onPan: (Double, Double) -> Unit,
    onZoomChange: (Int) -> Unit,
    onPinTap: (StoreMapPin) -> Unit,
    onClusterTap: (MapCluster) -> Unit,
    modifier: Modifier = Modifier
) {
    val tileSizePx = 256
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val loadedTiles = remember { mutableStateMapOf<String, Bitmap>() }
    var tileFailed by remember { mutableStateOf(false) }

    val (centerTileX, centerTileY) = remember(centerLat, centerLon, zoom) {
        OsmProjection.latLonToTileDouble(centerLat, centerLon, zoom)
    }

    val visibleRange = remember(centerTileX, centerTileY, zoom, canvasSize) {
        if (canvasSize.width == 0 || canvasSize.height == 0) {
            IntRange.EMPTY to IntRange.EMPTY
        } else {
            val halfTilesX = canvasSize.width / (2.0 * tileSizePx)
            val halfTilesY = canvasSize.height / (2.0 * tileSizePx)
            val minX = floor(centerTileX - halfTilesX).toInt()
            val maxX = ceil(centerTileX + halfTilesX).toInt()
            val minY = floor(centerTileY - halfTilesY).toInt()
            val maxY = ceil(centerTileY + halfTilesY).toInt()
            (minX..maxX) to (minY..maxY)
        }
    }

    // Fetch visible OSM tiles asynchronously (cached 7 days on disk + LRU memory)
    LaunchedEffect(visibleRange, zoom, tileUrlTemplate) {
        val (xRange, yRange) = visibleRange
        if (xRange.isEmpty() || yRange.isEmpty()) return@LaunchedEffect
        var anyFailure = false
        for (tx in xRange) {
            for (ty in yRange) {
                val key = "$zoom/$tx/$ty/$tileUrlTemplate"
                if (!loadedTiles.containsKey(key)) {
                    launch {
                        val bmp = tileLoader.loadTile(zoom, tx, ty, tileUrlTemplate)
                        if (bmp != null) {
                            loadedTiles[key] = bmp
                        } else {
                            anyFailure = true
                            if (loadedTiles.isEmpty()) tileFailed = true
                        }
                    }
                }
            }
        }
        if (loadedTiles.isNotEmpty() && !anyFailure) tileFailed = false
    }

    // Screen-space clustering (merge pins within 72px)
    val clusters = remember(pins, centerTileX, centerTileY, zoom, canvasSize) {
        if (canvasSize.width == 0 || canvasSize.height == 0) return@remember emptyList<MapCluster>()
        val cx = canvasSize.width / 2f
        val cy = canvasSize.height / 2f
        val projected = pins.map { pin ->
            val (px, py) = OsmProjection.latLonToTileDouble(pin.store.store.lat, pin.store.store.lon, zoom)
            val sx = cx + ((px - centerTileX) * tileSizePx).toFloat()
            val sy = cy + ((py - centerTileY) * tileSizePx).toFloat()
            Triple(sx, sy, pin)
        }
        val used = BooleanArray(projected.size)
        val out = mutableListOf<MapCluster>()
        for (i in projected.indices) {
            if (used[i]) continue
            used[i] = true
            val (sx, sy, firstPin) = projected[i]
            val group = mutableListOf(firstPin)
            var sumX = sx
            var sumY = sy
            for (j in i + 1 until projected.size) {
                if (used[j]) continue
                val (ox, oy, otherPin) = projected[j]
                if (hypot((ox - sx).toDouble(), (oy - sy).toDouble()) < 72.0) {
                    used[j] = true
                    group += otherPin
                    sumX += ox
                    sumY += oy
                }
            }
            out += MapCluster(sumX / group.size, sumY / group.size, group)
        }
        out
    }

    val textPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = 32f
            typeface = Typeface.DEFAULT_BOLD
        }
    }

    Box(
        modifier = modifier
            .background(Color(0xFFE7ECE9))
            .onSizeChanged { canvasSize = it }
            .pointerInput(centerLat, centerLon, zoom) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    val (curX, curY) = OsmProjection.latLonToTileDouble(centerLat, centerLon, zoom)
                    val newX = curX - pan.x / tileSizePx.toDouble()
                    val newY = curY - pan.y / tileSizePx.toDouble()
                    val (nLat, nLon) = OsmProjection.tileDoubleToLatLon(newX, newY, zoom)
                    onPan(nLat, nLon)
                    if (gestureZoom > 1.25f) onZoomChange(zoom + 1)
                    else if (gestureZoom < 0.8f) onZoomChange(zoom - 1)
                }
            }
            .pointerInput(clusters) {
                detectTapGestures { offset ->
                    val hit = clusters.minByOrNull {
                        hypot((it.screenX - offset.x).toDouble(), (it.screenY - offset.y).toDouble())
                    }
                    if (hit != null && hypot((hit.screenX - offset.x).toDouble(), (hit.screenY - offset.y).toDouble()) <= 56.0) {
                        if (hit.pins.size == 1) onPinTap(hit.pins.first()) else onClusterTap(hit)
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val (xRange, yRange) = visibleRange
            // Subtle offline grid backdrop
            val gridStep = 128f
            var gx = 0f
            while (gx < size.width) {
                drawLine(Color(0xFFD5DFDA), Offset(gx, 0f), Offset(gx, size.height), strokeWidth = 1f)
                gx += gridStep
            }
            var gy = 0f
            while (gy < size.height) {
                drawLine(Color(0xFFD5DFDA), Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f)
                gy += gridStep
            }

            // Draw tiles
            for (tx in xRange) {
                for (ty in yRange) {
                    val key = "$zoom/$tx/$ty/$tileUrlTemplate"
                    val bmp = loadedTiles[key]
                    if (bmp != null) {
                        val left = (cx + (tx - centerTileX) * tileSizePx).roundToInt()
                        val top = (cy + (ty - centerTileY) * tileSizePx).roundToInt()
                        drawImage(
                            image = bmp.asImageBitmap(),
                            dstOffset = IntOffset(left, top),
                            dstSize = IntSize(tileSizePx + 1, tileSizePx + 1)
                        )
                    }
                }
            }

            // Draw clustered markers
            for (cluster in clusters) {
                val center = Offset(cluster.screenX, cluster.screenY)
                val isMulti = cluster.pins.size > 1
                val hasOverdue = cluster.pins.any { it.hasOverdue }
                val pinColor = when {
                    hasOverdue -> TerracottaError
                    isMulti -> AmberAccent
                    else -> ForestPrimary
                }
                val radius = if (isMulti) 34f else 28f
                drawCircle(color = Color.White, radius = radius + 4f, center = center)
                drawCircle(color = pinColor, radius = radius, center = center)

                val badgeText = if (isMulti) {
                    cluster.pins.size.toString()
                } else {
                    val count = cluster.pins.first().jobs.size
                    if (count > 0) count.toString() else "•"
                }
                drawIntoCanvas { canvas ->
                    val textY = center.y - (textPaint.descent() + textPaint.ascent()) / 2f
                    canvas.nativeCanvas.drawText(badgeText, center.x, textY, textPaint)
                }
            }
        }

        // Offline tile notice banner
        if (tileFailed && loadedTiles.isEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(12.dp)
            ) {
                Text(
                    text = lStr(R.string.map_tiles_offline),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        // Zoom controls
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(shape = CircleShape, shadowElevation = 3.dp, color = MaterialTheme.colorScheme.surface) {
                IconButton(onClick = { onZoomChange(zoom + 1) }) {
                    Icon(Icons.Default.Add, contentDescription = "Zoom in")
                }
            }
            Surface(shape = CircleShape, shadowElevation = 3.dp, color = MaterialTheme.colorScheme.surface) {
                IconButton(onClick = { onZoomChange(zoom - 1) }) {
                    Icon(Icons.Default.Remove, contentDescription = "Zoom out")
                }
            }
        }

        // Required visible OpenStreetMap attribution
        Surface(
            color = Color.White.copy(alpha = 0.88f),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
        ) {
            Text(
                text = lStr(R.string.osm_attribution),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF1F2937),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}
