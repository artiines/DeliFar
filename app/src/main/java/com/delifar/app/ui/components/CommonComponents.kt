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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.delifar.app.R
import com.delifar.app.cloud.CloudSyncUiState
import com.delifar.app.data.EmployeeRole
import com.delifar.app.data.HoursMode
import com.delifar.app.data.InvoiceStatus
import com.delifar.app.data.JobIssue
import com.delifar.app.data.JobListRow
import com.delifar.app.data.JobStatus
import com.delifar.app.data.JobType
import com.delifar.app.data.StoreHoursEntity
import com.delifar.app.domain.BusinessRules
import com.delifar.app.ui.theme.AmberAccent
import com.delifar.app.ui.theme.AmberContainer
import com.delifar.app.ui.theme.InfoBlue
import com.delifar.app.ui.theme.InfoContainer
import com.delifar.app.ui.theme.SuccessContainer
import com.delifar.app.ui.theme.SuccessGreen
import com.delifar.app.ui.theme.TerracottaContainer
import com.delifar.app.ui.theme.TerracottaError
import java.time.ZoneId
import java.util.Locale

val LocalLocalizedContext = compositionLocalOf<Context> {
    error("LocalLocalizedContext not provided")
}

@Composable
fun ProvideAppLocale(languageCode: String, content: @Composable () -> Unit) {
    val baseContext = LocalContext.current
    val localizedContext = remember(baseContext, languageCode) {
        if (languageCode == "system") {
            baseContext
        } else {
            val locale = Locale(languageCode)
            val cfg = Configuration(baseContext.resources.configuration)
            cfg.setLocale(locale)
            baseContext.createConfigurationContext(cfg)
        }
    }
    CompositionLocalProvider(LocalLocalizedContext provides localizedContext, content = content)
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
fun SyncStatusBanner(
    syncState: CloudSyncUiState,
    pendingCount: Int,
    failedCount: Int,
    companyName: String?,
    onTapSync: () -> Unit
) {
    val (bg, fg, icon, label) = when {
        failedCount > 0 -> Quad(TerracottaContainer, TerracottaError, Icons.Default.CloudOff, lStr(R.string.sync_failed, failedCount))
        syncState.isSyncing -> Quad(InfoContainer, InfoBlue, Icons.Default.CloudSync, lStr(R.string.sync_syncing))
        pendingCount > 0 && syncState.firebaseConfigured -> Quad(AmberContainer, Color(0xFF92400E), Icons.Default.CloudUpload, lStr(R.string.sync_pending, pendingCount))
        !syncState.firebaseConfigured -> Quad(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, Icons.Default.CloudOff, lStr(R.string.sync_offline_mode))
        else -> Quad(SuccessContainer, SuccessGreen, Icons.Default.CloudDone, lStr(R.string.sync_synced))
    }

    Surface(
        color = bg,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTapSync)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (!companyName.isNullOrBlank()) "$companyName • $label" else label,
                    style = MaterialTheme.typography.labelMedium,
                    color = fg,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (failedCount > 0 || pendingCount > 0) {
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

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

/**
 * Mandatory Google Maps Platform Demo Key Quota Warning Banner (Section 8 Case A).
 */
@Composable
fun GoogleMapsQuotaBanner(
    visible: Boolean,
    onDismiss: () -> Unit
) {
    if (!visible) return
    val context = LocalContext.current
    Surface(
        color = AmberContainer,
        border = BorderStroke(1.dp, AmberAccent.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = AmberAccent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = lStr(R.string.maps_quota_warning),
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFF451A03),
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = {
                    val uri = Uri.parse(
                        "https://developers.google.com/maps/ai/ai-studio?utm_campaign=gmp_mcp_codeassist_v1_aistudio#quota_exceeded_errors"
                    )
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                }
            ) {
                Text(lStr(R.string.maps_quota_learn_more), style = MaterialTheme.typography.labelMedium)
            }
            TextButton(onClick = onDismiss) {
                Text(lStr(R.string.close), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
fun JobCard(
    row: JobListRow,
    zoneId: ZoneId = BusinessRules.LA_ZONE,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
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
                        Text(
                            text = row.displayStoreName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
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
        }
    }
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
 * Launches Google Maps EXCLUSIVELY for turn-by-turn driving navigation.
 * Targets `com.google.android.apps.maps` directly, falling back ONLY to Google Maps HTTPS directions.
 */
fun launchDirections(context: Context, lat: Double, lon: Double, label: String) {
    val navUri = Uri.parse("google.navigation:q=$lat,$lon&mode=d")
    val mapsAppIntent = Intent(Intent.ACTION_VIEW, navUri).apply {
        setPackage("com.google.android.apps.maps")
    }
    if (mapsAppIntent.resolveActivity(context.packageManager) != null) {
        runCatching { context.startActivity(mapsAppIntent) }
        return
    }
    // Fallback strictly to Google Maps web directions (never third-party map apps)
    val webUri = Uri.parse(
        "https://www.google.com/maps/dir/?api=1&destination=$lat,$lon&travelmode=driving"
    )
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, webUri)) }
}

fun launchDialer(context: Context, phone: String) {
    val clean = phone.trim()
    if (clean.isEmpty()) return
    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$clean"))
    runCatching { context.startActivity(intent) }
}
