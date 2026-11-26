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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.delifar.app.R
import com.delifar.app.cloud.InvoiceSyncValidator
import com.delifar.app.data.InvoiceStatus
import com.delifar.app.data.InvoiceWithPages
import com.delifar.app.data.JobStatus
import com.delifar.app.data.JobWithItems
import com.delifar.app.data.StoreWithHours
import com.delifar.app.data.SyncState
import com.delifar.app.documents.SignedPdfGenerator
import com.delifar.app.domain.BusinessRules
import com.delifar.app.domain.RolePermissions
import com.delifar.app.ui.DeliFarViewModel
import com.delifar.app.ui.components.ReceivingWindowStatusTag
import com.delifar.app.ui.components.ReportIssueDialog
import com.delifar.app.ui.components.StatusTag
import com.delifar.app.ui.components.invoiceStatusColors
import com.delifar.app.ui.components.issueLabel
import com.delifar.app.ui.components.jobTypeLabel
import com.delifar.app.ui.components.lStr
import com.delifar.app.ui.components.launchDialer
import com.delifar.app.ui.components.launchDirections
import com.delifar.app.ui.components.statusColors
import com.delifar.app.ui.components.statusLabel
import com.delifar.app.ui.components.todayHoursSummary
import com.delifar.app.ui.theme.AmberAccent
import com.delifar.app.ui.theme.AmberContainer
import com.delifar.app.ui.theme.InfoBlue
import com.delifar.app.ui.theme.InfoContainer
import com.delifar.app.ui.theme.SuccessContainer
import com.delifar.app.ui.theme.SuccessGreen
import com.delifar.app.ui.theme.TerracottaContainer
import com.delifar.app.ui.theme.TerracottaError
import java.math.BigDecimal

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobDetailScreen(
    jobId: String,
    viewModel: DeliFarViewModel,
    stores: List<StoreWithHours>,
    onBack: () -> Unit,
    onEditJob: (String) -> Unit,
    onOpenStore: (String) -> Unit,
    onAddInvoice: (jobId: String, storeId: String) -> Unit,
    onSignInvoice: (invoiceId: String) -> Unit,
    onOpenInvoices: () -> Unit
) {
    val context = LocalContext.current
    val prefs by viewModel.preferences.collectAsState()
    val employees by viewModel.employees.collectAsState()
    val syncState by viewModel.syncState.collectAsState()
    val invoiceAttachmentIssues by viewModel.invoiceAttachmentIssues.collectAsState()
    val zoneId = remember(prefs.companyTimezone) { BusinessRules.resolveZone(prefs.companyTimezone) }
    val today = remember(zoneId) { BusinessRules.todayInZone(zoneId) }

    val jobWithItems: JobWithItems? by viewModel.observeJob(jobId).collectAsState(initial = null)
    val jobInvoices: List<InvoiceWithPages> by viewModel.observeInvoicesForJob(jobId).collectAsState(initial = emptyList())
    val invalidCoordMsg = lStr(R.string.invalid_store_coordinates)

    var showRescheduleDialog by remember { mutableStateOf(false) }
    var showIssueDialog by remember { mutableStateOf(false) }
    var driverDropdownOpen by remember { mutableStateOf(false) }

    val canManage = RolePermissions.canManageStoresAndJobs(prefs.activeUserRole)
    val canUpdate = RolePermissions.canUpdateAssignedJobs(prefs.activeUserRole)
    val canUploadAndSign = RolePermissions.canUploadAndSignInvoices(prefs.activeUserRole)

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
                    if (job != null && job.status in JobStatus.ACTIVE && canManage) {
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

        val overdue = BusinessRules.isOverdue(job.date, job.status, today)
        val (badgeBg, badgeFg) = statusColors(job.status, overdue)
        val assignableEmployees = remember(employees) { employees.filter { it.active } }

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
                            "${job.date} • ${job.time ?: lStr(R.string.untimed)} (${zoneId.id})",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        if (liveStore != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${lStr(R.string.receiving_hours)}: ${todayHoursSummary(liveStore.hours, zoneId)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.weight(1f)
                                )
                                ReceivingWindowStatusTag(hours = liveStore.hours, zoneId = zoneId)
                            }
                        }
                        if (job.notes.isNotBlank()) {
                            Text(
                                text = job.notes,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Driver Assignment
                        if (RolePermissions.canAssignDrivers(prefs.activeUserRole) && assignableEmployees.isNotEmpty()) {
                            ExposedDropdownMenuBox(
                                expanded = driverDropdownOpen,
                                onExpandedChange = { driverDropdownOpen = it }
                            ) {
                                OutlinedTextField(
                                    value = job.assignedDriverName.ifBlank { lStr(R.string.unassigned_driver) },
                                    onValueChange = {},
                                    readOnly = true,
                                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                                    label = { Text(lStr(R.string.assign_driver)) },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = driverDropdownOpen) },
                                    modifier = Modifier
                                        .menuAnchor()
                                        .fillMaxWidth()
                                )
                                ExposedDropdownMenu(
                                    expanded = driverDropdownOpen,
                                    onDismissRequest = { driverDropdownOpen = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(lStr(R.string.unassigned_driver)) },
                                        onClick = {
                                            viewModel.assignDriverToJob(job.id, null, "")
                                            driverDropdownOpen = false
                                        }
                                    )
                                    assignableEmployees.forEach { emp ->
                                        DropdownMenuItem(
                                            text = { Text("${emp.displayName} (${emp.role})") },
                                            onClick = {
                                                viewModel.assignDriverToJob(job.id, emp.uid, emp.displayName)
                                                driverDropdownOpen = false
                                            }
                                        )
                                    }
                                }
                            }
                        } else if (job.assignedDriverName.isNotBlank()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "${lStr(R.string.assign_driver)}: ${job.assignedDriverName}",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    val ok = launchDirections(context, storeLat, storeLon, storeName, storeAddress)
                                    if (!ok) viewModel.notifyMessage(invalidCoordMsg)
                                },
                                modifier = Modifier.defaultMinSize(minHeight = 44.dp)
                            ) {
                                Icon(Icons.Default.Directions, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(lStr(R.string.directions))
                            }
                            if (storePhone.isNotBlank()) {
                                OutlinedButton(
                                    onClick = { launchDialer(context, storePhone) },
                                    modifier = Modifier.defaultMinSize(minHeight = 44.dp)
                                ) {
                                    Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(lStr(R.string.call_store))
                                }
                            }
                            if (liveStore != null) {
                                TextButton(
                                    onClick = { onOpenStore(liveStore.store.id) },
                                    modifier = Modifier.defaultMinSize(minHeight = 44.dp)
                                ) {
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

            // Invoices & Electronic Signatures Section
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "${lStr(R.string.invoices_title)} (${jobInvoices.size})",
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                            if (canUploadAndSign) {
                                OutlinedButton(onClick = { onAddInvoice(job.id, job.storeId) }) {
                                    Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(lStr(R.string.add_invoice))
                                }
                            }
                        }

                        if (jobInvoices.isEmpty()) {
                            Text(
                                text = lStr(R.string.no_invoices_for_job),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            jobInvoices.forEach { iwp ->
                                val inv = iwp.invoice
                                val isUploadingNow = syncState.currentlyUploadingInvoiceId == inv.id
                                val hasAllRemote = remember(iwp) { InvoiceSyncValidator.hasAllRemoteAttachments(iwp) }
                                val localIssues = invoiceAttachmentIssues[inv.id].orEmpty()
                                val (displayStatusText, stBg, stFg) = when {
                                    isUploadingNow -> Triple(lStr(R.string.invoice_status_uploading), InfoContainer, InfoBlue)
                                    inv.status == InvoiceStatus.UPLOAD_FAILED || inv.syncState == SyncState.FAILED ->
                                        Triple(lStr(R.string.invoice_status_upload_failed), TerracottaContainer, TerracottaError)
                                    inv.status == InvoiceStatus.VOIDED ->
                                        Triple(lStr(R.string.invoice_status_voided), invoiceStatusColors(inv.status).first, invoiceStatusColors(inv.status).second)
                                    inv.signed && inv.status == InvoiceStatus.SYNCHRONIZED && inv.syncState == SyncState.SYNCED && hasAllRemote ->
                                        Triple(lStr(R.string.invoice_status_signed_synced), SuccessContainer, SuccessGreen)
                                    inv.status == InvoiceStatus.SYNCHRONIZED && inv.syncState == SyncState.SYNCED && hasAllRemote ->
                                        Triple(lStr(R.string.invoice_status_uploaded), SuccessContainer, SuccessGreen)
                                    inv.companyId.isNotBlank() && (inv.status == InvoiceStatus.PENDING_UPLOAD || inv.syncState == SyncState.PENDING) ->
                                        Triple(lStr(R.string.invoice_status_pending_upload), InfoContainer, InfoBlue)
                                    else ->
                                        Triple(lStr(R.string.invoice_status_saved_locally), invoiceStatusColors(inv.status).first, invoiceStatusColors(inv.status).second)
                                }
                                val needsRetry = !isUploadingNow && inv.companyId.isNotBlank() && (
                                    inv.status == InvoiceStatus.UPLOAD_FAILED ||
                                        inv.syncState == SyncState.FAILED ||
                                        inv.status == InvoiceStatus.PENDING_UPLOAD ||
                                        !inv.uploadError.isNullOrBlank()
                                    )

                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(onClick = onOpenInvoices)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "#${inv.invoiceNumber} (v${inv.version})",
                                                    style = MaterialTheme.typography.titleMedium
                                                )
                                                Text(
                                                    text = if (inv.signed) lStr(R.string.invoice_signed_by, inv.recipientName)
                                                    else lStr(R.string.invoice_pages_count, iwp.pages.size),
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            StatusTag(text = displayStatusText, bg = stBg, fg = stFg)
                                        }

                                        val errDetail = inv.uploadError?.takeIf { it.isNotBlank() } ?: localIssues.firstOrNull()
                                        if (errDetail != null && (inv.status == InvoiceStatus.UPLOAD_FAILED || inv.syncState == SyncState.FAILED || localIssues.isNotEmpty())) {
                                            Text(
                                                text = errDetail,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = TerracottaError
                                            )
                                        }

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            if (!inv.signed && inv.status != InvoiceStatus.VOIDED && canUploadAndSign) {
                                                Button(
                                                    onClick = { onSignInvoice(inv.id) },
                                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .defaultMinSize(minHeight = 44.dp)
                                                ) {
                                                    Icon(Icons.Default.Draw, contentDescription = null, modifier = Modifier.size(14.dp))
                                                    Spacer(Modifier.width(4.dp))
                                                    Text(lStr(R.string.collect_signature), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                                                }
                                            }
                                            if (needsRetry) {
                                                OutlinedButton(
                                                    onClick = { viewModel.retryInvoiceUpload(inv.id) },
                                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                    modifier = Modifier.defaultMinSize(minHeight = 44.dp)
                                                ) {
                                                    Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(14.dp))
                                                    Spacer(Modifier.width(4.dp))
                                                    Text(lStr(R.string.retry_upload), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                                                }
                                            }
                                            val pdfPath = inv.signedPdfLocalPath
                                            if (!pdfPath.isNullOrBlank()) {
                                                OutlinedButton(
                                                    onClick = { SignedPdfGenerator.openFile(context, pdfPath, "application/pdf") },
                                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                    modifier = Modifier.defaultMinSize(minHeight = 44.dp)
                                                ) {
                                                    Icon(Icons.Default.PictureAsPdf, contentDescription = lStr(R.string.view_signed_pdf), modifier = Modifier.size(16.dp))
                                                }
                                                OutlinedButton(
                                                    onClick = { SignedPdfGenerator.printPdf(context, pdfPath, "DeliFar-${inv.invoiceNumber}") },
                                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                    modifier = Modifier.defaultMinSize(minHeight = 44.dp)
                                                ) {
                                                    Icon(Icons.Default.Print, contentDescription = lStr(R.string.print_pdf), modifier = Modifier.size(16.dp))
                                                }
                                                val sharePdfTitle = lStr(R.string.share_pdf)
                                                OutlinedButton(
                                                    onClick = { SignedPdfGenerator.shareFile(context, pdfPath, "application/pdf", sharePdfTitle) },
                                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                    modifier = Modifier.defaultMinSize(minHeight = 44.dp)
                                                ) {
                                                    Icon(Icons.Default.Share, contentDescription = sharePdfTitle, modifier = Modifier.size(16.dp))
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

            // Individual reminder switch
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
            if (canUpdate) {
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
                                if (canManage) {
                                    OutlinedButton(
                                        onClick = { showRescheduleDialog = true },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(lStr(R.string.action_reschedule))
                                    }
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
                            if (canManage) {
                                TextButton(
                                    onClick = { viewModel.updateJobStatus(job.id, JobStatus.CANCELED) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(lStr(R.string.action_cancel_job), color = TerracottaError)
                                }
                            }
                        } else if (canManage) {
                            Button(
                                onClick = { viewModel.updateJobStatus(job.id, JobStatus.PENDING) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(lStr(R.string.action_reopen))
                            }
                        }
                    }
                }
            }

            // Product Lines
            item {
                Text(
                    "${lStr(R.string.product_lines)} (${jwi.sortedItems.size})",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            items(jwi.sortedItems, key = { it.id }) { line ->
                val varianceLabel = remember(line.planned, line.actual) {
                    val actualStr = line.actual ?: return@remember null
                    runCatching {
                        val diff = BigDecimal(actualStr).subtract(BigDecimal(line.planned)).stripTrailingZeros()
                        val sign = if (diff.signum() > 0) "+" else ""
                        "$sign${diff.toPlainString()} ${line.unit}"
                    }.getOrNull()
                }
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
                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                StatusTag(
                                    text = "${lStr(R.string.actual_qty)}: ${line.actual} ${line.unit}",
                                    bg = AmberContainer,
                                    fg = AmberAccent
                                )
                                if (varianceLabel != null) {
                                    Text(
                                        text = "${lStr(R.string.variance_prefix)} $varianceLabel",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TerracottaError,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
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
            ReportIssueDialog(
                items = jwi.sortedItems,
                initialIssue = job.issue,
                initialNote = job.issueNote,
                onDismiss = { showIssueDialog = false },
                onSubmit = { issueType, note, keepOpen, actuals ->
                    viewModel.reportIssue(
                        jobId = job.id,
                        issueType = issueType,
                        note = note,
                        keepOpen = keepOpen,
                        actuals = actuals,
                        onDone = { showIssueDialog = false }
                    )
                }
            )
        }
    }
}
