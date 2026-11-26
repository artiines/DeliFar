package com.delifar.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.delifar.app.R
import com.delifar.app.cloud.FirebaseServices
import com.delifar.app.data.DraftEntity
import com.delifar.app.data.JobItemEntity
import com.delifar.app.data.JobListRow
import com.delifar.app.data.JobStatus
import com.delifar.app.data.StoreWithHours
import com.delifar.app.domain.BusinessRules
import com.delifar.app.domain.RolePermissions
import com.delifar.app.ui.components.JobCard
import com.delifar.app.ui.components.LocalAppLocale
import com.delifar.app.ui.components.ReceivingWindowStatusTag
import com.delifar.app.ui.components.ReportIssueDialog
import com.delifar.app.ui.components.RoleBadge
import com.delifar.app.ui.components.StatusTag
import com.delifar.app.ui.components.jobTypeLabel
import com.delifar.app.ui.components.lStr
import com.delifar.app.ui.components.launchDialer
import com.delifar.app.ui.components.launchDirections
import com.delifar.app.ui.components.statusColors
import com.delifar.app.ui.components.statusLabel
import com.delifar.app.ui.components.todayHoursSummary
import com.delifar.app.ui.theme.AmberAccent
import com.delifar.app.ui.theme.AmberContainer
import com.delifar.app.ui.theme.SuccessContainer
import com.delifar.app.ui.theme.SuccessGreen
import com.delifar.app.ui.theme.TerracottaContainer
import com.delifar.app.ui.theme.TerracottaError
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    stores: List<StoreWithHours>,
    jobRows: List<JobListRow>,
    latestDraft: DraftEntity?,
    zoneId: ZoneId,
    userRole: String,
    companyName: String?,
    hasAccount: Boolean,
    onboardingDismissed: Boolean,
    offlineCompaniesToClaimCount: Int = 0,
    onOpenSettings: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenTeam: () -> Unit,
    onNewJob: () -> Unit,
    onResumeDraft: (DraftEntity) -> Unit,
    onDiscardDraft: (String) -> Unit,
    onOpenJob: (String) -> Unit,
    onAddStore: () -> Unit,
    onQuickStartJob: (String) -> Unit,
    onQuickCompleteJob: (String) -> Unit,
    onAddInvoiceForJob: (String, String) -> Unit,
    onLoadJobItems: suspend (String) -> List<JobItemEntity> = { emptyList() },
    onReportIssue: (jobId: String, issueType: String, note: String, keepOpen: Boolean, actuals: Map<String, String>, onDone: () -> Unit) -> Unit = { _, _, _, _, _, onDone -> onDone() },
    onDismissOnboarding: () -> Unit,
    onNotify: (String) -> Unit
) {
    val context = LocalContext.current
    val appLocale = LocalAppLocale.current
    val today = remember(zoneId) { BusinessRules.todayInZone(zoneId) }
    val todayIso = today.toString()
    val prettyDate = remember(today, appLocale) {
        today.format(DateTimeFormatter.ofPattern("EEE, MMM d", appLocale))
    }

    val storesById = remember(stores) { stores.associateBy { it.store.id } }
    val activeStores = remember(stores) { stores.filter { !it.store.archived } }
    val overdueJobs = remember(jobRows, today) {
        BusinessRules.sortJobsForDay(jobRows.filter { BusinessRules.isOverdue(it.job.date, it.job.status, today) })
    }
    val todayJobs = remember(jobRows, todayIso) {
        BusinessRules.sortJobsForDay(jobRows.filter { it.job.date == todayIso })
    }
    val activeTodayJobs = remember(todayJobs) { todayJobs.filter { it.job.status in JobStatus.ACTIVE } }
    val completedTodayCount = remember(todayJobs) { todayJobs.count { it.job.status == JobStatus.COMPLETED } }
    val nextStop: JobListRow? = remember(activeTodayJobs, overdueJobs) {
        activeTodayJobs.firstOrNull { it.job.status == JobStatus.PROGRESS }
            ?: activeTodayJobs.firstOrNull()
            ?: overdueJobs.firstOrNull()
    }

    var quickIssueTarget by remember { mutableStateOf<JobListRow?>(null) }
    val quickIssueItems by produceState<List<JobItemEntity>>(initialValue = emptyList(), key1 = quickIssueTarget?.job?.id) {
        val targetId = quickIssueTarget?.job?.id
        value = if (targetId != null) onLoadJobItems(targetId) else emptyList()
    }

    val todayActiveCount = activeTodayJobs.size
    val canManage = RolePermissions.canManageStoresAndJobs(userRole)
    val canUpdateJobs = RolePermissions.canUpdateAssignedJobs(userRole)
    val canUploadInvoices = RolePermissions.canUploadAndSignInvoices(userRole)
    val invalidCoordMsg = lStr(R.string.invalid_store_coordinates)

    val setupComplete = if (canManage) {
        activeStores.isNotEmpty() && jobRows.isNotEmpty()
    } else {
        !companyName.isNullOrBlank() || jobRows.isNotEmpty()
    }
    val showSetupChecklist = !onboardingDismissed && !setupComplete

    fun navigateToRow(row: JobListRow) {
        val store = storesById[row.job.storeId]?.store
        val lat = store?.lat ?: row.job.snapshotLat
        val lon = store?.lon ?: row.job.snapshotLon
        val launched = launchDirections(context, lat, lon, row.displayStoreName, row.displayStoreAddress)
        if (!launched) onNotify(invalidCoordMsg)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = companyName?.ifBlank { lStr(R.string.home_title) } ?: lStr(R.string.home_title),
                                style = MaterialTheme.typography.headlineMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
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
            // Daily operational counters + Smart Distributor Shift Progress
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    if (todayJobs.isNotEmpty()) {
                        val progress = (completedTodayCount.toFloat() / todayJobs.size.toFloat()).coerceIn(0f, 1f)
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = lStr(R.string.shift_progress_label, completedTodayCount, todayJobs.size),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }

            // Explicit Offline-to-Cloud Company Claiming Prompt on Home when signed in
            if (offlineCompaniesToClaimCount > 0) {
                item {
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = AmberContainer),
                        border = BorderStroke(1.dp, AmberAccent.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onOpenAccount)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = lStr(R.string.claim_offline_home_banner, offlineCompaniesToClaimCount),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF451A03),
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = onOpenAccount,
                                modifier = Modifier.defaultMinSize(minHeight = 44.dp)
                            ) {
                                Text(lStr(R.string.claim_offline_company_action))
                            }
                        }
                    }
                }
            }

            // Guided First-Time Setup Checklist
            if (showSetupChecklist) {
                item {
                    QuickSetupCard(
                        hasAccount = hasAccount || !FirebaseServices.isConfigured,
                        hasCompany = !companyName.isNullOrBlank(),
                        hasStore = activeStores.isNotEmpty(),
                        hasDelivery = jobRows.isNotEmpty(),
                        canManage = canManage,
                        onOpenAccount = onOpenAccount,
                        onAddStore = onAddStore,
                        onNewJob = onNewJob,
                        onDismiss = onDismissOnboarding
                    )
                }
            }

            // Unsaved Job Draft Recovery Banner
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

            // Driver Priority Spotlight: Next Scheduled Stop with One-Tap Actions
            if (nextStop != null) {
                item {
                    val stopStore = storesById[nextStop.job.storeId]
                    val hoursText = stopStore?.let { todayHoursSummary(it.hours, zoneId) }
                    val phone = stopStore?.store?.phone.orEmpty()
                    val isOverdue = BusinessRules.isOverdue(nextStop.job.date, nextStop.job.status, today)
                    NextStopSpotlightCard(
                        row = nextStop,
                        receivingHoursToday = hoursText,
                        storeHours = stopStore?.hours.orEmpty(),
                        zoneId = zoneId,
                        isOverdue = isOverdue,
                        canUpdateStatus = canUpdateJobs,
                        canUploadInvoice = canUploadInvoices,
                        hasPhone = phone.isNotBlank(),
                        onOpenJob = { onOpenJob(nextStop.job.id) },
                        onNavigate = { navigateToRow(nextStop) },
                        onCall = { launchDialer(context, phone) },
                        onAdvanceStatus = {
                            if (nextStop.job.status == JobStatus.PROGRESS) {
                                onQuickCompleteJob(nextStop.job.id)
                            } else {
                                onQuickStartJob(nextStop.job.id)
                            }
                        },
                        onInvoice = { onAddInvoiceForJob(nextStop.job.id, nextStop.job.storeId) },
                        onQuickIssue = { quickIssueTarget = nextStop }
                    )
                }
            }

            // Empty store prompt (if onboarding dismissed)
            if (activeStores.isEmpty() && canManage && onboardingDismissed) {
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

            // Overdue jobs
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
                    val storeWithHours = storesById[row.job.storeId]
                    val phone = storeWithHours?.store?.phone.orEmpty()
                    JobCard(
                        row = row,
                        zoneId = zoneId,
                        onClick = { onOpenJob(row.job.id) },
                        receivingHoursToday = storeWithHours?.let { todayHoursSummary(it.hours, zoneId) },
                        storeHours = storeWithHours?.hours.orEmpty(),
                        onQuickNavigate = { navigateToRow(row) },
                        onQuickCall = if (phone.isNotBlank()) ({ launchDialer(context, phone) }) else null,
                        onQuickAdvanceStatus = if (canUpdateJobs) {
                            {
                                if (row.job.status == JobStatus.PROGRESS) onQuickCompleteJob(row.job.id)
                                else onQuickStartJob(row.job.id)
                            }
                        } else null,
                        onQuickInvoice = if (canUploadInvoices) {
                            { onAddInvoiceForJob(row.job.id, row.job.storeId) }
                        } else null
                    )
                }
            }

            // Today's jobs
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
                    val storeWithHours = storesById[row.job.storeId]
                    val phone = storeWithHours?.store?.phone.orEmpty()
                    JobCard(
                        row = row,
                        zoneId = zoneId,
                        onClick = { onOpenJob(row.job.id) },
                        receivingHoursToday = storeWithHours?.let { todayHoursSummary(it.hours, zoneId) },
                        storeHours = storeWithHours?.hours.orEmpty(),
                        onQuickNavigate = { navigateToRow(row) },
                        onQuickCall = if (phone.isNotBlank()) ({ launchDialer(context, phone) }) else null,
                        onQuickAdvanceStatus = if (canUpdateJobs) {
                            {
                                if (row.job.status == JobStatus.PROGRESS) onQuickCompleteJob(row.job.id)
                                else onQuickStartJob(row.job.id)
                            }
                        } else null,
                        onQuickInvoice = if (canUploadInvoices) {
                            { onAddInvoiceForJob(row.job.id, row.job.storeId) }
                        } else null
                    )
                }
            }
        }

        quickIssueTarget?.let { target ->
            ReportIssueDialog(
                items = quickIssueItems,
                initialIssue = target.job.issue,
                initialNote = target.job.issueNote,
                onDismiss = { quickIssueTarget = null },
                onSubmit = { issueType, note, keepOpen, actuals ->
                    onReportIssue(target.job.id, issueType, note, keepOpen, actuals) {
                        quickIssueTarget = null
                    }
                }
            )
        }
    }
}

@Composable
private fun QuickSetupCard(
    hasAccount: Boolean,
    hasCompany: Boolean,
    hasStore: Boolean,
    hasDelivery: Boolean,
    canManage: Boolean,
    onOpenAccount: () -> Unit,
    onAddStore: () -> Unit,
    onNewJob: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = lStr(R.string.setup_checklist_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = lStr(R.string.setup_checklist_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.defaultMinSize(minHeight = 44.dp)
                ) {
                    Text(lStr(R.string.setup_dismiss))
                }
            }

            SetupChecklistRow(
                title = lStr(R.string.setup_step_account),
                done = hasAccount,
                onClick = onOpenAccount
            )
            if (canManage) {
                SetupChecklistRow(
                    title = lStr(R.string.setup_step_company),
                    done = hasCompany,
                    onClick = onOpenAccount
                )
                SetupChecklistRow(
                    title = lStr(R.string.setup_step_store),
                    done = hasStore,
                    onClick = onAddStore
                )
                SetupChecklistRow(
                    title = lStr(R.string.setup_step_delivery),
                    done = hasDelivery,
                    enabled = hasStore,
                    onClick = onNewJob
                )
            } else {
                SetupChecklistRow(
                    title = lStr(R.string.setup_step_join_team),
                    done = hasCompany,
                    onClick = onOpenAccount
                )
            }
        }
    }
}

@Composable
private fun SetupChecklistRow(
    title: String,
    done: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = if (done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            tint = when {
                done -> SuccessGreen
                enabled -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            },
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (done) FontWeight.Normal else FontWeight.Medium
        )
    }
}

@Composable
private fun NextStopSpotlightCard(
    row: JobListRow,
    receivingHoursToday: String?,
    storeHours: List<com.delifar.app.data.StoreHoursEntity>,
    zoneId: ZoneId,
    isOverdue: Boolean,
    canUpdateStatus: Boolean,
    canUploadInvoice: Boolean,
    hasPhone: Boolean,
    onOpenJob: () -> Unit,
    onNavigate: () -> Unit,
    onCall: () -> Unit,
    onAdvanceStatus: () -> Unit,
    onInvoice: () -> Unit,
    onQuickIssue: () -> Unit
) {
    val (badgeBg, badgeFg) = statusColors(row.job.status, isOverdue)
    val isInProgress = row.job.status == JobStatus.PROGRESS

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenJob),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)),
        border = BorderStroke(
            width = 1.5.dp,
            color = if (isOverdue) TerracottaError else MaterialTheme.colorScheme.primary
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = lStr(R.string.next_stop_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isOverdue) TerracottaError else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (storeHours.isNotEmpty()) {
                        ReceivingWindowStatusTag(hours = storeHours, zoneId = zoneId)
                    }
                    StatusTag(
                        text = if (isOverdue) lStr(R.string.overdue) else statusLabel(row.job.status),
                        bg = badgeBg,
                        fg = badgeFg
                    )
                }
            }

            Text(
                text = row.displayStoreName,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = row.displayStoreAddress,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${jobTypeLabel(row.job.type)} • ${row.job.date} • ${row.job.time ?: lStr(R.string.untimed)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!receivingHoursToday.isNullOrBlank()) {
                    Text(
                        text = receivingHoursToday,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Primary one-handed thumb actions row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onNavigate,
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                ) {
                    Icon(Icons.Default.Directions, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(lStr(R.string.directions), maxLines = 1)
                }
                if (canUpdateStatus) {
                    Button(
                        onClick = onAdvanceStatus,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isInProgress) SuccessGreen else MaterialTheme.colorScheme.secondary
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                    ) {
                        Icon(
                            imageVector = if (isInProgress) Icons.Default.CheckCircle else Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (isInProgress) lStr(R.string.action_complete) else lStr(R.string.action_start),
                            maxLines = 1
                        )
                    }
                }
            }

            // Secondary one-handed driver actions row (Call, Invoice/Sign, 1-tap Issue)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (hasPhone) {
                    OutlinedButton(
                        onClick = onCall,
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 44.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(lStr(R.string.call_store), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (canUploadInvoice) {
                    OutlinedButton(
                        onClick = onInvoice,
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 44.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(lStr(R.string.quick_invoice_action), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                OutlinedButton(
                    onClick = onQuickIssue,
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 44.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = AmberAccent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(lStr(R.string.quick_issue_action), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
