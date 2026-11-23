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
import com.delifar.app.R
import com.delifar.app.data.DeliFarDatabase
import com.delifar.app.data.DeliFarRepository
import com.delifar.app.data.JobStatus
import com.delifar.app.domain.BusinessRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime

object ReminderScheduler {
    const val CHANNEL_ID = "delifar_reminders"
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
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DeliFar Reminders",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Daily work summaries and timed delivery/pickup reminders"
            }
            manager.createNotificationChannel(channel)
        }
    }

    suspend fun rebuildAndDispatchIfDue(context: Context) {
        ensureChannel(context)
        val db = DeliFarDatabase.getInstance(context)
        val repo = DeliFarRepository(db)
        val prefs = repo.getPreferences()
        val activeJobs = db.jobDao().getActiveJobs()
        val nowInstant = Instant.now()
        val nowLa = BusinessRules.nowLa()

        val futureCandidates = mutableListOf<CandidateAlert>()

        // 1. Daily summary candidate (today if still within 6h window, plus tomorrow)
        if (prefs.daily) {
            val summaryTime = LocalTime.of(prefs.summaryMinute / 60, prefs.summaryMinute % 60)
            for (dayOffset in 0L..1L) {
                val targetDate = nowLa.toLocalDate().plusDays(dayOffset)
                val targetZdt = ZonedDateTime.of(targetDate, summaryTime, BusinessRules.LA_ZONE)
                val triggerInstant = targetZdt.toInstant()
                val receiptKey = "summary:$targetDate:${prefs.summaryMinute}"
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
                    notificationId = ("summary:$targetDate").hashCode()
                )
                val delayMs = Duration.between(triggerInstant, nowInstant).toMillis()
                if (delayMs >= 0L) {
                    // Delayed beyond 6 hours -> skip
                    if (delayMs <= 6L * 60 * 60 * 1000) {
                        postNotification(context, candidate)
                        repo.recordReceipt(candidate.receiptKey)
                    }
                } else {
                    futureCandidates += candidate
                }
            }
        }

        // 2. Timed job reminders
        if (prefs.jobReminders) {
            val advanceMs = prefs.advance.toLong() * 60_000L
            for (job in activeJobs) {
                if (job.status !in JobStatus.ACTIVE || !job.reminderEnabled) continue
                val timeStr = job.time ?: continue
                val jobInstant = BusinessRules.jobInstantLa(job.date, timeStr) ?: continue
                // Expired job alerts are never replayed
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
                    // Future job already inside reminder window -> one immediate eligible alert
                    postNotification(context, candidate)
                    repo.recordReceipt(candidate.receiptKey)
                } else {
                    futureCandidates += candidate
                }
            }
        }

        // Schedule single next AlarmManager.setAndAllowWhileIdle alarm
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java).apply { action = ACTION_ALARM_FIRE }
        val pi = PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val next = futureCandidates.minByOrNull { it.triggerAt }
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

    private fun postNotification(context: Context, alert: CandidateAlert) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPi = PendingIntent.getActivity(
            context,
            alert.notificationId,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(alert.title)
            .setContentText(alert.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(contentPi)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(alert.notificationId, notification)
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
