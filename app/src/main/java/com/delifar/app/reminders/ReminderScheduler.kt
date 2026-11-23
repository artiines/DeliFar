package com.delifar.app.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.delifar.app.MainActivity
import com.delifar.app.data.DeliFarDatabase
import com.delifar.app.data.DeliFarRepository
import com.delifar.app.data.EmployeeRole
import com.delifar.app.data.JobStatus
import com.delifar.app.domain.BusinessRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime

data class ReminderDispatchSummary(
    val deliveredCount: Int,
    val failedOrSuppressedCount: Int,
    val nextScheduledAt: Instant?
)

object ReminderScheduler {
    const val CHANNEL_ID = "delifar_reminders"
    const val CLOUD_CHANNEL_ID = "delifar_cloud_alerts"
    const val ACTION_ALARM_FIRE = "com.delifar.app.ACTION_REMINDER_ALARM"
    private const val ALARM_REQUEST_CODE = 42001

    private data class CandidateAlert(
        val triggerAt: Instant,
        val receiptKey: String,
        val title: String,
        val body: String,
        val notificationId: Int
    )

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DeliFar Reminders",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Daily work summaries and timed delivery/pickup reminders"
            }
            manager.createNotificationChannel(channel)
        }
        if (manager.getNotificationChannel(CLOUD_CHANNEL_ID) == null) {
            val cloudChannel = NotificationChannel(
                CLOUD_CHANNEL_ID,
                "DeliFar Team & Invoice Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Driver assignments, signed invoices, upload status, and team invitations"
            }
            manager.createNotificationChannel(cloudChannel)
        }
    }

    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return runCatching {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }.getOrDefault(false)
    }

    suspend fun rebuildAndDispatchIfDue(context: Context): ReminderDispatchSummary {
        ensureChannel(context)
        val db = DeliFarDatabase.getInstance(context)
        return rebuildAndDispatchIfDue(
            context = context,
            db = db,
            nowInstant = Instant.now(),
            scheduleAlarm = true,
            poster = { title, body, notificationId, channelId ->
                postNotification(context, title, body, notificationId, channelId)
            }
        )
    }

    /**
     * Evaluates due daily summaries and timed job reminders strictly scoped to the active company
     * (and active driver when logged in as Driver). Records a delivery receipt ONLY if
     * notification delivery actually succeeded; denied permissions or failed deliveries will NOT
     * silently suppress future delivery attempts.
     */
    suspend fun rebuildAndDispatchIfDue(
        context: Context,
        db: DeliFarDatabase,
        nowInstant: Instant = Instant.now(),
        scheduleAlarm: Boolean = true,
        poster: (title: String, body: String, notificationId: Int, channelId: String) -> Boolean = { title, body, id, channel ->
            postNotification(context, title, body, id, channel)
        }
    ): ReminderDispatchSummary {
        val repo = DeliFarRepository(db)
        val prefs = repo.getPreferences()
        val zone = BusinessRules.resolveZone(prefs.companyTimezone)
        val companyJobs = db.jobDao().getActiveJobsByCompany(prefs.activeCompanyId)
        val activeJobs = if (prefs.activeUserRole == EmployeeRole.DRIVER && prefs.activeUserUid.isNotBlank()) {
            companyJobs.filter { it.assignedDriverUid == prefs.activeUserUid }
        } else {
            companyJobs
        }
        val nowZone = ZonedDateTime.ofInstant(nowInstant, zone)

        val futureCandidates = mutableListOf<CandidateAlert>()
        var deliveredCount = 0
        var failedCount = 0

        // 1. Daily summary candidate (today if still within 6h window, plus tomorrow)
        if (prefs.daily) {
            val summaryTime = LocalTime.of(prefs.summaryMinute / 60, prefs.summaryMinute % 60)
            for (dayOffset in 0L..1L) {
                val targetDate = nowZone.toLocalDate().plusDays(dayOffset)
                val targetZdt = ZonedDateTime.of(targetDate, summaryTime, zone)
                val triggerInstant = targetZdt.toInstant()
                val companyScope = prefs.activeCompanyId.ifBlank { "local" }
                val receiptKey = "summary:$companyScope:$targetDate:${prefs.summaryMinute}:${zone.id}"
                if (repo.hasReceipt(receiptKey)) continue

                val jobsForDay = activeJobs.count { it.date == targetDate.toString() }
                val overdueCount = activeJobs.count { BusinessRules.isOverdue(it.date, it.status, targetDate) }
                if (jobsForDay == 0 && overdueCount == 0 && dayOffset == 0L) continue

                val body = buildString {
                    append("$jobsForDay scheduled for $targetDate")
                    if (overdueCount > 0) append(" • $overdueCount overdue")
                }
                val candidate = CandidateAlert(
                    triggerAt = triggerInstant,
                    receiptKey = receiptKey,
                    title = "DeliFar Daily Summary",
                    body = body,
                    notificationId = ("summary:$companyScope:$targetDate").hashCode()
                )
                val delayMs = Duration.between(triggerInstant, nowInstant).toMillis()
                if (delayMs >= 0L) {
                    if (delayMs <= 6L * 60 * 60 * 1000) {
                        val delivered = poster(
                            candidate.title,
                            candidate.body,
                            candidate.notificationId,
                            CHANNEL_ID
                        )
                        if (delivered) {
                            repo.recordReceipt(candidate.receiptKey)
                            deliveredCount++
                        } else {
                            failedCount++
                        }
                    }
                } else {
                    futureCandidates += candidate
                }
            }
        }

        // 2. Timed job reminders
        if (prefs.jobReminders && prefs.notifUpcoming) {
            val advanceMs = prefs.advance.toLong() * 60_000L
            for (job in activeJobs) {
                if (job.status !in JobStatus.ACTIVE || !job.reminderEnabled) continue
                val timeStr = job.time ?: continue
                val jobInstant = BusinessRules.jobInstant(job.date, timeStr, zone) ?: continue
                if (!jobInstant.isAfter(nowInstant)) continue

                val receiptKey = "job:${job.id}:${job.reminderVersion}"
                if (repo.hasReceipt(receiptKey)) continue

                val triggerInstant = jobInstant.minusMillis(advanceMs)
                val candidate = CandidateAlert(
                    triggerAt = triggerInstant,
                    receiptKey = receiptKey,
                    title = "${job.type.replaceFirstChar { it.uppercase() }} • ${job.snapshotName}",
                    body = "Scheduled at $timeStr (${job.date}) — ${job.snapshotAddress}",
                    notificationId = job.id.hashCode()
                )
                if (!triggerInstant.isAfter(nowInstant)) {
                    val delivered = poster(
                        candidate.title,
                        candidate.body,
                        candidate.notificationId,
                        CHANNEL_ID
                    )
                    if (delivered) {
                        repo.recordReceipt(candidate.receiptKey)
                        deliveredCount++
                    } else {
                        failedCount++
                    }
                } else {
                    futureCandidates += candidate
                }
            }
        }

        val next = futureCandidates.minByOrNull { it.triggerAt }
        if (scheduleAlarm) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            if (alarmManager != null) {
                val intent = Intent(context, ReminderReceiver::class.java).apply { action = ACTION_ALARM_FIRE }
                val pi = PendingIntent.getBroadcast(
                    context,
                    ALARM_REQUEST_CODE,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                if (next != null) {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        next.triggerAt.toEpochMilli(),
                        pi
                    )
                } else {
                    alarmManager.cancel(pi)
                }
            }
        }
        return ReminderDispatchSummary(
            deliveredCount = deliveredCount,
            failedOrSuppressedCount = failedCount,
            nextScheduledAt = next?.triggerAt
        )
    }

    /**
     * Posts a local Android notification and returns true ONLY if permission was granted,
     * notifications are enabled for the app, and NotificationManagerCompat accepted the notification.
     */
    fun postNotification(
        context: Context,
        title: String,
        body: String,
        notificationId: Int,
        channelId: String = CLOUD_CHANNEL_ID
    ): Boolean {
        ensureChannel(context)
        if (!hasNotificationPermission(context)) {
            return false
        }
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPi = PendingIntent.getActivity(
            context,
            notificationId,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(contentPi)
            .build()

        return try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ReminderScheduler.rebuildAndDispatchIfDue(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }
}
