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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.delifar.app.R
import com.delifar.app.data.JobListRow
import com.delifar.app.data.JobStatus
import com.delifar.app.data.StoreWithHours
import com.delifar.app.ui.components.ReceivingWindowStatusTag
import com.delifar.app.ui.components.StatusTag
import com.delifar.app.ui.components.lStr
import com.delifar.app.ui.components.launchDialer
import com.delifar.app.ui.components.launchDirections
import com.delifar.app.ui.components.todayHoursSummary
import com.delifar.app.ui.theme.AmberAccent
import com.delifar.app.ui.theme.AmberContainer
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoresScreen(
    stores: List<StoreWithHours>,
    jobRows: List<JobListRow>,
    zoneId: ZoneId,
    canManage: Boolean,
    onAddStore: () -> Unit,
    onOpenStore: (String) -> Unit
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var showArchived by remember { mutableStateOf(false) }

    val activeJobsByStore = remember(jobRows) {
        jobRows.filter { it.job.status in JobStatus.ACTIVE }.groupingBy { it.job.storeId }.eachCount()
    }

    val filtered = remember(stores, query, showArchived) {
        val q = query.trim().lowercase()
        stores.filter { swh ->
            swh.store.archived == showArchived && (
                q.isEmpty() ||
                    swh.store.name.lowercase().contains(q) ||
                    swh.store.address.lowercase().contains(q)
                )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(lStr(R.string.stores_title), style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        floatingActionButton = {
            if (canManage) {
                ExtendedFloatingActionButton(
                    onClick = onAddStore,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text(lStr(R.string.add_store)) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(lStr(R.string.search_stores_hint)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = !showArchived,
                    onClick = { showArchived = false },
                    label = { Text("${lStr(R.string.active_stores)} (${stores.count { !it.store.archived }})") }
                )
                FilterChip(
                    selected = showArchived,
                    onClick = { showArchived = true },
                    label = { Text("${lStr(R.string.archived_stores)} (${stores.count { it.store.archived }})") }
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            text = lStr(R.string.no_stores_yet),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 16.dp)
                        )
                    }
                } else {
                    items(filtered, key = { it.store.id }) { swh ->
                        val activeCount = activeJobsByStore[swh.store.id] ?: 0
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenStore(swh.store.id) },
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = swh.store.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = swh.store.address,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (!swh.store.locationConfirmed) {
                                        Spacer(Modifier.width(6.dp))
                                        StatusTag(
                                            text = lStr(R.string.map_review_pin),
                                            bg = AmberContainer,
                                            fg = AmberAccent
                                        )
                                    }
                                    if (activeCount > 0) {
                                        Spacer(Modifier.width(8.dp))
                                        StatusTag(
                                            text = "$activeCount",
                                            bg = MaterialTheme.colorScheme.primaryContainer,
                                            fg = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Schedule,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Text(
                                            text = todayHoursSummary(swh.hours, zoneId),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        ReceivingWindowStatusTag(hours = swh.hours, zoneId = zoneId)
                                    }
                                    Row {
                                        if (swh.store.phone.isNotBlank()) {
                                            IconButton(
                                                onClick = { launchDialer(context, swh.store.phone) },
                                                modifier = Modifier.size(44.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.Call,
                                                    contentDescription = lStr(R.string.call_store),
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                        IconButton(
                                            onClick = {
                                                if (!swh.store.locationConfirmed) {
                                                    onOpenStore(swh.store.id)
                                                } else {
                                                    launchDirections(
                                                        context,
                                                        swh.store.lat,
                                                        swh.store.lon,
                                                        swh.store.name,
                                                        swh.store.address
                                                    )
                                                }
                                            },
                                            modifier = Modifier.size(44.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Directions,
                                                contentDescription = lStr(R.string.directions),
                                                tint = if (swh.store.locationConfirmed) MaterialTheme.colorScheme.onSurface else AmberAccent,
                                                modifier = Modifier.size(20.dp)
                                            )
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
}
