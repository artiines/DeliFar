package com.delifar.app.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

object JobType {
    const val DELIVERY = "delivery"
    const val PICKUP = "pickup"
    val ALL = setOf(DELIVERY, PICKUP)
}

object JobStatus {
    const val PENDING = "pending"
    const val PROGRESS = "progress"
    const val COMPLETED = "completed"
    const val CANCELED = "canceled"
    val ACTIVE = setOf(PENDING, PROGRESS)
    val ALL = setOf(PENDING, PROGRESS, COMPLETED, CANCELED)
}

object JobIssue {
    const val PARTIAL = "partial"
    const val CLOSED = "closed"
    const val REFUSED = "refused"
    const val UNAVAILABLE = "unavailable"
    const val DAMAGED = "damaged"
    const val UNSIGNED = "unsigned"
    const val OTHER = "other"
    val ALL = setOf(PARTIAL, CLOSED, REFUSED, UNAVAILABLE, DAMAGED, UNSIGNED, OTHER)
}

object HoursMode {
    const val UNKNOWN = "unknown"
    const val CLOSED = "closed"
    const val OPEN = "open"
    val ALL = setOf(UNKNOWN, CLOSED, OPEN)
}

object SearchArea {
    const val LA = "la"
    const val SAC = "sac"
    val ALL = setOf(LA, SAC)
}

object EmployeeRole {
    const val OWNER = "owner"
    const val MANAGER = "manager"
    const val DRIVER = "driver"
    const val ACCOUNTANT = "accountant"
    val ALL = setOf(OWNER, MANAGER, DRIVER, ACCOUNTANT)
}

object SyncState {
    const val PENDING = "pending"
    const val SYNCED = "synced"
    const val FAILED = "failed"
    val ALL = setOf(PENDING, SYNCED, FAILED)
}

object InvoiceStatus {
    const val NOT_SIGNED = "not_signed"
    const val SIGNED = "signed"
    const val PENDING_UPLOAD = "pending_upload"
    const val SYNCHRONIZED = "synchronized"
    const val UPLOAD_FAILED = "upload_failed"
    const val VOIDED = "voided"
    val ALL = setOf(NOT_SIGNED, SIGNED, PENDING_UPLOAD, SYNCHRONIZED, UPLOAD_FAILED, VOIDED)
}

object InviteStatus {
    const val PENDING = "pending"
    const val ACCEPTED = "accepted"
    const val REVOKED = "revoked"
    const val EXPIRED = "expired"
    val ALL = setOf(PENDING, ACCEPTED, REVOKED, EXPIRED)
}

object PrefKeys {
    const val LANGUAGE = "language"
    const val AREA = "area"
    const val DAILY = "daily"
    const val SUMMARY_MINUTE = "summaryMinute"
    const val JOB_REMINDERS = "jobReminders"
    const val ADVANCE = "advance"

    // DeliFar 2.0 company & notification preferences
    const val COMPANY_TIMEZONE = "companyTimezone"
    const val ACTIVE_COMPANY_ID = "activeCompanyId"
    const val ACTIVE_USER_UID = "activeUserUid"
    const val ACTIVE_USER_NAME = "activeUserName"
    const val ACTIVE_USER_EMAIL = "activeUserEmail"
    const val ACTIVE_USER_ROLE = "activeUserRole"

    // Per-event notification preferences (8 cloud & operational events)
    const val NOTIF_NEW_ASSIGNMENT = "notifNewAssignment"
    const val NOTIF_UPCOMING = "notifUpcoming"
    const val NOTIF_CHANGED_ASSIGNMENT = "notifChangedAssignment"
    const val NOTIF_COMPLETED = "notifCompleted"
    const val NOTIF_SIGNED_INVOICE = "notifSignedInvoice"
    const val NOTIF_MISSING_SIGNATURE = "notifMissingSignature"
    const val NOTIF_FAILED_UPLOAD = "notifFailedUpload"
    const val NOTIF_EMPLOYEE_INVITE = "notifEmployeeInvite"

    val BACKUP_KEYS = setOf(LANGUAGE, AREA, DAILY, SUMMARY_MINUTE, JOB_REMINDERS, ADVANCE)
}

@Entity(
    tableName = "companies",
    indices = [Index(value = ["ownerUid"])]
)
data class CompanyEntity(
    @PrimaryKey val id: String,
    val name: String,
    val ownerUid: String,
    val timezone: String = "America/Los_Angeles",
    val defaultArea: String = SearchArea.LA,
    val storageUsedBytes: Long = 0L,
    val storageQuotaBytes: Long = 5L * 1024L * 1024L * 1024L, // 5 GB default
    val created: Long,
    val modified: Long
)

@Entity(
    tableName = "employees",
    indices = [
        Index(value = ["companyId", "active"]),
        Index(value = ["email"])
    ]
)
data class EmployeeEntity(
    @PrimaryKey val uid: String,
    val companyId: String,
    val displayName: String,
    val email: String,
    val phone: String = "",
    val role: String = EmployeeRole.DRIVER,
    val active: Boolean = true,
    val fcmToken: String = "",
    val invitedByUid: String = "",
    val joinedAt: Long = 0L,
    val lastActiveAt: Long = 0L
)

@Entity(
    tableName = "invitations",
    indices = [
        Index(value = ["companyId", "status"]),
        Index(value = ["inviteCode"], unique = true)
    ]
)
data class InvitationEntity(
    @PrimaryKey val id: String,
    val companyId: String,
    val companyName: String,
    val email: String,
    val role: String,
    val inviteCode: String,
    val status: String = InviteStatus.PENDING,
    val createdByUid: String,
    val createdByName: String,
    val createdAt: Long,
    val expiresAt: Long,
    val acceptedByUid: String? = null
)

@Entity(
    tableName = "stores",
    indices = [
        Index(value = ["providerId"], unique = true),
        Index(value = ["archived", "name"]),
        Index(value = ["companyId"])
    ]
)
data class StoreEntity(
    @PrimaryKey val id: String,
    val name: String,
    val address: String,
    val lat: Double,
    val lon: Double,
    val providerId: String?,
    val phone: String = "",
    val created: Long,
    val modified: Long,
    val archived: Boolean = false,
    val companyId: String = "",
    val syncState: String = SyncState.PENDING,
    val serverUpdatedAt: Long? = null,
    val updatedByUid: String = "",
    val version: Int = 1
)

@Entity(
    tableName = "hours",
    primaryKeys = ["storeId", "day"],
    foreignKeys = [
        ForeignKey(
            entity = StoreEntity::class,
            parentColumns = ["id"],
            childColumns = ["storeId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["storeId"])]
)
data class StoreHoursEntity(
    val storeId: String,
    val day: Int, // ISO 1=Monday..7=Sunday
    val mode: String = HoursMode.UNKNOWN,
    val windows: String = ""
)

@Entity(
    tableName = "jobs",
    foreignKeys = [
        ForeignKey(
            entity = StoreEntity::class,
            parentColumns = ["id"],
            childColumns = ["storeId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["storeId"]),
        Index(value = ["date", "time"]),
        Index(value = ["status", "date"]),
        Index(value = ["companyId", "assignedDriverUid"])
    ]
)
data class JobEntity(
    @PrimaryKey val id: String,
    val storeId: String,
    val type: String, // delivery | pickup
    val date: String, // YYYY-MM-DD in company timezone
    val time: String?, // HH:mm or null
    val status: String, // pending | progress | completed | canceled
    val issue: String?, // partial | closed | refused | unavailable | damaged | unsigned | other | null
    val issueNote: String = "",
    val snapshotName: String,
    val snapshotAddress: String,
    val snapshotLat: Double,
    val snapshotLon: Double,
    val created: Long,
    val modified: Long,
    val completed: Long?,
    val reminderVersion: String,
    val reminderEnabled: Boolean = true,
    val companyId: String = "",
    val assignedDriverUid: String? = null,
    val assignedDriverName: String = "",
    val notes: String = "",
    val syncState: String = SyncState.PENDING,
    val serverUpdatedAt: Long? = null,
    val updatedByUid: String = "",
    val version: Int = 1
)

@Entity(
    tableName = "items",
    foreignKeys = [
        ForeignKey(
            entity = JobEntity::class,
            parentColumns = ["id"],
            childColumns = ["jobId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["jobId", "position"])]
)
data class JobItemEntity(
    @PrimaryKey val id: String,
    val jobId: String,
    val position: Int,
    val name: String,
    val planned: String, // BigDecimal string
    val unit: String,
    val actual: String? = null // BigDecimal string on partial outcomes, null otherwise
)

@Entity(
    tableName = "invoices",
    indices = [
        Index(value = ["companyId", "createdAt"]),
        Index(value = ["jobId"]),
        Index(value = ["storeId"]),
        Index(value = ["invoiceNumber"])
    ]
)
data class InvoiceEntity(
    @PrimaryKey val id: String,
    val companyId: String = "",
    val jobId: String? = null,
    val storeId: String,
    val storeName: String,
    val invoiceNumber: String,
    val notes: String = "",
    val status: String = InvoiceStatus.NOT_SIGNED,
    val version: Int = 1,
    val supersedesInvoiceId: String? = null,
    val voidedReason: String? = null,
    val createdByUid: String = "",
    val createdByName: String = "",
    val createdAt: Long,
    val modifiedAt: Long,
    // Signature fields (immutable once signed; corrections create a new version)
    val signed: Boolean = false,
    val recipientName: String = "",
    val recipientTitle: String = "",
    val acknowledgmentConfirmed: Boolean = false,
    val signatureLocalPath: String? = null,
    val signatureStoragePath: String? = null,
    val signaturePageIndex: Int = 0,
    val signaturePosX: Float = 0.5f,
    val signaturePosY: Float = 0.82f,
    val signedLocalAt: Long? = null,
    val signedServerAt: Long? = null,
    val signedPdfLocalPath: String? = null,
    val signedPdfStoragePath: String? = null,
    val signedPdfSha256: String? = null,
    val totalBytes: Long = 0L,
    val syncState: String = SyncState.PENDING,
    val serverUpdatedAt: Long? = null,
    val uploadError: String? = null
)

@Entity(
    tableName = "invoice_pages",
    foreignKeys = [
        ForeignKey(
            entity = InvoiceEntity::class,
            parentColumns = ["id"],
            childColumns = ["invoiceId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["invoiceId", "pageIndex"])]
)
data class InvoicePageEntity(
    @PrimaryKey val id: String,
    val invoiceId: String,
    val pageIndex: Int,
    val originalLocalPath: String,
    val editedLocalPath: String,
    val originalStoragePath: String? = null,
    val editedStoragePath: String? = null,
    val originalSha256: String = "",
    val editedSha256: String = "",
    val rotationDegrees: Int = 0,
    val contrastBoost: Boolean = false,
    val cropInsetPercent: Int = 0,
    val byteSize: Long = 0L,
    val mimeType: String = "image/jpeg"
)

@Entity(
    tableName = "audit_logs",
    indices = [Index(value = ["companyId", "createdAt"])]
)
data class AuditLogEntity(
    @PrimaryKey val id: String,
    val companyId: String = "",
    val actorUid: String = "",
    val actorName: String = "",
    val actorRole: String = "",
    val action: String,
    val targetType: String,
    val targetId: String,
    val summary: String,
    val createdAt: Long,
    val serverTimestamp: Long? = null,
    val syncState: String = SyncState.PENDING
)

@Entity(
    tableName = "sync_queue",
    indices = [Index(value = ["status", "nextAttemptAt"])]
)
data class SyncQueueEntity(
    @PrimaryKey val id: String,
    val companyId: String,
    val entityType: String, // store | job | invoice | invoice_file | employee | invitation | audit
    val entityId: String,
    val operation: String, // upsert | upload_file | delete
    val payloadJson: String = "{}",
    val retryCount: Int = 0,
    val status: String = SyncState.PENDING,
    val lastError: String? = null,
    val createdAt: Long,
    val nextAttemptAt: Long
)

@Entity(tableName = "preferences")
data class PreferenceEntity(
    @PrimaryKey val key: String,
    val value: String
)

@Entity(tableName = "drafts")
data class DraftEntity(
    @PrimaryKey val jobId: String,
    val isEditingExisting: Boolean = false,
    val storeId: String = "",
    val type: String = JobType.DELIVERY,
    val date: String = "",
    val time: String = "",
    val reminderEnabled: Boolean = true,
    val itemsJson: String = "[]",
    val updatedAt: Long = 0L
)

@Entity(tableName = "receipts")
data class ReceiptEntity(
    @PrimaryKey val receiptKey: String,
    val deliveredAt: Long
)

@Entity(tableName = "search_cache")
data class SearchCacheEntity(
    @PrimaryKey val cacheKey: String,
    val responseJson: String,
    val cachedAt: Long
)

data class StoreWithHours(
    @Embedded val store: StoreEntity,
    @Relation(parentColumn = "id", entityColumn = "storeId")
    val hours: List<StoreHoursEntity>
)

data class JobWithItems(
    @Embedded val job: JobEntity,
    @Relation(parentColumn = "id", entityColumn = "jobId")
    val items: List<JobItemEntity>
) {
    val sortedItems: List<JobItemEntity>
        get() = items.sortedBy { it.position }
}

data class InvoiceWithPages(
    @Embedded val invoice: InvoiceEntity,
    @Relation(parentColumn = "id", entityColumn = "invoiceId")
    val pages: List<InvoicePageEntity>
) {
    val sortedPages: List<InvoicePageEntity>
        get() = pages.sortedBy { it.pageIndex }
}

data class JobListRow(
    @Embedded val job: JobEntity,
    @ColumnInfo(name = "lineCount") val lineCount: Int,
    @ColumnInfo(name = "invoiceCount") val invoiceCount: Int = 0,
    @ColumnInfo(name = "signedInvoiceCount") val signedInvoiceCount: Int = 0,
    @ColumnInfo(name = "liveStoreName") val liveStoreName: String?,
    @ColumnInfo(name = "liveStoreAddress") val liveStoreAddress: String?,
    @ColumnInfo(name = "liveStoreLat") val liveStoreLat: Double?,
    @ColumnInfo(name = "liveStoreLon") val liveStoreLon: Double?,
    @ColumnInfo(name = "liveStorePhone") val liveStorePhone: String?
) {
    val displayStoreName: String
        get() = if (job.status == JobStatus.COMPLETED || job.status == JobStatus.CANCELED) {
            job.snapshotName
        } else {
            liveStoreName ?: job.snapshotName
        }

    val displayStoreAddress: String
        get() = if (job.status == JobStatus.COMPLETED || job.status == JobStatus.CANCELED) {
            job.snapshotAddress
        } else {
            liveStoreAddress ?: job.snapshotAddress
        }

    val displayLat: Double
        get() = if (job.status == JobStatus.COMPLETED || job.status == JobStatus.CANCELED) {
            job.snapshotLat
        } else {
            liveStoreLat ?: job.snapshotLat
        }

    val displayLon: Double
        get() = if (job.status == JobStatus.COMPLETED || job.status == JobStatus.CANCELED) {
            job.snapshotLon
        } else {
            liveStoreLon ?: job.snapshotLon
        }
}

data class AppPreferences(
    val language: String = "system",
    val area: String = SearchArea.LA,
    val daily: Boolean = true,
    val summaryMinute: Int = 420, // 07:00
    val jobReminders: Boolean = true,
    val advance: Int = 60,
    val companyTimezone: String = "America/Los_Angeles",
    val activeCompanyId: String = "",
    val activeUserUid: String = "",
    val activeUserName: String = "",
    val activeUserEmail: String = "",
    val activeUserRole: String = EmployeeRole.OWNER,
    val notifNewAssignment: Boolean = true,
    val notifUpcoming: Boolean = true,
    val notifChangedAssignment: Boolean = true,
    val notifCompleted: Boolean = true,
    val notifSignedInvoice: Boolean = true,
    val notifMissingSignature: Boolean = true,
    val notifFailedUpload: Boolean = true,
    val notifEmployeeInvite: Boolean = true
) {
    companion object {
        fun fromMap(map: Map<String, String>): AppPreferences {
            return AppPreferences(
                language = map[PrefKeys.LANGUAGE]?.takeIf { it in setOf("system", "en", "ru", "hy") } ?: "system",
                area = map[PrefKeys.AREA]?.takeIf { it in SearchArea.ALL } ?: SearchArea.LA,
                daily = map[PrefKeys.DAILY]?.toBooleanStrictOrNull() ?: true,
                summaryMinute = map[PrefKeys.SUMMARY_MINUTE]?.toIntOrNull()?.coerceIn(0, 1439) ?: 420,
                jobReminders = map[PrefKeys.JOB_REMINDERS]?.toBooleanStrictOrNull() ?: true,
                advance = map[PrefKeys.ADVANCE]?.toIntOrNull()?.coerceIn(1, 10080) ?: 60,
                companyTimezone = map[PrefKeys.COMPANY_TIMEZONE]?.takeIf { it.isNotBlank() } ?: "America/Los_Angeles",
                activeCompanyId = map[PrefKeys.ACTIVE_COMPANY_ID].orEmpty(),
                activeUserUid = map[PrefKeys.ACTIVE_USER_UID].orEmpty(),
                activeUserName = map[PrefKeys.ACTIVE_USER_NAME].orEmpty(),
                activeUserEmail = map[PrefKeys.ACTIVE_USER_EMAIL].orEmpty(),
                activeUserRole = map[PrefKeys.ACTIVE_USER_ROLE]?.takeIf { it in EmployeeRole.ALL } ?: EmployeeRole.OWNER,
                notifNewAssignment = map[PrefKeys.NOTIF_NEW_ASSIGNMENT]?.toBooleanStrictOrNull() ?: true,
                notifUpcoming = map[PrefKeys.NOTIF_UPCOMING]?.toBooleanStrictOrNull() ?: true,
                notifChangedAssignment = map[PrefKeys.NOTIF_CHANGED_ASSIGNMENT]?.toBooleanStrictOrNull() ?: true,
                notifCompleted = map[PrefKeys.NOTIF_COMPLETED]?.toBooleanStrictOrNull() ?: true,
                notifSignedInvoice = map[PrefKeys.NOTIF_SIGNED_INVOICE]?.toBooleanStrictOrNull() ?: true,
                notifMissingSignature = map[PrefKeys.NOTIF_MISSING_SIGNATURE]?.toBooleanStrictOrNull() ?: true,
                notifFailedUpload = map[PrefKeys.NOTIF_FAILED_UPLOAD]?.toBooleanStrictOrNull() ?: true,
                notifEmployeeInvite = map[PrefKeys.NOTIF_EMPLOYEE_INVITE]?.toBooleanStrictOrNull() ?: true
            )
        }
    }
}
