package com.delifar.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.delifar.app.R
import com.delifar.app.cloud.InvoiceSyncValidator
import com.delifar.app.data.InvoiceStatus
import com.delifar.app.data.InvoiceWithPages
import com.delifar.app.data.SyncState
import com.delifar.app.documents.SignedPdfGenerator
import com.delifar.app.domain.BusinessRules
import com.delifar.app.domain.RolePermissions
import com.delifar.app.ui.DeliFarViewModel
import com.delifar.app.ui.components.StatusTag
import com.delifar.app.ui.components.invoiceStatusColors
import com.delifar.app.ui.components.invoiceStatusLabel
import com.delifar.app.ui.components.lStr
import com.delifar.app.ui.theme.InfoBlue
import com.delifar.app.ui.theme.InfoContainer
import com.delifar.app.ui.theme.SuccessContainer
import com.delifar.app.ui.theme.SuccessGreen
import com.delifar.app.ui.theme.TerracottaContainer
import com.delifar.app.ui.theme.TerracottaError
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvoicesScreen(
    invoices: List<InvoiceWithPages>,
    viewModel: DeliFarViewModel,
    onAddInvoice: () -> Unit,
    onSignInvoice: (String) -> Unit
) {
    val context = LocalContext.current
    val prefs by viewModel.preferences.collectAsState()
    val syncState by viewModel.syncState.collectAsState()
    val invoiceAttachmentIssues by viewModel.invoiceAttachmentIssues.collectAsState()
    val zoneId = remember(prefs.companyTimezone) { BusinessRules.resolveZone(prefs.companyTimezone) }
    val scope = rememberCoroutineScope()

    val canUpload = RolePermissions.canUploadAndSignInvoices(prefs.activeUserRole)
    val canExport = RolePermissions.canExportInvoices(prefs.activeUserRole)
    val canVoidOrCorrect = RolePermissions.canVoidOrCorrectInvoices(prefs.activeUserRole)

    var query by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf("all") }
    var selectedInvoice by remember { mutableStateOf<InvoiceWithPages?>(null) }
    var voidTarget by remember { mutableStateOf<InvoiceWithPages?>(null) }
    var correctTarget by remember { mutableStateOf<InvoiceWithPages?>(null) }

    val filtered = remember(invoices, query, statusFilter) {
        val q = query.trim().lowercase()
        invoices.filter { iwp ->
            val inv = iwp.invoice
            val matchesStatus = when (statusFilter) {
                "all" -> true
                "signed" -> inv.signed && inv.status != InvoiceStatus.VOIDED
                "unsigned" -> !inv.signed && inv.status != InvoiceStatus.VOIDED
                else -> inv.status == statusFilter
            }
            val matchesQuery = q.isEmpty() ||
                inv.invoiceNumber.lowercase().contains(q) ||
                inv.storeName.lowercase().contains(q) ||
                inv.recipientName.lowercase().contains(q) ||
                inv.createdByName.lowercase().contains(q)
            matchesStatus && matchesQuery
        }
    }

    val csvExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) viewModel.exportInvoicesCsvToUri(uri, filtered)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(lStr(R.string.invoices_title), style = MaterialTheme.typography.titleLarge) },
                actions = {
                    if (canExport && filtered.isNotEmpty()) {
                        IconButton(onClick = { csvExportLauncher.launch("delifar-invoices-${BusinessRules.todayIso(zoneId)}.csv") }) {
                            Icon(Icons.Default.Download, contentDescription = lStr(R.string.export_invoices_csv))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        floatingActionButton = {
            if (canUpload) {
                ExtendedFloatingActionButton(
                    onClick = onAddInvoice,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text(lStr(R.string.add_invoice)) },
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
                placeholder = { Text(lStr(R.string.search_invoices_hint)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )

            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(selected = statusFilter == "all", onClick = { statusFilter = "all" }, label = { Text(lStr(R.string.all)) })
                }
                item {
                    FilterChip(selected = statusFilter == "unsigned", onClick = { statusFilter = "unsigned" }, label = { Text(lStr(R.string.invoice_status_not_signed)) })
                }
                item {
                    FilterChip(selected = statusFilter == "signed", onClick = { statusFilter = "signed" }, label = { Text(lStr(R.string.invoice_status_signed)) })
                }
                item {
                    FilterChip(selected = statusFilter == InvoiceStatus.PENDING_UPLOAD, onClick = { statusFilter = InvoiceStatus.PENDING_UPLOAD }, label = { Text(lStr(R.string.invoice_status_pending_upload)) })
                }
                item {
                    FilterChip(selected = statusFilter == InvoiceStatus.UPLOAD_FAILED, onClick = { statusFilter = InvoiceStatus.UPLOAD_FAILED }, label = { Text(lStr(R.string.invoice_status_upload_failed)) })
                }
                item {
                    FilterChip(selected = statusFilter == InvoiceStatus.VOIDED, onClick = { statusFilter = InvoiceStatus.VOIDED }, label = { Text(lStr(R.string.invoice_status_voided)) })
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            text = lStr(R.string.no_invoices_yet),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }
                } else {
                    items(filtered, key = { it.invoice.id }) { iwp ->
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

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedInvoice = iwp },
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(
                                1.dp,
                                if (inv.status == InvoiceStatus.UPLOAD_FAILED || inv.syncState == SyncState.FAILED) {
                                    TerracottaError.copy(alpha = 0.5f)
                                } else {
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                                }
                            )
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("#${inv.invoiceNumber} (v${inv.version})", style = MaterialTheme.typography.titleMedium)
                                    StatusTag(text = displayStatusText, bg = stBg, fg = stFg)
                                }
                                Text(inv.storeName, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = "${BusinessRules.formatTimestamp(inv.createdAt, zoneId)} • ${lStr(R.string.invoice_pages_count, iwp.pages.size)} • ${inv.createdByName}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (inv.signed) {
                                    val sigScopeLabel = if (inv.signedServerAt != null) {
                                        lStr(R.string.invoice_server_verified)
                                    } else {
                                        lStr(R.string.invoice_local_capture)
                                    }
                                    Text(
                                        text = "${lStr(R.string.invoice_signed_by, inv.recipientName)} • $sigScopeLabel",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                val failureDetail = inv.uploadError?.takeIf { it.isNotBlank() }
                                    ?: localIssues.firstOrNull()
                                if (failureDetail != null && (inv.status == InvoiceStatus.UPLOAD_FAILED || inv.syncState == SyncState.FAILED || localIssues.isNotEmpty())) {
                                    Text(
                                        text = failureDetail,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TerracottaError
                                    )
                                }
                                if (needsRetry) {
                                    OutlinedButton(
                                        onClick = { viewModel.retryInvoiceUpload(inv.id) },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                        modifier = Modifier.defaultMinSize(minHeight = 44.dp)
                                    ) {
                                        Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(lStr(R.string.retry_upload))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Invoice Detail & Actions Modal
    selectedInvoice?.let { iwp ->
        val inv = iwp.invoice
        val detailIssues = invoiceAttachmentIssues[inv.id].orEmpty()
        AlertDialog(
            onDismissRequest = { selectedInvoice = null },
            title = { Text(lStr(R.string.invoice_detail_title, inv.invoiceNumber, inv.version)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(lStr(R.string.invoice_store_label, inv.storeName), style = MaterialTheme.typography.bodyMedium)
                    Text(lStr(R.string.invoice_status_field, invoiceStatusLabel(inv.status)), style = MaterialTheme.typography.bodyMedium)
                    Text(lStr(R.string.invoice_uploaded_by, inv.createdByName), style = MaterialTheme.typography.bodySmall)
                    if (inv.notes.isNotBlank()) {
                        Text(lStr(R.string.invoice_notes_field, inv.notes), style = MaterialTheme.typography.bodySmall)
                    }
                    if (inv.signed) {
                        Text(
                            lStr(R.string.invoice_recipient_field, "${inv.recipientName} ${inv.recipientTitle}".trim()),
                            style = MaterialTheme.typography.bodySmall
                        )
                        inv.signedLocalAt?.let {
                            Text(
                                lStr(R.string.invoice_local_capture_time, BusinessRules.formatTimestamp(it, zoneId)),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        Text(
                            lStr(
                                R.string.invoice_server_confirmed_time,
                                inv.signedServerAt?.let { BusinessRules.formatTimestamp(it, zoneId) } ?: lStr(R.string.invoice_pending_sync)
                            ),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    inv.voidedReason?.let {
                        Text(lStr(R.string.invoice_voided_reason_field, it), color = TerracottaError, style = MaterialTheme.typography.bodySmall)
                    }
                    if (!inv.uploadError.isNullOrBlank()) {
                        Text(lStr(R.string.invoice_upload_error_field, inv.uploadError), color = TerracottaError, style = MaterialTheme.typography.bodySmall)
                    }
                    detailIssues.forEach { issue ->
                        Text("• $issue", color = TerracottaError, style = MaterialTheme.typography.bodySmall)
                    }

                    // Safe retry upload (preserves signed state and history)
                    if (inv.companyId.isNotBlank() &&
                        (inv.status == InvoiceStatus.UPLOAD_FAILED || inv.syncState == SyncState.FAILED || inv.status == InvoiceStatus.PENDING_UPLOAD || !inv.uploadError.isNullOrBlank())
                    ) {
                        Button(
                            onClick = {
                                viewModel.retryInvoiceUpload(inv.id)
                                selectedInvoice = null
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                        ) {
                            Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(lStr(R.string.retry_upload))
                        }
                    }

                    // Document actions
                    if (!inv.signed && inv.status != InvoiceStatus.VOIDED && canUpload) {
                        Button(
                            onClick = {
                                selectedInvoice = null
                                onSignInvoice(inv.id)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                        ) {
                            Icon(Icons.Default.Draw, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(lStr(R.string.collect_signature))
                        }
                    }

                    val pdfPath = inv.signedPdfLocalPath
                    if (!pdfPath.isNullOrBlank()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { SignedPdfGenerator.openFile(context, pdfPath, "application/pdf") },
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 44.dp)
                            ) {
                                Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(lStr(R.string.view_signed_pdf))
                            }
                            OutlinedButton(
                                onClick = { SignedPdfGenerator.printPdf(context, pdfPath, "DeliFar-${inv.invoiceNumber}") },
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 44.dp)
                            ) {
                                Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(lStr(R.string.print_pdf))
                            }
                        }
                        val sharePdfTitle = lStr(R.string.share_pdf)
                        OutlinedButton(
                            onClick = { SignedPdfGenerator.shareFile(context, pdfPath, "application/pdf", sharePdfTitle) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 44.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(sharePdfTitle)
                        }
                    }

                    val origPath = iwp.sortedPages.firstOrNull()?.originalLocalPath
                    if (!origPath.isNullOrBlank()) {
                        OutlinedButton(
                            onClick = { SignedPdfGenerator.openFile(context, origPath, "image/jpeg") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 44.dp)
                        ) {
                            Text(lStr(R.string.view_original_photo))
                        }
                    }

                    if (canVoidOrCorrect && inv.status != InvoiceStatus.VOIDED) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            if (inv.signed) {
                                OutlinedButton(
                                    onClick = {
                                        selectedInvoice = null
                                        correctTarget = iwp
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .defaultMinSize(minHeight = 44.dp)
                                ) {
                                    Text(lStr(R.string.new_corrected_version))
                                }
                            }
                            TextButton(
                                onClick = {
                                    selectedInvoice = null
                                    voidTarget = iwp
                                },
                                modifier = Modifier.defaultMinSize(minHeight = 44.dp)
                            ) {
                                Text(lStr(R.string.void_invoice), color = TerracottaError)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedInvoice = null }) { Text(lStr(R.string.close)) }
            }
        )
    }

    // Void Dialog
    voidTarget?.let { target ->
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { voidTarget = null },
            title = { Text("${lStr(R.string.void_invoice)} #${target.invoice.invoiceNumber}") },
            text = {
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text(lStr(R.string.invoice_void_reason_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    enabled = reason.trim().length >= 3,
                    onClick = {
                        viewModel.voidInvoice(target.invoice.id, reason)
                        voidTarget = null
                    }
                ) { Text(lStr(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { voidTarget = null }) { Text(lStr(R.string.cancel)) }
            }
        )
    }

    // Create Corrected Version Dialog (preserves immutable signed original)
    correctTarget?.let { target ->
        var note by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { correctTarget = null },
            title = { Text(lStr(R.string.new_corrected_version)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        lStr(R.string.invoice_correction_explain, target.invoice.version + 1, target.invoice.version),
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text(lStr(R.string.invoice_correction_reason_hint)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = note.trim().length >= 3,
                    onClick = {
                        scope.launch {
                            val res = viewModel.createCorrectedInvoice(target.invoice.id, note)
                            correctTarget = null
                            res.onSuccess { created -> onSignInvoice(created.invoice.id) }
                        }
                    }
                ) { Text(lStr(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { correctTarget = null }) { Text(lStr(R.string.cancel)) }
            }
        )
    }
}
