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
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.delifar.app.data.HoursMode
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
fun issueLabel(issue: String): String = when (issue) {
    JobIssue.PARTIAL -> lStr(R.string.issue_partial)
    JobIssue.CLOSED -> lStr(R.string.issue_closed)
    JobIssue.REFUSED -> lStr(R.string.issue_refused)
    else -> lStr(R.string.issue_other)
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
fun JobCard(
    row: JobListRow,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val overdue = BusinessRules.isOverdue(row.job.date, row.job.status)
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

            Spacer(Modifier.height(10.dp))

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
        }
    }
}

@Composable
fun todayHoursSummary(hours: List<StoreHoursEntity>): String {
    val todayDay = BusinessRules.todayLa().dayOfWeek.value
    val h = hours.find { it.day == todayDay } ?: return lStr(R.string.hours_unknown)
    return when (h.mode) {
        HoursMode.CLOSED -> lStr(R.string.hours_closed)
        HoursMode.OPEN -> h.windows.ifBlank { lStr(R.string.hours_open) }
        else -> lStr(R.string.hours_unknown)
    }
}

fun launchDirections(context: Context, lat: Double, lon: Double, label: String) {
    val encoded = Uri.encode(label)
    val geoUri = Uri.parse("geo:$lat,$lon?q=$lat,$lon($encoded)")
    val intent = Intent(Intent.ACTION_VIEW, geoUri)
    runCatching { context.startActivity(intent) }
}

fun launchDialer(context: Context, phone: String) {
    val clean = phone.trim()
    if (clean.isEmpty()) return
    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$clean"))
    runCatching { context.startActivity(intent) }
}
