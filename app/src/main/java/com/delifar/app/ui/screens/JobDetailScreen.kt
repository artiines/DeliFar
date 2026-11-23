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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.delifar.app.R
import com.delifar.app.data.JobIssue
import com.delifar.app.data.JobStatus
import com.delifar.app.data.JobWithItems
import com.delifar.app.data.StoreWithHours
import com.delifar.app.domain.BusinessRules
import com.delifar.app.ui.DeliFarViewModel
import com.delifar.app.ui.components.StatusTag
import com.delifar.app.ui.components.issueLabel
import com.delifar.app.ui.components.jobTypeLabel
import com.delifar.app.ui.components.lStr
import com.delifar.app.ui.components.launchDialer
import com.delifar.app.ui.components.launchDirections
import com.delifar.app.ui.components.statusColors
import com.delifar.app.ui.components.statusLabel
import com.delifar.app.ui.theme.AmberAccent
import com.delifar.app.ui.theme.AmberContainer
import com.delifar.app.ui.theme.TerracottaError

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobDetailScreen(
    jobId: String,
    viewModel: DeliFarViewModel,
    stores: List<StoreWithHours>,
    onBack: () -> Unit,
    onEditJob: (String) -> Unit,
    onOpenStore: (String) -> Unit
) {
    val context = LocalContext.current
    val jobWithItems: JobWithItems? by viewModel.observeJob(jobId).collectAsState(initial = null)
    var showRescheduleDialog by remember { mutableStateOf(false) }
    var showIssueDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(lStr(R.string.job_details), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = lStr(R.string.back))
                    }
                },
                actions = {
                    val job = jobWithItems?.job
                    if (job != null && job.status in JobStatus.ACTIVE) {
                        IconButton(onClick = { onEditJob(job.id) }) {
                            Icon(Icons.Default.Edit, contentDescription = lStr(R.string.edit))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { innerPadding ->
        val jwi = jobWithItems
        if (jwi == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        val job = jwi.job
        val liveStore = remember(stores, job.storeId) { stores.find { it.store.id == job.storeId } }
        val isHistorical = job.status == JobStatus.COMPLETED || job.status == JobStatus.CANCELED
        val storeName = if (isHistorical) job.snapshotName else liveStore?.store?.name ?: job.snapshotName
        val storeAddress = if (isHistorical) job.snapshotAddress else liveStore?.store?.address ?: job.snapshotAddress
        val storeLat = if (isHistorical) job.snapshotLat else liveStore?.store?.lat ?: job.snapshotLat
        val storeLon = if (isHistorical) job.snapshotLon else liveStore?.store?.lon ?: job.snapshotLon
        val storePhone = liveStore?.store?.phone.orEmpty()

        val overdue = BusinessRules.isOverdue(job.date, job.status)
        val (badgeBg, badgeFg) = statusColors(job.status, overdue)

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header & Store card
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = jobTypeLabel(job.type),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            StatusTag(
                                text = if (overdue) lStr(R.string.overdue) else statusLabel(job.status),
                                bg = badgeBg,
                                fg = badgeFg
                            )
                        }
                        Text(storeName, style = MaterialTheme.typography.titleLarge)
                        Text(
                            storeAddress,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "${job.date} • ${job.time ?: lStr(R.string.untimed)} (${lStr(R.string.la_time_label)})",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { launchDirections(context, storeLat, storeLon, storeName) }
                            ) {
                                Icon(Icons.Default.Directions, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(lStr(R.string.directions))
                            }
                            if (storePhone.isNotBlank()) {
                                OutlinedButton(onClick = { launchDialer(context, storePhone) }) {
                                    Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(lStr(R.string.call_store))
                                }
                            }
                            if (liveStore != null) {
                                TextButton(onClick = { onOpenStore(liveStore.store.id) }) {
                                    Text(lStr(R.string.store_details))
                                }
                            }
                        }
                    }
                }
            }

            // Issue callout if present
            if (job.issue != null) {
                item {
                    Surface(
                        color = AmberContainer,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, AmberAccent.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = issueLabel(job.issue),
                                style = MaterialTheme.typography.titleMedium,
                                color = AmberAccent
                            )
                            if (job.issueNote.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(job.issueNote, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }

            // Individual reminder switch for timed active jobs
            if (job.time != null && job.status in JobStatus.ACTIVE) {
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(lStr(R.string.job_reminder_switch), style = MaterialTheme.typography.bodyLarge)
                            Switch(
                                checked = job.reminderEnabled,
                                onCheckedChange = { viewModel.setJobReminderEnabled(job.id, it) }
                            )
                        }
                    }
                }
            }

            // Status actions
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (job.status in JobStatus.ACTIVE) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (job.status == JobStatus.PENDING) {
                                OutlinedButton(
                                    onClick = { viewModel.updateJobStatus(job.id, JobStatus.PROGRESS) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(lStr(R.string.action_start))
                                }
                            }
                            Button(
                                onClick = { viewModel.updateJobStatus(job.id, JobStatus.COMPLETED) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(lStr(R.string.action_complete))
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { showRescheduleDialog = true },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(lStr(R.string.action_reschedule))
                            }
                            OutlinedButton(
                                onClick = { showIssueDialog = true },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.ReportProblem, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(lStr(R.string.action_report_issue))
                            }
                        }
                        TextButton(
                            onClick = { viewModel.updateJobStatus(job.id, JobStatus.CANCELED) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(lStr(R.string.action_cancel_job), color = TerracottaError)
                        }
                    } else {
                        Button(
                            onClick = { viewModel.updateJobStatus(job.id, JobStatus.PENDING) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(lStr(R.string.action_reopen))
                        }
                    }
                }
            }

            // Product Lines Card
            item {
                Text(
                    "${lStr(R.string.product_lines)} (${jwi.sortedItems.size})",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            items(jwi.sortedItems, key = { it.id }) { line ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(line.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${lStr(R.string.planned_qty)}: ${line.planned} ${line.unit}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (line.actual != null) {
                            StatusTag(
                                text = "${lStr(R.string.actual_qty)}: ${line.actual} ${line.unit}",
                                bg = AmberContainer,
                                fg = AmberAccent
                            )
                        }
                    }
                }
            }
        }

        if (showRescheduleDialog) {
            var newDate by remember { mutableStateOf(job.date) }
            var newTime by remember { mutableStateOf(job.time ?: "") }
            AlertDialog(
                onDismissRequest = { showRescheduleDialog = false },
                title = { Text(lStr(R.string.action_reschedule)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = newDate,
                            onValueChange = { newDate = it },
                            label = { Text(lStr(R.string.date_label)) },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = newTime,
                            onValueChange = { newTime = it },
                            label = { Text(lStr(R.string.time_label)) },
                            singleLine = true
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.rescheduleJob(job.id, newDate.trim(), newTime.trim().ifEmpty { null })
                            showRescheduleDialog = false
                        }
                    ) { Text(lStr(R.string.save)) }
                },
                dismissButton = {
                    TextButton(onClick = { showRescheduleDialog = false }) { Text(lStr(R.string.cancel)) }
                }
            )
        }

        if (showIssueDialog) {
            var selectedIssue by remember { mutableStateOf(job.issue ?: JobIssue.PARTIAL) }
            var note by remember { mutableStateOf(job.issueNote) }
            var keepOpen by remember { mutableStateOf(true) }
            val actuals = remember(jwi.sortedItems) {
                mutableStateMapOf<String, String>().apply {
                    jwi.sortedItems.forEach { this[it.id] = it.actual ?: it.planned }
                }
            }

            AlertDialog(
                onDismissRequest = { showIssueDialog = false },
                title = { Text(lStr(R.string.action_report_issue)) },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = selectedIssue == JobIssue.PARTIAL,
                                onClick = { selectedIssue = JobIssue.PARTIAL },
                                label = { Text(lStr(R.string.issue_partial)) }
                            )
                            FilterChip(
                                selected = selectedIssue == JobIssue.CLOSED,
                                onClick = { selectedIssue = JobIssue.CLOSED },
                                label = { Text(lStr(R.string.issue_closed)) }
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = selectedIssue == JobIssue.REFUSED,
                                onClick = { selectedIssue = JobIssue.REFUSED },
                                label = { Text(lStr(R.string.issue_refused)) }
                            )
                            FilterChip(
                                selected = selectedIssue == JobIssue.OTHER,
                                onClick = { selectedIssue = JobIssue.OTHER },
                                label = { Text(lStr(R.string.issue_other)) }
                            )
                        }

                        if (selectedIssue == JobIssue.PARTIAL) {
                            HorizontalDivider()
                            jwi.sortedItems.forEach { line ->
                                OutlinedTextField(
                                    value = actuals[line.id] ?: "0",
                                    onValueChange = { actuals[line.id] = it },
                                    label = { Text("${line.name} (${lStr(R.string.planned_qty)} ${line.planned} ${line.unit})") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        OutlinedTextField(
                            value = note,
                            onValueChange = { note = it },
                            label = { Text(lStr(R.string.issue_note_label)) },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = keepOpen, onCheckedChange = { keepOpen = it })
                            Text(lStr(R.string.keep_work_open), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.reportIssue(
                                jobId = job.id,
                                issueType = selectedIssue,
                                note = note,
                                keepOpen = keepOpen,
                                actuals = actuals.toMap(),
                                onDone = { showIssueDialog = false }
                            )
                        }
                    ) { Text(lStr(R.string.save)) }
                },
                dismissButton = {
                    TextButton(onClick = { showIssueDialog = false }) { Text(lStr(R.string.cancel)) }
                }
            )
        }
    }
}
