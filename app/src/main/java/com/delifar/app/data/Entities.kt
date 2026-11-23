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
    const val OTHER = "other"
    val ALL = setOf(PARTIAL, CLOSED, REFUSED, OTHER)
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

object PrefKeys {
    const val LANGUAGE = "language"
    const val AREA = "area"
    const val DAILY = "daily"
    const val SUMMARY_MINUTE = "summaryMinute"
    const val JOB_REMINDERS = "jobReminders"
    const val ADVANCE = "advance"
    // Local-only service configuration (excluded from backup)
    const val SEARCH_URL = "searchUrl"
    const val TILE_URL = "tileUrl"

    val BACKUP_KEYS = setOf(LANGUAGE, AREA, DAILY, SUMMARY_MINUTE, JOB_REMINDERS, ADVANCE)
}

@Entity(
    tableName = "stores",
    indices = [
        Index(value = ["providerId"], unique = true),
        Index(value = ["archived", "name"])
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
    val archived: Boolean = false
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
        Index(value = ["status", "date"])
    ]
)
data class JobEntity(
    @PrimaryKey val id: String,
    val storeId: String,
    val type: String, // delivery | pickup
    val date: String, // YYYY-MM-DD in America/Los_Angeles
    val time: String?, // HH:mm or null
    val status: String, // pending | progress | completed | canceled
    val issue: String?, // partial | closed | refused | other | null
    val issueNote: String = "",
    val snapshotName: String,
    val snapshotAddress: String,
    val snapshotLat: Double,
    val snapshotLon: Double,
    val created: Long,
    val modified: Long,
    val completed: Long?,
    val reminderVersion: String,
    val reminderEnabled: Boolean = true
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

data class JobListRow(
    @Embedded val job: JobEntity,
    @ColumnInfo(name = "lineCount") val lineCount: Int,
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
    val searchUrl: String = DEFAULT_SEARCH_URL,
    val tileUrl: String = DEFAULT_TILE_URL
) {
    companion object {
        const val DEFAULT_SEARCH_URL = "https://photon.komoot.io/api/"
        const val DEFAULT_TILE_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"

        fun fromMap(map: Map<String, String>): AppPreferences {
            return AppPreferences(
                language = map[PrefKeys.LANGUAGE]?.takeIf { it in setOf("system", "en", "ru", "hy") } ?: "system",
                area = map[PrefKeys.AREA]?.takeIf { it in SearchArea.ALL } ?: SearchArea.LA,
                daily = map[PrefKeys.DAILY]?.toBooleanStrictOrNull() ?: true,
                summaryMinute = map[PrefKeys.SUMMARY_MINUTE]?.toIntOrNull()?.coerceIn(0, 1439) ?: 420,
                jobReminders = map[PrefKeys.JOB_REMINDERS]?.toBooleanStrictOrNull() ?: true,
                advance = map[PrefKeys.ADVANCE]?.toIntOrNull()?.coerceIn(1, 10080) ?: 60,
                searchUrl = map[PrefKeys.SEARCH_URL]?.takeIf { it.startsWith("https://") } ?: DEFAULT_SEARCH_URL,
                tileUrl = map[PrefKeys.TILE_URL]?.takeIf { it.startsWith("https://") } ?: DEFAULT_TILE_URL
            )
        }
    }
}
