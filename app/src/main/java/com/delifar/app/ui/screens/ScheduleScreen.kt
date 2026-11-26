package com.delifar.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.delifar.app.R
import com.delifar.app.data.JobListRow
import com.delifar.app.data.JobStatus
import com.delifar.app.data.StoreWithHours
import com.delifar.app.domain.BusinessRules
import com.delifar.app.domain.RolePermissions
import com.delifar.app.ui.components.JobCard
import com.delifar.app.ui.components.LocalAppLocale
import com.delifar.app.ui.components.lStr
import com.delifar.app.ui.components.launchDialer
import com.delifar.app.ui.components.launchDirections
import com.delifar.app.ui.components.todayHoursSummary
import com.delifar.app.ui.components.weekdayShortLabel
import com.delifar.app.ui.theme.TerracottaError
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class ScheduleMode { DAY, WEEK, OVERDUE, ALL }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    stores: List<StoreWithHours> = emptyList(),
    jobRows: List<JobListRow>,
    hasActiveStores: Boolean,
    zoneId: ZoneId,
    userRole: String = "",
    canManage: Boolean,
    onOpenJob: (String) -> Unit,
    onNewJob: () -> Unit,
    onQuickStartJob: ((String) -> Unit)? = null,
    onQuickCompleteJob: ((String) -> Unit)? = null,
    onAddInvoiceForJob: ((String, String) -> Unit)? = null,
    onNotify: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val appLocale = LocalAppLocale.current
    val today = remember(zoneId) { BusinessRules.todayInZone(zoneId) }
    var selectedDate by remember(zoneId) { mutableStateOf(today) }
    var mode by remember { mutableStateOf(ScheduleMode.WEEK) }

    val storesById = remember(stores) { stores.associateBy { it.store.id } }
    val canUpdateJobs = remember(userRole) { userRole.isNotBlank() && RolePermissions.canUpdateAssignedJobs(userRole) }
    val canUploadInvoices = remember(userRole) { userRole.isNotBlank() && RolePermissions.canUploadAndSignInvoices(userRole) }
    val invalidCoordMsg = lStr(R.string.invalid_store_coordinates)

    val weekDays = remember(selectedDate) { BusinessRules.weekDays(selectedDate) }
    val (weekStart, weekEnd) = remember(selectedDate) { BusinessRules.weekRange(selectedDate) }
    val monthFmt = remember(appLocale) { DateTimeFormatter.ofPattern("MMM d", appLocale) }

    val activeCountsByDate = remember(jobRows) {
        jobRows.filter { it.job.status in JobStatus.ACTIVE }.groupingBy { it.job.date }.eachCount()
    }

    val displayedJobs = remember(jobRows, mode, selectedDate, weekStart, weekEnd, today) {
        val filtered = when (mode) {
            ScheduleMode.DAY -> jobRows.filter { it.job.date == selectedDate.toString() }
            ScheduleMode.WEEK -> jobRows.filter {
                val d = BusinessRules.parseIsoDate(it.job.date)
                d != null && !d.isBefore(weekStart) && !d.isAfter(weekEnd)
            }
            ScheduleMode.OVERDUE -> jobRows.filter { BusinessRules.isOverdue(it.job.date, it.job.status, today) }
            ScheduleMode.ALL -> jobRows
        }
        BusinessRules.sortJobsForDay(filtered)
    }

    val groupedByDate = remember(displayedJobs) {
        displayedJobs.groupBy { it.job.date }.toSortedMap()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(lStr(R.string.schedule_title), style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${weekStart.format(monthFmt)} – ${weekEnd.format(monthFmt)} • ${zoneId.id}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        floatingActionButton = {
            if (hasActiveStores && canManage) {
                FloatingActionButton(
                    onClick = onNewJob,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(Icons.Default.Add, contentDescription = lStr(R.string.new_job))
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Monday-Sunday week navigation strip
            Surface(
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { selectedDate = selectedDate.minusWeeks(1) }) {
                        Icon(Icons.Default.ChevronLeft, contentDescription = "Previous week")
                    }
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        weekDays.forEach { day ->
                            val isSelected = day == selectedDate && mode == ScheduleMode.DAY
                            val isToday = day == today
                            val count = activeCountsByDate[day.toString()] ?: 0
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        when {
                                            isSelected -> MaterialTheme.colorScheme.primary
                                            isToday -> MaterialTheme.colorScheme.primaryContainer
                                            else -> MaterialTheme.colorScheme.surface
                                        }
                                    )
                                    .clickable {
                                        selectedDate = day
                                        mode = ScheduleMode.DAY
                                    }
                                    .padding(horizontal = 6.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = weekdayShortLabel(day.dayOfWeek.value),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = day.dayOfMonth.toString(),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.height(2.dp))
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(
                                            when {
                                                count == 0 -> MaterialTheme.colorScheme.surface.copy(alpha = 0f)
                                                day.isBefore(today) -> TerracottaError
                                                isSelected -> MaterialTheme.colorScheme.onPrimary
                                                else -> MaterialTheme.colorScheme.secondary
                                            }
                                        )
                                )
                            }
                        }
                    }
                    IconButton(onClick = { selectedDate = selectedDate.plusWeeks(1) }) {
                        Icon(Icons.Default.ChevronRight, contentDescription = "Next week")
                    }
                }
            }

            // Mode filter chips (horizontally scrollable so RU/HY labels never clip)
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = mode == ScheduleMode.WEEK,
                        onClick = { mode = ScheduleMode.WEEK },
                        label = { Text(lStr(R.string.this_week)) }
                    )
                }
                item {
                    FilterChip(
                        selected = mode == ScheduleMode.DAY && selectedDate == today,
                        onClick = {
                            selectedDate = today
                            mode = ScheduleMode.DAY
                        },
                        label = { Text(lStr(R.string.today)) }
                    )
                }
                item {
                    FilterChip(
                        selected = mode == ScheduleMode.OVERDUE,
                        onClick = { mode = ScheduleMode.OVERDUE },
                        label = { Text(lStr(R.string.overdue)) }
                    )
                }
                item {
                    FilterChip(
                        selected = mode == ScheduleMode.ALL,
                        onClick = { mode = ScheduleMode.ALL },
                        label = { Text(lStr(R.string.all)) }
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (groupedByDate.isEmpty()) {
                    item {
                        Text(
                            text = lStr(R.string.no_jobs_in_filter),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }
                } else {
                    groupedByDate.forEach { (dateIso, rows) ->
                        item(key = "hdr_$dateIso") {
                            val parsed: LocalDate? = BusinessRules.parseIsoDate(dateIso)
                            val dayHeader = if (parsed != null) {
                                "${weekdayShortLabel(parsed.dayOfWeek.value)} • $dateIso"
                            } else {
                                dateIso
                            }
                            Text(
                                text = dayHeader,
                                style = MaterialTheme.typography.titleMedium,
                                color = if (parsed != null && parsed.isBefore(today)) TerracottaError
                                else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                        items(rows, key = { it.job.id }) { row ->
                            val storeWithHours = storesById[row.job.storeId]
                            val phone = storeWithHours?.store?.phone.orEmpty()
                            JobCard(
                                row = row,
                                zoneId = zoneId,
                                onClick = { onOpenJob(row.job.id) },
                                receivingHoursToday = storeWithHours?.let { todayHoursSummary(it.hours, zoneId) },
                                storeHours = storeWithHours?.hours.orEmpty(),
                                onQuickNavigate = {
                                    val store = storeWithHours?.store
                                    val lat = store?.lat ?: row.job.snapshotLat
                                    val lon = store?.lon ?: row.job.snapshotLon
                                    val ok = launchDirections(context, lat, lon, row.displayStoreName, row.displayStoreAddress)
                                    if (!ok) onNotify?.invoke(invalidCoordMsg)
                                },
                                onQuickCall = if (phone.isNotBlank()) ({ launchDialer(context, phone) }) else null,
                                onQuickAdvanceStatus = if (canUpdateJobs) {
                                    {
                                        if (row.job.status == JobStatus.PROGRESS) onQuickCompleteJob?.invoke(row.job.id)
                                        else onQuickStartJob?.invoke(row.job.id)
                                    }
                                } else null,
                                onQuickInvoice = if (canUploadInvoices && onAddInvoiceForJob != null) {
                                    { onAddInvoiceForJob(row.job.id, row.job.storeId) }
                                } else null
                            )
                        }
                    }
                }
            }
        }
    }
}
