package com.delifar.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface StoreDao {
    @Transaction
    @Query("SELECT * FROM stores ORDER BY archived ASC, name COLLATE NOCASE ASC")
    fun observeAllStoresWithHours(): Flow<List<StoreWithHours>>

    @Transaction
    @Query("SELECT * FROM stores ORDER BY id ASC")
    suspend fun getAllStoresWithHours(): List<StoreWithHours>

    @Transaction
    @Query("SELECT * FROM stores WHERE id = :storeId")
    fun observeStoreWithHours(storeId: String): Flow<StoreWithHours?>

    @Transaction
    @Query("SELECT * FROM stores WHERE id = :storeId")
    suspend fun getStoreWithHours(storeId: String): StoreWithHours?

    @Query("SELECT * FROM stores WHERE id = :storeId")
    suspend fun getStore(storeId: String): StoreEntity?

    @Query("SELECT * FROM stores WHERE providerId = :providerId LIMIT 1")
    suspend fun findByProviderId(providerId: String): StoreEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStore(store: StoreEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHours(hours: List<StoreHoursEntity>)

    @Query("DELETE FROM hours WHERE storeId = :storeId")
    suspend fun deleteHoursForStore(storeId: String)

    @Query("UPDATE stores SET archived = :archived, modified = :modified WHERE id = :storeId")
    suspend fun setArchived(storeId: String, archived: Boolean, modified: Long)
}

@Dao
interface JobDao {
    @Query(
        """
        SELECT j.*,
               (SELECT COUNT(*) FROM items i WHERE i.jobId = j.id) AS lineCount,
               s.name AS liveStoreName,
               s.address AS liveStoreAddress,
               s.lat AS liveStoreLat,
               s.lon AS liveStoreLon,
               s.phone AS liveStorePhone
        FROM jobs j
        LEFT JOIN stores s ON s.id = j.storeId
        ORDER BY j.date ASC, CASE WHEN j.time IS NULL THEN 1 ELSE 0 END ASC, j.time ASC, j.created ASC
        """
    )
    fun observeAllJobRows(): Flow<List<JobListRow>>

    @Transaction
    @Query("SELECT * FROM jobs WHERE id = :jobId")
    fun observeJobWithItems(jobId: String): Flow<JobWithItems?>

    @Transaction
    @Query("SELECT * FROM jobs WHERE id = :jobId")
    suspend fun getJobWithItems(jobId: String): JobWithItems?

    @Transaction
    @Query("SELECT * FROM jobs ORDER BY id ASC")
    suspend fun getAllJobsWithItems(): List<JobWithItems>

    @Query("SELECT * FROM jobs WHERE status IN ('pending', 'progress')")
    suspend fun getActiveJobs(): List<JobEntity>

    @Query("SELECT COUNT(*) FROM jobs WHERE storeId = :storeId AND status IN ('pending', 'progress')")
    suspend fun countActiveJobsForStore(storeId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertJob(job: JobEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItems(items: List<JobItemEntity>)

    @Query("DELETE FROM items WHERE jobId = :jobId")
    suspend fun deleteItemsForJob(jobId: String)

    @Query("DELETE FROM jobs WHERE id = :jobId")
    suspend fun deleteJob(jobId: String)
}

@Dao
interface PreferenceDao {
    @Query("SELECT * FROM preferences")
    fun observeAll(): Flow<List<PreferenceEntity>>

    @Query("SELECT * FROM preferences")
    suspend fun getAll(): List<PreferenceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(pref: PreferenceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(prefs: List<PreferenceEntity>)
}

@Dao
interface DraftDao {
    @Query("SELECT * FROM drafts ORDER BY updatedAt DESC LIMIT 1")
    fun observeLatestDraft(): Flow<DraftEntity?>

    @Query("SELECT * FROM drafts WHERE jobId = :jobId")
    suspend fun getDraft(jobId: String): DraftEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(draft: DraftEntity)

    @Query("DELETE FROM drafts WHERE jobId = :jobId")
    suspend fun delete(jobId: String)

    @Query("DELETE FROM drafts")
    suspend fun clearAll()
}

@Dao
interface ReceiptDao {
    @Query("SELECT EXISTS(SELECT 1 FROM receipts WHERE receiptKey = :key)")
    suspend fun exists(key: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(receipt: ReceiptEntity)

    @Query("DELETE FROM receipts WHERE deliveredAt < :cutoff")
    suspend fun pruneOlderThan(cutoff: Long)
}

@Dao
interface SearchCacheDao {
    @Query("SELECT * FROM search_cache WHERE cacheKey = :key")
    suspend fun get(key: String): SearchCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: SearchCacheEntity)

    @Query("DELETE FROM search_cache WHERE cachedAt < :cutoff")
    suspend fun pruneOlderThan(cutoff: Long)
}

@Database(
    entities = [
        StoreEntity::class,
        StoreHoursEntity::class,
        JobEntity::class,
        JobItemEntity::class,
        PreferenceEntity::class,
        DraftEntity::class,
        ReceiptEntity::class,
        SearchCacheEntity::class
    ],
    version = 4,
    exportSchema = true
)
abstract class DeliFarDatabase : RoomDatabase() {
    abstract fun storeDao(): StoreDao
    abstract fun jobDao(): JobDao
    abstract fun preferenceDao(): PreferenceDao
    abstract fun draftDao(): DraftDao
    abstract fun receiptDao(): ReceiptDao
    abstract fun searchCacheDao(): SearchCacheDao

    companion object {
        @Volatile
        private var INSTANCE: DeliFarDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `drafts` (
                        `jobId` TEXT NOT NULL,
                        `isEditingExisting` INTEGER NOT NULL DEFAULT 0,
                        `storeId` TEXT NOT NULL DEFAULT '',
                        `type` TEXT NOT NULL DEFAULT 'delivery',
                        `date` TEXT NOT NULL DEFAULT '',
                        `time` TEXT NOT NULL DEFAULT '',
                        `reminderEnabled` INTEGER NOT NULL DEFAULT 1,
                        `itemsJson` TEXT NOT NULL DEFAULT '[]',
                        `updatedAt` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`jobId`)
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `receipts` (
                        `receiptKey` TEXT NOT NULL,
                        `deliveredAt` INTEGER NOT NULL,
                        PRIMARY KEY(`receiptKey`)
                    )
                    """.trimIndent()
                )
                db.execSQL("ALTER TABLE `jobs` ADD COLUMN `reminderVersion` TEXT NOT NULL DEFAULT 'v1'")
                db.execSQL("ALTER TABLE `jobs` ADD COLUMN `reminderEnabled` INTEGER NOT NULL DEFAULT 1")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `search_cache` (
                        `cacheKey` TEXT NOT NULL,
                        `responseJson` TEXT NOT NULL,
                        `cachedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`cacheKey`)
                    )
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): DeliFarDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    DeliFarDatabase::class.java,
                    "delifar.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
