package com.delifar.app.cloud

import com.delifar.app.data.DeliFarDatabase
import com.delifar.app.data.DeliFarRepository
import com.delifar.app.reminders.ReminderScheduler
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles Firebase Cloud Messaging (FCM) push notifications across all 8 DeliFar events
 * and refreshes the employee's FCM device token in Firestore.
 */
class DeliFarMessagingService : FirebaseMessagingService() {

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val db = DeliFarDatabase.getInstance(applicationContext)
                val repo = DeliFarRepository(db)
                val prefs = repo.getPreferences()
                val uid = prefs.activeUserUid
                val companyId = prefs.activeCompanyId
                if (uid.isNotBlank() && companyId.isNotBlank()) {
                    val emp = db.employeeDao().getByUid(uid)
                    if (emp != null) {
                        db.employeeDao().upsert(emp.copy(fcmToken = token))
                        repo.enqueueSyncInternal(companyId, "employee", uid, "upsert")
                        CloudSyncManager.scheduleBackgroundSync(applicationContext)
                    }
                }
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val eventType = message.data["eventType"].orEmpty()
        val title = message.notification?.title ?: message.data["title"] ?: "DeliFar Update"
        val body = message.notification?.body ?: message.data["body"] ?: "You have a new delivery update."

        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val db = DeliFarDatabase.getInstance(applicationContext)
                val prefs = DeliFarRepository(db).getPreferences()
                val allowed = when (eventType) {
                    "new_assignment" -> prefs.notifNewAssignment
                    "upcoming_delivery" -> prefs.notifUpcoming
                    "changed_assignment" -> prefs.notifChangedAssignment
                    "completed_delivery" -> prefs.notifCompleted
                    "signed_invoice" -> prefs.notifSignedInvoice
                    "missing_signature" -> prefs.notifMissingSignature
                    "failed_upload" -> prefs.notifFailedUpload
                    "employee_invitation" -> prefs.notifEmployeeInvite
                    else -> true
                }
                if (allowed) {
                    ReminderScheduler.postNotification(
                        context = applicationContext,
                        title = title,
                        body = body,
                        notificationId = (eventType + title + body).hashCode()
                    )
                }
            }
        }
    }
}
