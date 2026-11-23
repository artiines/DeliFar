package com.delifar.app.cloud

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.delifar.app.data.DeliFarDatabase
import com.delifar.app.data.DeliFarRepository
import com.delifar.app.reminders.ReminderScheduler

/**
 * Persistent background WorkManager worker that flushes queued Firestore mutations
 * and private Firebase Cloud Storage invoice uploads whenever connectivity returns.
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val db = DeliFarDatabase.getInstance(applicationContext)
        val repo = DeliFarRepository(db)
        val syncManager = CloudSyncManager(applicationContext, db, repo)

        val prefs = repo.getPreferences()
        val ok = runCatching { syncManager.flushPendingQueue(prefs.activeCompanyId) }.getOrDefault(false)
        if (!ok) {
            if (prefs.notifFailedUpload && runAttemptCount >= 2) {
                ReminderScheduler.postNotification(
                    context = applicationContext,
                    title = "DeliFar Upload Retry Needed",
                    body = "Some invoices or updates could not upload yet. DeliFar will retry automatically when online.",
                    notificationId = 90042
                )
            }
            return Result.retry()
        }
        return Result.success()
    }
}
