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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.delifar.app.R
import com.delifar.app.data.DraftEntity
import com.delifar.app.data.JobListRow
import com.delifar.app.data.JobStatus
import com.delifar.app.data.StoreWithHours
import com.delifar.app.domain.BusinessRules
import com.delifar.app.domain.RolePermissions
import com.delifar.app.ui.components.JobCard
import com.delifar.app.ui.components.RoleBadge
import com.delifar.app.ui.components.lStr
import com.delifar.app.ui.theme.AmberContainer
import com.delifar.app.ui.theme.SuccessContainer
import com.delifar.app.ui.theme.SuccessGreen
import com.delifar.app.ui.theme.TerracottaContainer
import com.delifar.app.ui.theme.TerracottaError
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    stores: List<StoreWithHours>,
    jobRows: List<JobListRow>,
    latestDraft: DraftEntity?,
    zoneId: ZoneId,
    userRole: String,
    companyName: String?,
    onOpenSettings: () -> Unit,
    onOpenTeam: () -> Unit,
    onNewJob: () -> Unit,
    onResumeDraft: (DraftEntity) -> Unit,
    onDiscardDraft: (String) -> Unit,
    onOpenJob: (String) -> Unit,
    onAddStore: () -> Unit
) {
    val today = remember(zoneId) { BusinessRules.todayInZone(zoneId) }
    val todayIso = today.toString()
    val prettyDate = remember(today) {
        today.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US))
    }

    val overdueJobs = remember(jobRows, today) {
        BusinessRules.sortJobsForDay(jobRows.filter { BusinessRules.isOverdue(it.job.date, it.job.status, today) })
    }
    val todayJobs = remember(jobRows, todayIso) {
        BusinessRules.sortJobsForDay(jobRows.filter { it.job.date == todayIso })
    }
    val todayActiveCount = todayJobs.count { it.job.status in JobStatus.ACTIVE }
    val completedTodayCount = todayJobs.count { it.job.status == JobStatus.COMPLETED }
    val activeStores = stores.filter { !it.store.archived }
    val canManage = RolePermissions.canManageStoresAndJobs(userRole)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = companyName?.ifBlank { lStr(R.string.home_title) } ?: lStr(R.string.home_title),
                                style = MaterialTheme.typography.headlineMedium
                            )
                            RoleBadge(role = userRole)
                        }
                        Text(
                            text = "$prettyDate • ${zoneId.id}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenTeam) {
                        Icon(Icons.Default.Groups, contentDescription = lStr(R.string.team_management))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = lStr(R.string.settings))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            if (activeStores.isNotEmpty() && canManage) {
                ExtendedFloatingActionButton(
                    onClick = onNewJob,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text(lStr(R.string.new_job)) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    StatTile(
                        label = lStr(R.string.today),
                        value = todayActiveCount.toString(),
                        bg = MaterialTheme.colorScheme.primaryContainer,
                        fg = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    StatTile(
                        label = lStr(R.string.overdue),
                        value = overdueJobs.size.toString(),
                        bg = if (overdueJobs.isNotEmpty()) TerracottaContainer else MaterialTheme.colorScheme.surfaceVariant,
                        fg = if (overdueJobs.isNotEmpty()) TerracottaError else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    StatTile(
                        label = lStr(R.string.home_completed_today),
                        value = completedTodayCount.toString(),
                        bg = SuccessContainer,
                        fg = SuccessGreen,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (latestDraft != null && canManage) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = AmberContainer),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.EditNote,
                                contentDescription = null,
                                tint = Color(0xFF92400E),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = lStr(R.string.draft_banner_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color(0xFF451A03)
                                )
                                Text(
                                    text = latestDraft.date.ifBlank { todayIso },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color(0xFF78350F)
                                )
                            }
                            TextButton(onClick = { onDiscardDraft(latestDraft.jobId) }) {
                                Text(lStr(R.string.draft_discard), color = TerracottaError)
                            }
                            Button(
                                onClick = { onResumeDraft(latestDraft) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(lStr(R.string.draft_resume))
                            }
                        }
                    }
                }
            }

            if (activeStores.isEmpty() && canManage) {
                item {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Store,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = lStr(R.string.home_add_first_store),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(14.dp))
                            Button(onClick = onAddStore) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(lStr(R.string.add_store))
                            }
                        }
                    }
                }
            }

            if (overdueJobs.isNotEmpty()) {
                item {
                    Text(
                        text = "${lStr(R.string.home_overdue_jobs)} (${overdueJobs.size})",
                        style = MaterialTheme.typography.titleMedium,
                        color = TerracottaError,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                items(overdueJobs, key = { "overdue_${it.job.id}" }) { row ->
                    JobCard(row = row, zoneId = zoneId, onClick = { onOpenJob(row.job.id) })
                }
            }

            item {
                Text(
                    text = "${lStr(R.string.home_today_jobs)} (${todayJobs.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (todayJobs.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = lStr(R.string.home_no_work_today),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (activeStores.isNotEmpty() && canManage) {
                                OutlinedButton(onClick = onNewJob) {
                                    Icon(Icons.Default.Add, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text(lStr(R.string.new_job))
                                }
                            }
                        }
                    }
                }
            } else {
                items(todayJobs, key = { "today_${it.job.id}" }) { row ->
                    JobCard(row = row, zoneId = zoneId, onClick = { onOpenJob(row.job.id) })
                }
            }
        }
    }
}

@Composable
private fun StatTile(
    label: String,
    value: String,
    bg: Color,
    fg: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = bg
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = fg
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = fg.copy(alpha = 0.9f),
                maxLines = 1
            )
        }
    }
}
