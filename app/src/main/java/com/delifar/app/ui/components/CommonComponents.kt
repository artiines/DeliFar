package com.delifar.app.ui.components

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.delifar.app.R
import com.delifar.app.cloud.CloudSyncStatus
import com.delifar.app.cloud.CloudSyncUiState
import com.delifar.app.data.EmployeeRole
import com.delifar.app.data.HoursMode
import com.delifar.app.data.InvoiceStatus
import com.delifar.app.data.JobIssue
import com.delifar.app.data.JobItemEntity
import com.delifar.app.data.JobListRow
import com.delifar.app.data.JobStatus
import com.delifar.app.data.JobType
import com.delifar.app.data.StoreHoursEntity
import com.delifar.app.domain.BusinessRules
import com.delifar.app.network.GeoValidation
import com.delifar.app.ui.theme.AmberAccent
import com.delifar.app.ui.theme.AmberContainer
import com.delifar.app.ui.theme.InfoBlue
import com.delifar.app.ui.theme.InfoContainer
import com.delifar.app.ui.theme.SuccessContainer
import com.delifar.app.ui.theme.SuccessGreen
import com.delifar.app.ui.theme.TerracottaContainer
import com.delifar.app.ui.theme.TerracottaError
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

val LocalLocalizedContext = compositionLocalOf<Context> {
    error("LocalLocalizedContext not provided")
}

val LocalAppLocale = compositionLocalOf<Locale> {
    Locale.getDefault()
}

@Composable
fun ProvideAppLocale(languageCode: String, content: @Composable () -> Unit) {
    val baseContext = LocalContext.current
    val resolvedLocale = remember(baseContext, languageCode) {
        if (languageCode == "system") {
            baseContext.resources.configuration.locales.takeIf { !it.isEmpty }?.get(0) ?: Locale.getDefault()
        } else {
            Locale(languageCode)
        }
    }
    val localizedContext = remember(baseContext, languageCode, resolvedLocale) {
        if (languageCode == "system") {
            baseContext
        } else {
            val cfg = Configuration(baseContext.resources.configuration)
            cfg.setLocale(resolvedLocale)
            baseContext.createConfigurationContext(cfg)
        }
    }
    CompositionLocalProvider(
        LocalLocalizedContext provides localizedContext,
        LocalAppLocale provides resolvedLocale,
        content = content
    )
}

@Composable
fun lStr(@StringRes id: Int): String = LocalLocalizedContext.current.getString(id)

@Composable
fun lStr(@StringRes id: Int, vararg args: Any): String = LocalLocalizedContext.current.getString(id, *args)

@Composable
fun statusLabel(status: String): String = when (status) {
    JobStatus.PENDING -> lStr(R.string.status_pending)
    JobStatus.PROGRESS -> lStr(R.string.status_progress)
    JobStatus.COMPLETED -> lStr(R.string.status_completed)
    JobStatus.CANCELED -> lStr(R.string.status_canceled)
    else -> status
}

@Composable
fun jobTypeLabel(type: String): String = when (type) {
    JobType.PICKUP -> lStr(R.string.type_pickup)
    else -> lStr(R.string.type_delivery)
}

@Composable
fun roleLabel(role: String): String = when (role) {
    EmployeeRole.OWNER -> lStr(R.string.role_owner)
    EmployeeRole.MANAGER -> lStr(R.string.role_manager)
    EmployeeRole.ACCOUNTANT -> lStr(R.string.role_accountant)
    else -> lStr(R.string.role_driver)
}

@Composable
fun issueLabel(issue: String): String = when (issue) {
    JobIssue.PARTIAL -> lStr(R.string.issue_partial)
    JobIssue.CLOSED -> lStr(R.string.issue_closed)
    JobIssue.REFUSED -> lStr(R.string.issue_refused)
    JobIssue.UNAVAILABLE -> lStr(R.string.issue_unavailable)
    JobIssue.DAMAGED -> lStr(R.string.issue_damaged)
    JobIssue.UNSIGNED -> lStr(R.string.issue_unsigned)
    else -> lStr(R.string.issue_other)
}

@Composable
fun invoiceStatusLabel(status: String): String = when (status) {
    InvoiceStatus.NOT_SIGNED -> lStr(R.string.invoice_status_not_signed)
    InvoiceStatus.SIGNED -> lStr(R.string.invoice_status_signed)
    InvoiceStatus.PENDING_UPLOAD -> lStr(R.string.invoice_status_pending_upload)
    InvoiceStatus.SYNCHRONIZED -> lStr(R.string.invoice_status_synchronized)
    InvoiceStatus.UPLOAD_FAILED -> lStr(R.string.invoice_status_upload_failed)
    InvoiceStatus.VOIDED -> lStr(R.string.invoice_status_voided)
    else -> status
}

fun invoiceStatusColors(status: String): Pair<Color, Color> = when (status) {
    InvoiceStatus.SYNCHRONIZED, InvoiceStatus.SIGNED -> SuccessContainer to SuccessGreen
    InvoiceStatus.PENDING_UPLOAD -> InfoContainer to InfoBlue
    InvoiceStatus.UPLOAD_FAILED -> TerracottaContainer to TerracottaError
    InvoiceStatus.VOIDED -> Color(0xFFE5E7EB) to Color(0xFF4B5563)
    else -> AmberContainer to Color(0xFF92400E)
}

@Composable
fun weekdayShortLabel(day: Int): String = when (day) {
    1 -> lStr(R.string.day_mon)
    2 -> lStr(R.string.day_tue)
    3 -> lStr(R.string.day_wed)
    4 -> lStr(R.string.day_thu)
    5 -> lStr(R.string.day_fri)
    6 -> lStr(R.string.day_sat)
    else -> lStr(R.string.day_sun)
}

fun statusColors(status: String, overdue: Boolean): Pair<Color, Color> {
    if (overdue) return TerracottaContainer to TerracottaError
    return when (status) {
        JobStatus.PROGRESS -> InfoContainer to InfoBlue
        JobStatus.COMPLETED -> SuccessContainer to SuccessGreen
        JobStatus.CANCELED -> Color(0xFFE5E7EB) to Color(0xFF4B5563)
        else -> AmberContainer to Color(0xFF92400E)
    }
}

@Composable
fun StatusTag(text: String, bg: Color, fg: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = fg,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
fun RoleBadge(role: String) {
    val (bg, fg) = when (role) {
        EmployeeRole.OWNER -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        EmployeeRole.MANAGER -> InfoContainer to InfoBlue
        EmployeeRole.ACCOUNTANT -> SuccessContainer to SuccessGreen
        else -> AmberContainer to Color(0xFF92400E)
    }
    StatusTag(text = roleLabel(role), bg = bg, fg = fg)
}

@Composable
fun syncStatusLabel(
    status: CloudSyncStatus,
    pendingCount: Int = 0,
    failedCount: Int = 0
): String = when (status) {
    CloudSyncStatus.OFFLINE_SAVING_LOCALLY -> lStr(R.string.sync_offline_mode)
    CloudSyncStatus.CONFIGURED_NOT_SIGNED_IN -> lStr(R.string.sync_configured_not_signed_in)
    CloudSyncStatus.AUTHENTICATION_REQUIRED -> lStr(R.string.sync_auth_required)
    CloudSyncStatus.CONNECTING -> lStr(R.string.sync_connecting)
    CloudSyncStatus.SYNCHRONIZING -> lStr(R.string.sync_syncing)
    CloudSyncStatus.FULLY_SYNCHRONIZED -> lStr(R.string.sync_synced)
    CloudSyncStatus.UPLOAD_PENDING -> lStr(R.string.sync_pending, pendingCount.coerceAtLeast(1))
    CloudSyncStatus.SYNCHRONIZATION_FAILED -> lStr(R.string.sync_failed, failedCount.coerceAtLeast(1))
    CloudSyncStatus.ACCESS_DENIED_OR_UNCONFIGURED -> lStr(R.string.sync_access_denied)
}

@Composable
fun syncStatusColors(status: CloudSyncStatus): Pair<Color, Color> = when (status) {
    CloudSyncStatus.FULLY_SYNCHRONIZED -> SuccessContainer to SuccessGreen
    CloudSyncStatus.SYNCHRONIZING, CloudSyncStatus.CONNECTING -> InfoContainer to InfoBlue
    CloudSyncStatus.UPLOAD_PENDING,
    CloudSyncStatus.CONFIGURED_NOT_SIGNED_IN,
    CloudSyncStatus.AUTHENTICATION_REQUIRED -> AmberContainer to Color(0xFF92400E)
    CloudSyncStatus.SYNCHRONIZATION_FAILED,
    CloudSyncStatus.ACCESS_DENIED_OR_UNCONFIGURED -> TerracottaContainer to TerracottaError
    CloudSyncStatus.OFFLINE_SAVING_LOCALLY ->
        MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
fun SyncStatusBanner(
    status: CloudSyncStatus,
    syncState: CloudSyncUiState,
    pendingCount: Int,
    failedCount: Int,
    companyName: String?,
    lastSyncedAt: Long? = null,
    zoneId: ZoneId = BusinessRules.LA_ZONE,
    onTapSync: () -> Unit,
    onTapOpenAccount: (() -> Unit)? = null
) {
    val (bg, fg) = syncStatusColors(status)
    val icon = when (status) {
        CloudSyncStatus.OFFLINE_SAVING_LOCALLY -> Icons.Default.CloudOff
        CloudSyncStatus.CONFIGURED_NOT_SIGNED_IN,
        CloudSyncStatus.AUTHENTICATION_REQUIRED -> Icons.Default.Lock
        CloudSyncStatus.CONNECTING,
        CloudSyncStatus.SYNCHRONIZING -> Icons.Default.CloudSync
        CloudSyncStatus.FULLY_SYNCHRONIZED -> Icons.Default.CloudDone
        CloudSyncStatus.UPLOAD_PENDING -> Icons.Default.CloudUpload
        CloudSyncStatus.SYNCHRONIZATION_FAILED,
        CloudSyncStatus.ACCESS_DENIED_OR_UNCONFIGURED -> Icons.Default.ErrorOutline
    }
    val label = syncStatusLabel(status, pendingCount, failedCount)
    val needsSignIn = status == CloudSyncStatus.CONFIGURED_NOT_SIGNED_IN ||
        status == CloudSyncStatus.AUTHENTICATION_REQUIRED
    val actionClick = if (needsSignIn && onTapOpenAccount != null) onTapOpenAccount else onTapSync
    val effectiveLastSync = syncState.lastSyncedAt ?: lastSyncedAt

    Surface(
        color = bg,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = actionClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 40.dp)
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (!companyName.isNullOrBlank()) "$companyName • $label" else label,
                        style = MaterialTheme.typography.labelMedium,
                        color = fg,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (status == CloudSyncStatus.FULLY_SYNCHRONIZED && effectiveLastSync != null) {
                        Text(
                            text = lStr(R.string.sync_last_synced, BusinessRules.formatTimestamp(effectiveLastSync, zoneId)),
                            style = MaterialTheme.typography.labelSmall,
                            color = fg.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else if ((status == CloudSyncStatus.SYNCHRONIZATION_FAILED ||
                            status == CloudSyncStatus.ACCESS_DENIED_OR_UNCONFIGURED) &&
                        !syncState.lastError.isNullOrBlank()
                    ) {
                        Text(
                            text = syncState.lastError,
                            style = MaterialTheme.typography.labelSmall,
                            color = fg.copy(alpha = 0.9f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            when {
                needsSignIn && onTapOpenAccount != null -> {
                    Text(
                        text = lStr(R.string.sign_in),
                        style = MaterialTheme.typography.labelMedium,
                        color = fg,
                        fontWeight = FontWeight.Bold
                    )
                }
                status == CloudSyncStatus.SYNCHRONIZATION_FAILED ||
                    status == CloudSyncStatus.UPLOAD_PENDING ||
                    status == CloudSyncStatus.ACCESS_DENIED_OR_UNCONFIGURED -> {
                    Text(
                        text = lStr(R.string.sync_now),
                        style = MaterialTheme.typography.labelMedium,
                        color = fg,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun JobCard(
    row: JobListRow,
    zoneId: ZoneId = BusinessRules.LA_ZONE,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    receivingHoursToday: String? = null,
    storeHours: List<StoreHoursEntity> = emptyList(),
    stopNumber: Int? = null,
    onQuickNavigate: (() -> Unit)? = null,
    onQuickCall: (() -> Unit)? = null,
    onQuickAdvanceStatus: (() -> Unit)? = null,
    onQuickInvoice: (() -> Unit)? = null
) {
    val today = remember(zoneId) { BusinessRules.todayInZone(zoneId) }
    val overdue = BusinessRules.isOverdue(row.job.date, row.job.status, today)
    val (badgeBg, badgeFg) = statusColors(row.job.status, overdue)
    val statusText = if (overdue) lStr(R.string.overdue) else statusLabel(row.job.status)
    val lineText = if (row.lineCount == 1) lStr(R.string.line_singular) else lStr(R.string.lines_count, row.lineCount)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            width = 1.dp,
            color = if (overdue) TerracottaError.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Surface(
                        color = if (row.job.type == JobType.DELIVERY) MaterialTheme.colorScheme.primaryContainer
                        else AmberContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(
                            imageVector = if (row.job.type == JobType.DELIVERY) Icons.Default.LocalShipping
                            else Icons.Default.Inventory2,
                            contentDescription = jobTypeLabel(row.job.type),
                            tint = if (row.job.type == JobType.DELIVERY) MaterialTheme.colorScheme.primary else AmberAccent,
                            modifier = Modifier
                                .padding(6.dp)
                                .size(18.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (stopNumber != null) {
                                StatusTag(
                                    text = "#$stopNumber",
                                    bg = MaterialTheme.colorScheme.primary,
                                    fg = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                            Text(
                                text = row.displayStoreName,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        }
                        Text(
                            text = row.displayStoreAddress,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                StatusTag(text = statusText, bg = badgeBg, fg = badgeFg)
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.AccessTime,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "${row.job.date} • ${row.job.time ?: lStr(R.string.untimed)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (row.job.issue != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = AmberAccent,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = issueLabel(row.job.issue),
                                style = MaterialTheme.typography.labelMedium,
                                color = AmberAccent,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    Text(
                        text = "${jobTypeLabel(row.job.type)} • $lineText",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Driver assignment & Invoice/Signature summary row
            if (row.job.assignedDriverName.isNotBlank() || row.invoiceCount > 0) {
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (row.job.assignedDriverName.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Person,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = row.job.assignedDriverName,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Spacer(Modifier.width(1.dp))
                    }

                    if (row.invoiceCount > 0) {
                        val allSigned = row.signedInvoiceCount >= row.invoiceCount
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (allSigned) Icons.Default.Verified else Icons.Default.Description,
                                contentDescription = null,
                                tint = if (allSigned) SuccessGreen else AmberAccent,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "${row.signedInvoiceCount}/${row.invoiceCount} ${lStr(R.string.invoice_status_signed)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (allSigned) SuccessGreen else AmberAccent,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            if (!receivingHoursToday.isNullOrBlank() || storeHours.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (!receivingHoursToday.isNullOrBlank()) {
                        Text(
                            text = "${lStr(R.string.receiving_hours)}: $receivingHoursToday",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Spacer(Modifier.width(1.dp))
                    }
                    if (storeHours.isNotEmpty()) {
                        ReceivingWindowStatusTag(hours = storeHours, zoneId = zoneId)
                    }
                }
            }

            // One-handed driver quick actions (only shown when callbacks provided and job is active)
            val isActive = row.job.status in JobStatus.ACTIVE
            val hasQuickActions = isActive && (onQuickNavigate != null || onQuickCall != null || onQuickAdvanceStatus != null || onQuickInvoice != null)
            if (hasQuickActions) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (onQuickNavigate != null) {
                        OutlinedButton(
                            onClick = onQuickNavigate,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 44.dp)
                        ) {
                            Icon(Icons.Default.Directions, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(lStr(R.string.directions), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (onQuickCall != null) {
                        OutlinedButton(
                            onClick = onQuickCall,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier.defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
                        ) {
                            Icon(Icons.Default.Call, contentDescription = lStr(R.string.call_store), modifier = Modifier.size(16.dp))
                        }
                    }
                    if (onQuickInvoice != null) {
                        OutlinedButton(
                            onClick = onQuickInvoice,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier.defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
                        ) {
                            Icon(Icons.Default.Description, contentDescription = lStr(R.string.quick_invoice_action), modifier = Modifier.size(16.dp))
                        }
                    }
                    if (onQuickAdvanceStatus != null) {
                        val isInProgress = row.job.status == JobStatus.PROGRESS
                        OutlinedButton(
                            onClick = onQuickAdvanceStatus,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 44.dp)
                        ) {
                            Icon(
                                imageVector = if (isInProgress) Icons.Default.CheckCircle else Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = if (isInProgress) SuccessGreen else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = if (isInProgress) lStr(R.string.action_complete) else lStr(R.string.action_start),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ReceivingWindowStatusTag(
    hours: List<StoreHoursEntity>,
    zoneId: ZoneId = BusinessRules.LA_ZONE
) {
    val status = remember(hours, zoneId) {
        val now = BusinessRules.nowInZone(zoneId)
        val dateIso = now.toLocalDate().toString()
        val timeStr = now.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))
        BusinessRules.checkReceivingHours(hours, dateIso, timeStr)
    }
    when (status) {
        BusinessRules.HoursCheckResult.OK -> StatusTag(
            text = lStr(R.string.receiving_open_now),
            bg = SuccessContainer,
            fg = SuccessGreen
        )
        BusinessRules.HoursCheckResult.OUTSIDE_WINDOWS -> StatusTag(
            text = lStr(R.string.receiving_outside_hours),
            bg = AmberContainer,
            fg = AmberAccent
        )
        BusinessRules.HoursCheckResult.CLOSED -> StatusTag(
            text = lStr(R.string.receiving_closed_today),
            bg = TerracottaContainer,
            fg = TerracottaError
        )
        BusinessRules.HoursCheckResult.UNKNOWN -> Unit
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReportIssueDialog(
    items: List<JobItemEntity>,
    initialIssue: String?,
    initialNote: String,
    onDismiss: () -> Unit,
    onSubmit: (issueType: String, note: String, keepOpen: Boolean, actuals: Map<String, String>) -> Unit
) {
    var selectedIssue by remember(initialIssue) { mutableStateOf(initialIssue ?: JobIssue.PARTIAL) }
    var note by remember(initialNote) { mutableStateOf(initialNote) }
    var keepOpen by remember { mutableStateOf(true) }
    val actuals = remember(items) {
        mutableStateMapOf<String, String>().apply {
            items.forEach { this[it.id] = it.actual ?: it.planned }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(lStr(R.string.action_report_issue)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(
                        JobIssue.PARTIAL,
                        JobIssue.CLOSED,
                        JobIssue.REFUSED,
                        JobIssue.UNAVAILABLE,
                        JobIssue.DAMAGED,
                        JobIssue.UNSIGNED,
                        JobIssue.OTHER
                    ).forEach { issue ->
                        FilterChip(
                            selected = selectedIssue == issue,
                            onClick = { selectedIssue = issue },
                            label = { Text(issueLabel(issue)) }
                        )
                    }
                }

                if (selectedIssue == JobIssue.PARTIAL && items.isNotEmpty()) {
                    HorizontalDivider()
                    items.forEach { line ->
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
                    onSubmit(selectedIssue, note, keepOpen, actuals.toMap())
                }
            ) { Text(lStr(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(lStr(R.string.cancel)) }
        }
    )
}

@Composable
fun todayHoursSummary(hours: List<StoreHoursEntity>, zoneId: ZoneId = BusinessRules.LA_ZONE): String {
    val todayDay = BusinessRules.todayInZone(zoneId).dayOfWeek.value
    val h = hours.find { it.day == todayDay } ?: return lStr(R.string.hours_unknown)
    return when (h.mode) {
        HoursMode.CLOSED -> lStr(R.string.hours_closed)
        HoursMode.OPEN -> h.windows.ifBlank { lStr(R.string.hours_open) }
        else -> lStr(R.string.hours_unknown)
    }
}

/**
 * Launches external turn-by-turn driving navigation (Google Maps app preferred, free intent handoff,
 * zero paid APIs). Validates coordinates before launching and refuses to navigate to invalid or
 * placeholder coordinates; falls back to the street address only if coordinates are invalid.
 */
fun launchDirections(
    context: Context,
    lat: Double,
    lon: Double,
    label: String,
    address: String = ""
): Boolean {
    val validCoords = GeoValidation.isValidCoordinate(lat, lon)
    val cleanAddress = address.trim()
    if (!validCoords && cleanAddress.isEmpty()) {
        return false
    }

    val navQuery = if (validCoords) {
        "$lat,$lon"
    } else {
        Uri.encode(cleanAddress)
    }

    val navUri = Uri.parse("google.navigation:q=$navQuery&mode=d")
    val mapsAppIntent = Intent(Intent.ACTION_VIEW, navUri).apply {
        setPackage("com.google.android.apps.maps")
    }
    if (mapsAppIntent.resolveActivity(context.packageManager) != null) {
        return runCatching { context.startActivity(mapsAppIntent) }.isSuccess
    }

    // Standard Android geo: intent fallback (lets user open any installed maps app)
    if (validCoords) {
        val encodedLabel = Uri.encode(label.ifBlank { cleanAddress })
        val geoUri = Uri.parse("geo:$lat,$lon?q=$lat,$lon($encodedLabel)")
        val geoIntent = Intent(Intent.ACTION_VIEW, geoUri)
        if (geoIntent.resolveActivity(context.packageManager) != null) {
            return runCatching { context.startActivity(geoIntent) }.isSuccess
        }
    }

    // Browser fallback to Google Maps driving directions (no API key required)
    val webDestination = if (validCoords) "$lat,$lon" else Uri.encode(cleanAddress)
    val webUri = Uri.parse(
        "https://www.google.com/maps/dir/?api=1&destination=$webDestination&travelmode=driving"
    )
    return runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, webUri)) }.isSuccess
}

fun launchDialer(context: Context, phone: String) {
    val clean = phone.trim()
    if (clean.isEmpty()) return
    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$clean"))
    runCatching { context.startActivity(intent) }
}
