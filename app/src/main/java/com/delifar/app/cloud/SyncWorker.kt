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
 * Never marks failed or unauthenticated background syncs as successful.
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!FirebaseServices.isConfigured || !FirebaseServices.isAuthenticated) {
            return Result.success()
        }
        val db = DeliFarDatabase.getInstance(applicationContext)
        val repo = DeliFarRepository(db)
        val syncManager = CloudSyncManager(applicationContext, db, repo)

        val prefs = repo.getPreferences()
        val outcome = try {
            runCatching {
                syncManager.flushPendingQueueDetailed(prefs.activeCompanyId)
            }.getOrElse { err ->
                runCatching {
                    repo.setPreference(
                        com.delifar.app.data.PrefKeys.LAST_SYNC_ERROR,
                        err.message ?: "Background sync failed."
                    )
                }
                SyncFlushOutcome.FAILED_TRANSIENT
            }
        } finally {
            syncManager.shutdown()
        }

        return when (outcome) {
            SyncFlushOutcome.SUCCEEDED,
            SyncFlushOutcome.NOT_CONFIGURED,
            SyncFlushOutcome.NOT_SIGNED_IN -> Result.success()

            SyncFlushOutcome.ACCESS_DENIED_OR_UNCONFIGURED -> {
                if (prefs.notifFailedUpload) {
                    ReminderScheduler.postNotification(
                        context = applicationContext,
                        title = "DeliFar Sync Access Issue",
                        body = "Cloud sync was denied or backend services are not configured yet. Open Settings to check status.",
                        notificationId = 90042
                    )
                }
                Result.failure()
            }

            SyncFlushOutcome.OFFLINE,
            SyncFlushOutcome.FAILED_TRANSIENT -> {
                if (prefs.notifFailedUpload && runAttemptCount >= 2) {
                    ReminderScheduler.postNotification(
                        context = applicationContext,
                        title = "DeliFar Upload Retry Needed",
                        body = "Some invoices or updates could not upload yet. Open DeliFar to review or retry.",
                        notificationId = 90042
                    )
                }
                Result.retry()
            }
        }
    }
}
