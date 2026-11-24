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
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface CompanyDao {
    @Query("SELECT * FROM companies ORDER BY created DESC")
    fun observeAll(): Flow<List<CompanyEntity>>

    @Query("SELECT * FROM companies WHERE id = :companyId")
    fun observeCompany(companyId: String): Flow<CompanyEntity?>

    @Query("SELECT * FROM companies WHERE id = :companyId")
    suspend fun getCompany(companyId: String): CompanyEntity?

    @Upsert
    suspend fun upsert(company: CompanyEntity)

    @Query("UPDATE companies SET storageUsedBytes = :bytes, modified = :modified WHERE id = :companyId")
    suspend fun updateStorageUsed(companyId: String, bytes: Long, modified: Long)

    @Query("SELECT * FROM companies WHERE ownerUid LIKE :prefix ORDER BY created DESC")
    suspend fun getCompaniesOwnedByPrefix(prefix: String): List<CompanyEntity>

    @Query(
        "SELECT DISTINCT c.* FROM companies c LEFT JOIN employees e ON e.companyId = c.id AND e.uid = :uid WHERE c.ownerUid = :uid OR (e.uid = :uid AND e.active = 1) ORDER BY c.created DESC"
    )
    suspend fun getCompaniesForUser(uid: String): List<CompanyEntity>
}

@Dao
interface EmployeeDao {
    @Query("SELECT * FROM employees WHERE companyId = :companyId ORDER BY active DESC, displayName COLLATE NOCASE ASC")
    fun observeByCompany(companyId: String): Flow<List<EmployeeEntity>>

    @Query("SELECT * FROM employees WHERE companyId = :companyId")
    suspend fun getByCompany(companyId: String): List<EmployeeEntity>

    @Query("SELECT * FROM employees WHERE uid = :uid")
    suspend fun getByUid(uid: String): EmployeeEntity?

    @Query("SELECT * FROM employees WHERE uid = :uid")
    fun observeByUid(uid: String): Flow<EmployeeEntity?>

    @Upsert
    suspend fun upsert(employee: EmployeeEntity)

    @Upsert
    suspend fun upsertAll(employees: List<EmployeeEntity>)

    @Query("UPDATE employees SET active = :active, lastActiveAt = :now WHERE uid = :uid")
    suspend fun setActive(uid: String, active: Boolean, now: Long)

    @Query("UPDATE employees SET role = :role, lastActiveAt = :now WHERE uid = :uid")
    suspend fun setRole(uid: String, role: String, now: Long)

    @Query("SELECT * FROM employees WHERE uid = :uid AND companyId = :companyId LIMIT 1")
    suspend fun getByUidInCompany(uid: String, companyId: String): EmployeeEntity?

    @Query("DELETE FROM employees WHERE uid = :uid AND companyId = :companyId")
    suspend fun deleteByUidInCompany(uid: String, companyId: String)
}

@Dao
interface InvitationDao {
    @Query("SELECT * FROM invitations WHERE companyId = :companyId ORDER BY createdAt DESC")
    fun observeByCompany(companyId: String): Flow<List<InvitationEntity>>

    @Query("SELECT * FROM invitations WHERE inviteCode = :code LIMIT 1")
    suspend fun findByCode(code: String): InvitationEntity?

    @Query("SELECT * FROM invitations WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): InvitationEntity?

    @Upsert
    suspend fun upsert(invitation: InvitationEntity)

    @Query("UPDATE invitations SET status = :status WHERE id = :id")
    suspend fun setStatus(id: String, status: String)
}

@Dao
interface StoreDao {
    @Transaction
    @Query("SELECT * FROM stores WHERE companyId = :companyId ORDER BY archived ASC, name COLLATE NOCASE ASC")
    fun observeStoresWithHoursByCompany(companyId: String): Flow<List<StoreWithHours>>

    @Transaction
    @Query("SELECT * FROM stores ORDER BY archived ASC, name COLLATE NOCASE ASC")
    fun observeAllStoresWithHours(): Flow<List<StoreWithHours>>

    @Transaction
    @Query("SELECT * FROM stores WHERE companyId = :companyId ORDER BY id ASC")
    suspend fun getStoresWithHoursByCompany(companyId: String): List<StoreWithHours>

    @Transaction
    @Query("SELECT * FROM stores ORDER BY id ASC")
    suspend fun getAllStoresWithHours(): List<StoreWithHours>

    @Query("SELECT * FROM stores WHERE companyId = ''")
    suspend fun getUnmigratedStores(): List<StoreEntity>

    @Transaction
    @Query("SELECT * FROM stores WHERE id = :storeId")
    fun observeStoreWithHours(storeId: String): Flow<StoreWithHours?>

    @Transaction
    @Query("SELECT * FROM stores WHERE companyId = :companyId AND id = :storeId")
    fun observeStoreWithHoursInCompany(companyId: String, storeId: String): Flow<StoreWithHours?>

    @Transaction
    @Query("SELECT * FROM stores WHERE id = :storeId")
    suspend fun getStoreWithHours(storeId: String): StoreWithHours?

    @Transaction
    @Query("SELECT * FROM stores WHERE companyId = :companyId AND id = :storeId")
    suspend fun getStoreWithHoursInCompany(companyId: String, storeId: String): StoreWithHours?

    @Query("SELECT * FROM stores WHERE id = :storeId")
    suspend fun getStore(storeId: String): StoreEntity?

    @Query("SELECT * FROM stores WHERE companyId = :companyId AND id = :storeId")
    suspend fun getStoreInCompany(companyId: String, storeId: String): StoreEntity?

    @Query("SELECT * FROM stores WHERE providerId = :providerId LIMIT 1")
    suspend fun findByProviderId(providerId: String): StoreEntity?

    @Query("SELECT * FROM stores WHERE companyId = :companyId AND providerId = :providerId LIMIT 1")
    suspend fun findByProviderIdInCompany(companyId: String, providerId: String): StoreEntity?

    @Query(
        "SELECT * FROM stores WHERE companyId = :companyId AND LOWER(TRIM(name)) = LOWER(TRIM(:name)) AND LOWER(TRIM(address)) = LOWER(TRIM(:address)) LIMIT 1"
    )
    suspend fun findDuplicateByNameAndAddress(companyId: String, name: String, address: String): StoreEntity?

    @Upsert
    suspend fun upsertStore(store: StoreEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHours(hours: List<StoreHoursEntity>)

    @Query("DELETE FROM hours WHERE storeId = :storeId")
    suspend fun deleteHoursForStore(storeId: String)

    @Query("UPDATE stores SET archived = :archived, modified = :modified, syncState = 'pending', version = version + 1 WHERE id = :storeId")
    suspend fun setArchived(storeId: String, archived: Boolean, modified: Long)

    @Query("UPDATE stores SET companyId = :companyId, syncState = 'pending' WHERE companyId = ''")
    suspend fun assignCompanyToUnmigrated(companyId: String)

    @Query("UPDATE stores SET syncState = :syncState, serverUpdatedAt = :serverTs WHERE id = :storeId")
    suspend fun markSyncState(storeId: String, syncState: String, serverTs: Long?)

    @Query("UPDATE stores SET updatedByUid = :newUid, syncState = 'pending' WHERE companyId = :companyId AND updatedByUid = :oldUid")
    suspend fun reassignUpdatedByUid(companyId: String, oldUid: String, newUid: String)
}

@Dao
interface JobDao {
    @Query(
        """
        SELECT j.*,
               (SELECT COUNT(*) FROM items i WHERE i.jobId = j.id) AS lineCount,
               (SELECT COUNT(*) FROM invoices inv WHERE inv.jobId = j.id AND inv.status != 'voided') AS invoiceCount,
               (SELECT COUNT(*) FROM invoices inv WHERE inv.jobId = j.id AND inv.signed = 1 AND inv.status != 'voided') AS signedInvoiceCount,
               s.name AS liveStoreName,
               s.address AS liveStoreAddress,
               s.lat AS liveStoreLat,
               s.lon AS liveStoreLon,
               s.phone AS liveStorePhone
        FROM jobs j
        LEFT JOIN stores s ON s.id = j.storeId
        WHERE j.companyId = :companyId
        ORDER BY j.date ASC, CASE WHEN j.time IS NULL THEN 1 ELSE 0 END ASC, j.time ASC, j.created ASC
        """
    )
    fun observeJobRowsByCompany(companyId: String): Flow<List<JobListRow>>

    @Query(
        """
        SELECT j.*,
               (SELECT COUNT(*) FROM items i WHERE i.jobId = j.id) AS lineCount,
               (SELECT COUNT(*) FROM invoices inv WHERE inv.jobId = j.id AND inv.status != 'voided') AS invoiceCount,
               (SELECT COUNT(*) FROM invoices inv WHERE inv.jobId = j.id AND inv.signed = 1 AND inv.status != 'voided') AS signedInvoiceCount,
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
    @Query("SELECT * FROM jobs WHERE companyId = :companyId AND id = :jobId")
    fun observeJobWithItemsInCompany(companyId: String, jobId: String): Flow<JobWithItems?>

    @Transaction
    @Query("SELECT * FROM jobs WHERE id = :jobId")
    suspend fun getJobWithItems(jobId: String): JobWithItems?

    @Transaction
    @Query("SELECT * FROM jobs WHERE companyId = :companyId AND id = :jobId")
    suspend fun getJobWithItemsInCompany(companyId: String, jobId: String): JobWithItems?

    @Transaction
    @Query("SELECT * FROM jobs WHERE companyId = :companyId ORDER BY id ASC")
    suspend fun getJobsWithItemsByCompany(companyId: String): List<JobWithItems>

    @Transaction
    @Query("SELECT * FROM jobs ORDER BY id ASC")
    suspend fun getAllJobsWithItems(): List<JobWithItems>

    @Query("SELECT * FROM jobs WHERE companyId = ''")
    suspend fun getUnmigratedJobs(): List<JobEntity>

    @Query("SELECT * FROM jobs WHERE companyId = :companyId AND status IN ('pending', 'progress')")
    suspend fun getActiveJobsByCompany(companyId: String): List<JobEntity>

    @Query("SELECT * FROM jobs WHERE status IN ('pending', 'progress')")
    suspend fun getActiveJobs(): List<JobEntity>

    @Query("SELECT COUNT(*) FROM jobs WHERE storeId = :storeId AND status IN ('pending', 'progress')")
    suspend fun countActiveJobsForStore(storeId: String): Int

    @Upsert
    suspend fun upsertJob(job: JobEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItems(items: List<JobItemEntity>)

    @Query("DELETE FROM items WHERE jobId = :jobId")
    suspend fun deleteItemsForJob(jobId: String)

    @Query("DELETE FROM jobs WHERE id = :jobId")
    suspend fun deleteJob(jobId: String)

    @Query("UPDATE jobs SET companyId = :companyId, syncState = 'pending' WHERE companyId = ''")
    suspend fun assignCompanyToUnmigrated(companyId: String)

    @Query("UPDATE jobs SET syncState = :syncState, serverUpdatedAt = :serverTs WHERE id = :jobId")
    suspend fun markSyncState(jobId: String, syncState: String, serverTs: Long?)

    @Query("UPDATE jobs SET updatedByUid = :newUid, syncState = 'pending' WHERE companyId = :companyId AND updatedByUid = :oldUid")
    suspend fun reassignUpdatedByUid(companyId: String, oldUid: String, newUid: String)

    @Query("UPDATE jobs SET assignedDriverUid = :newUid, assignedDriverName = :newName, syncState = 'pending' WHERE companyId = :companyId AND assignedDriverUid = :oldUid")
    suspend fun reassignAssignedDriverUid(companyId: String, oldUid: String, newUid: String, newName: String)
}

@Dao
interface InvoiceDao {
    @Transaction
    @Query("SELECT * FROM invoices WHERE companyId = :companyId ORDER BY createdAt DESC")
    fun observeInvoicesWithPagesByCompany(companyId: String): Flow<List<InvoiceWithPages>>

    @Transaction
    @Query("SELECT * FROM invoices ORDER BY createdAt DESC")
    fun observeAllInvoicesWithPages(): Flow<List<InvoiceWithPages>>

    @Transaction
    @Query("SELECT * FROM invoices WHERE companyId = :companyId AND jobId = :jobId ORDER BY version DESC, createdAt DESC")
    fun observeInvoicesForJobInCompany(companyId: String, jobId: String): Flow<List<InvoiceWithPages>>

    @Transaction
    @Query("SELECT * FROM invoices WHERE jobId = :jobId ORDER BY version DESC, createdAt DESC")
    fun observeInvoicesForJob(jobId: String): Flow<List<InvoiceWithPages>>

    @Transaction
    @Query("SELECT * FROM invoices WHERE companyId = :companyId ORDER BY createdAt DESC")
    suspend fun getInvoicesWithPagesByCompany(companyId: String): List<InvoiceWithPages>

    @Transaction
    @Query("SELECT * FROM invoices ORDER BY createdAt DESC")
    suspend fun getAllInvoicesWithPages(): List<InvoiceWithPages>

    @Transaction
    @Query("SELECT * FROM invoices WHERE id = :invoiceId")
    fun observeInvoice(invoiceId: String): Flow<InvoiceWithPages?>

    @Transaction
    @Query("SELECT * FROM invoices WHERE companyId = :companyId AND id = :invoiceId")
    fun observeInvoiceInCompany(companyId: String, invoiceId: String): Flow<InvoiceWithPages?>

    @Transaction
    @Query("SELECT * FROM invoices WHERE id = :invoiceId")
    suspend fun getInvoiceWithPages(invoiceId: String): InvoiceWithPages?

    @Transaction
    @Query("SELECT * FROM invoices WHERE companyId = :companyId AND id = :invoiceId")
    suspend fun getInvoiceWithPagesInCompany(companyId: String, invoiceId: String): InvoiceWithPages?

    @Query("SELECT * FROM invoices WHERE companyId = ''")
    suspend fun getUnmigratedInvoices(): List<InvoiceEntity>

    @Query("SELECT COALESCE(SUM(totalBytes), 0) FROM invoices WHERE companyId = :companyId")
    suspend fun sumStorageBytes(companyId: String): Long

    @Upsert
    suspend fun upsertInvoice(invoice: InvoiceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPages(pages: List<InvoicePageEntity>)

    @Query("DELETE FROM invoice_pages WHERE invoiceId = :invoiceId")
    suspend fun deletePagesForInvoice(invoiceId: String)

    @Query("UPDATE invoices SET companyId = :companyId, syncState = 'pending' WHERE companyId = ''")
    suspend fun assignCompanyToUnmigrated(companyId: String)

    @Query(
        "UPDATE invoices SET syncState = :syncState, status = :status, serverUpdatedAt = :serverTs, signedServerAt = COALESCE(:signedServerTs, signedServerAt), uploadError = :error WHERE id = :invoiceId"
    )
    suspend fun updateSyncAndStatus(
        invoiceId: String,
        syncState: String,
        status: String,
        serverTs: Long?,
        signedServerTs: Long?,
        error: String?
    )

    @Query("UPDATE invoices SET createdByUid = :newUid, syncState = 'pending' WHERE companyId = :companyId AND createdByUid = :oldUid")
    suspend fun reassignCreatedByUid(companyId: String, oldUid: String, newUid: String)

    @Query("UPDATE invoices SET assignedDriverUid = :newUid, syncState = 'pending' WHERE companyId = :companyId AND assignedDriverUid = :oldUid")
    suspend fun reassignAssignedDriverUid(companyId: String, oldUid: String, newUid: String)
}

@Dao
interface AuditLogDao {
    @Query("SELECT * FROM audit_logs ORDER BY createdAt DESC LIMIT 200")
    fun observeRecent(): Flow<List<AuditLogEntity>>

    @Query("SELECT * FROM audit_logs WHERE companyId = :companyId ORDER BY createdAt DESC LIMIT 200")
    fun observeByCompany(companyId: String): Flow<List<AuditLogEntity>>

    @Query("SELECT * FROM audit_logs WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AuditLogEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: AuditLogEntity)

    @Query("UPDATE audit_logs SET syncState = :syncState, serverTimestamp = :serverTs WHERE id = :id")
    suspend fun markSynced(id: String, syncState: String, serverTs: Long?)
}

@Dao
interface SyncQueueDao {
    @Query("SELECT COUNT(*) FROM sync_queue WHERE companyId = :companyId AND status != 'synced'")
    fun observePendingCountByCompany(companyId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM sync_queue WHERE companyId = :companyId AND status = 'failed'")
    fun observeFailedCountByCompany(companyId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM sync_queue WHERE status != 'synced'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM sync_queue WHERE status = 'failed'")
    fun observeFailedCount(): Flow<Int>

    @Query("SELECT * FROM sync_queue WHERE companyId = :companyId AND status != 'synced' AND nextAttemptAt <= :now ORDER BY createdAt ASC LIMIT 100")
    suspend fun getDueItemsForCompany(companyId: String, now: Long): List<SyncQueueEntity>

    @Query("SELECT * FROM sync_queue WHERE status != 'synced' AND nextAttemptAt <= :now ORDER BY createdAt ASC LIMIT 50")
    suspend fun getDueItems(now: Long): List<SyncQueueEntity>

    @Query("SELECT * FROM sync_queue WHERE companyId = :companyId AND entityType = :entityType AND entityId = :entityId AND operation = :operation LIMIT 1")
    suspend fun findExistingInCompany(companyId: String, entityType: String, entityId: String, operation: String): SyncQueueEntity?

    @Query("SELECT * FROM sync_queue WHERE entityType = :entityType AND entityId = :entityId AND operation = :operation LIMIT 1")
    suspend fun findExisting(entityType: String, entityId: String, operation: String): SyncQueueEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: SyncQueueEntity)

    @Query("DELETE FROM sync_queue WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM sync_queue WHERE status = 'synced'")
    suspend fun pruneSynced()

    @Query("UPDATE sync_queue SET status = 'pending', nextAttemptAt = 0 WHERE companyId = :companyId AND status = 'failed'")
    suspend fun resetFailedForCompany(companyId: String)

    @Query("UPDATE sync_queue SET status = 'pending', nextAttemptAt = 0 WHERE companyId = :companyId AND entityId = :entityId AND status = 'failed'")
    suspend fun resetFailedForEntity(companyId: String, entityId: String)

    @Query("SELECT COUNT(*) FROM sync_queue WHERE companyId = :companyId AND status != 'synced'")
    suspend fun getPendingCountNow(companyId: String): Int

    @Query("SELECT COUNT(*) FROM sync_queue WHERE companyId = :companyId AND status = 'failed'")
    suspend fun getFailedCountNow(companyId: String): Int

    @Query("SELECT * FROM sync_queue WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): SyncQueueEntity?

    @Query("SELECT * FROM sync_queue WHERE companyId = :companyId AND status = 'failed' ORDER BY createdAt DESC LIMIT 20")
    fun observeFailedItemsByCompany(companyId: String): Flow<List<SyncQueueEntity>>

    @Query("SELECT * FROM sync_queue WHERE status = 'failed' ORDER BY createdAt DESC LIMIT 20")
    fun observeFailedItems(): Flow<List<SyncQueueEntity>>

    @Query("SELECT * FROM sync_queue WHERE companyId = :companyId AND status = 'failed' ORDER BY createdAt DESC LIMIT 20")
    suspend fun getFailedItemsByCompany(companyId: String): List<SyncQueueEntity>

    @Query("SELECT lastError FROM sync_queue WHERE companyId = :companyId AND status = 'failed' AND lastError IS NOT NULL ORDER BY createdAt DESC LIMIT 1")
    fun observeLatestErrorByCompany(companyId: String): Flow<String?>

    @Query("UPDATE sync_queue SET status = 'pending', nextAttemptAt = 0 WHERE status = 'failed'")
    suspend fun resetFailedForRetry()
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
        CompanyEntity::class,
        EmployeeEntity::class,
        InvitationEntity::class,
        StoreEntity::class,
        StoreHoursEntity::class,
        JobEntity::class,
        JobItemEntity::class,
        InvoiceEntity::class,
        InvoicePageEntity::class,
        AuditLogEntity::class,
        SyncQueueEntity::class,
        PreferenceEntity::class,
        DraftEntity::class,
        ReceiptEntity::class,
        SearchCacheEntity::class
    ],
    version = 7,
    exportSchema = true
)
abstract class DeliFarDatabase : RoomDatabase() {
    abstract fun companyDao(): CompanyDao
    abstract fun employeeDao(): EmployeeDao
    abstract fun invitationDao(): InvitationDao
    abstract fun storeDao(): StoreDao
    abstract fun jobDao(): JobDao
    abstract fun invoiceDao(): InvoiceDao
    abstract fun auditLogDao(): AuditLogDao
    abstract fun syncQueueDao(): SyncQueueDao
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

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Extend existing stores & jobs without losing local data
                db.execSQL("ALTER TABLE `stores` ADD COLUMN `companyId` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `stores` ADD COLUMN `syncState` TEXT NOT NULL DEFAULT 'pending'")
                db.execSQL("ALTER TABLE `stores` ADD COLUMN `serverUpdatedAt` INTEGER")
                db.execSQL("ALTER TABLE `stores` ADD COLUMN `updatedByUid` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `stores` ADD COLUMN `version` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_stores_companyId` ON `stores` (`companyId`)")

                db.execSQL("ALTER TABLE `jobs` ADD COLUMN `companyId` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `jobs` ADD COLUMN `assignedDriverUid` TEXT")
                db.execSQL("ALTER TABLE `jobs` ADD COLUMN `assignedDriverName` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `jobs` ADD COLUMN `notes` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `jobs` ADD COLUMN `syncState` TEXT NOT NULL DEFAULT 'pending'")
                db.execSQL("ALTER TABLE `jobs` ADD COLUMN `serverUpdatedAt` INTEGER")
                db.execSQL("ALTER TABLE `jobs` ADD COLUMN `updatedByUid` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `jobs` ADD COLUMN `version` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_jobs_companyId_assignedDriverUid` ON `jobs` (`companyId`, `assignedDriverUid`)")

                // Companies
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `companies` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `ownerUid` TEXT NOT NULL,
                        `timezone` TEXT NOT NULL,
                        `defaultArea` TEXT NOT NULL,
                        `storageUsedBytes` INTEGER NOT NULL,
                        `storageQuotaBytes` INTEGER NOT NULL,
                        `created` INTEGER NOT NULL,
                        `modified` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_companies_ownerUid` ON `companies` (`ownerUid`)")

                // Employees
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `employees` (
                        `uid` TEXT NOT NULL,
                        `companyId` TEXT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `email` TEXT NOT NULL,
                        `phone` TEXT NOT NULL,
                        `role` TEXT NOT NULL,
                        `active` INTEGER NOT NULL,
                        `fcmToken` TEXT NOT NULL,
                        `invitedByUid` TEXT NOT NULL,
                        `joinedAt` INTEGER NOT NULL,
                        `lastActiveAt` INTEGER NOT NULL,
                        PRIMARY KEY(`uid`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_employees_companyId_active` ON `employees` (`companyId`, `active`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_employees_email` ON `employees` (`email`)")

                // Invitations
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `invitations` (
                        `id` TEXT NOT NULL,
                        `companyId` TEXT NOT NULL,
                        `companyName` TEXT NOT NULL,
                        `email` TEXT NOT NULL,
                        `role` TEXT NOT NULL,
                        `inviteCode` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `createdByUid` TEXT NOT NULL,
                        `createdByName` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `expiresAt` INTEGER NOT NULL,
                        `acceptedByUid` TEXT,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_invitations_companyId_status` ON `invitations` (`companyId`, `status`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_invitations_inviteCode` ON `invitations` (`inviteCode`)")

                // Invoices
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `invoices` (
                        `id` TEXT NOT NULL,
                        `companyId` TEXT NOT NULL,
                        `jobId` TEXT,
                        `storeId` TEXT NOT NULL,
                        `storeName` TEXT NOT NULL,
                        `invoiceNumber` TEXT NOT NULL,
                        `notes` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `version` INTEGER NOT NULL,
                        `supersedesInvoiceId` TEXT,
                        `voidedReason` TEXT,
                        `createdByUid` TEXT NOT NULL,
                        `createdByName` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `modifiedAt` INTEGER NOT NULL,
                        `signed` INTEGER NOT NULL,
                        `recipientName` TEXT NOT NULL,
                        `recipientTitle` TEXT NOT NULL,
                        `acknowledgmentConfirmed` INTEGER NOT NULL,
                        `signatureLocalPath` TEXT,
                        `signatureStoragePath` TEXT,
                        `signaturePageIndex` INTEGER NOT NULL,
                        `signaturePosX` REAL NOT NULL,
                        `signaturePosY` REAL NOT NULL,
                        `signedLocalAt` INTEGER,
                        `signedServerAt` INTEGER,
                        `signedPdfLocalPath` TEXT,
                        `signedPdfStoragePath` TEXT,
                        `signedPdfSha256` TEXT,
                        `totalBytes` INTEGER NOT NULL,
                        `syncState` TEXT NOT NULL,
                        `serverUpdatedAt` INTEGER,
                        `uploadError` TEXT,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_invoices_companyId_createdAt` ON `invoices` (`companyId`, `createdAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_invoices_jobId` ON `invoices` (`jobId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_invoices_storeId` ON `invoices` (`storeId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_invoices_invoiceNumber` ON `invoices` (`invoiceNumber`)")

                // Invoice pages
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `invoice_pages` (
                        `id` TEXT NOT NULL,
                        `invoiceId` TEXT NOT NULL,
                        `pageIndex` INTEGER NOT NULL,
                        `originalLocalPath` TEXT NOT NULL,
                        `editedLocalPath` TEXT NOT NULL,
                        `originalStoragePath` TEXT,
                        `editedStoragePath` TEXT,
                        `originalSha256` TEXT NOT NULL,
                        `editedSha256` TEXT NOT NULL,
                        `rotationDegrees` INTEGER NOT NULL,
                        `contrastBoost` INTEGER NOT NULL,
                        `cropInsetPercent` INTEGER NOT NULL,
                        `byteSize` INTEGER NOT NULL,
                        `mimeType` TEXT NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`invoiceId`) REFERENCES `invoices`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_invoice_pages_invoiceId_pageIndex` ON `invoice_pages` (`invoiceId`, `pageIndex`)")

                // Audit logs
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `audit_logs` (
                        `id` TEXT NOT NULL,
                        `companyId` TEXT NOT NULL,
                        `actorUid` TEXT NOT NULL,
                        `actorName` TEXT NOT NULL,
                        `actorRole` TEXT NOT NULL,
                        `action` TEXT NOT NULL,
                        `targetType` TEXT NOT NULL,
                        `targetId` TEXT NOT NULL,
                        `summary` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `serverTimestamp` INTEGER,
                        `syncState` TEXT NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_audit_logs_companyId_createdAt` ON `audit_logs` (`companyId`, `createdAt`)")

                // Sync queue
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sync_queue` (
                        `id` TEXT NOT NULL,
                        `companyId` TEXT NOT NULL,
                        `entityType` TEXT NOT NULL,
                        `entityId` TEXT NOT NULL,
                        `operation` TEXT NOT NULL,
                        `payloadJson` TEXT NOT NULL,
                        `retryCount` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `lastError` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `nextAttemptAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_queue_status_nextAttemptAt` ON `sync_queue` (`status`, `nextAttemptAt`)")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Track confirmed coordinates & geocoding source on stores
                db.execSQL("ALTER TABLE `stores` ADD COLUMN `locationConfirmed` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `stores` ADD COLUMN `geocodeSource` TEXT NOT NULL DEFAULT 'legacy'")
                // Scope providerId uniqueness per company so two companies can save the same storefront
                db.execSQL("DROP INDEX IF EXISTS `index_stores_providerId`")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_stores_companyId_providerId` ON `stores` (`companyId`, `providerId`)"
                )
                // Track assignedDriverUid on invoices for strict driver authorization & query parity
                db.execSQL("ALTER TABLE `invoices` ADD COLUMN `assignedDriverUid` TEXT")
                // Company-scoped sync queue index
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sync_queue_companyId_status_nextAttemptAt` ON `sync_queue` (`companyId`, `status`, `nextAttemptAt`)"
                )
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Flag legacy stores whose coordinates match the old silent downtown LA default (34.0522, -118.2437)
                // or invalid Null Island (0.0, 0.0) so users are prompted to review & confirm their pin.
                // Never delete or move existing store records.
                db.execSQL(
                    """
                    UPDATE `stores`
                    SET `locationConfirmed` = 0
                    WHERE `geocodeSource` = 'legacy'
                      AND (
                        (ABS(`lat` - 34.0522) <= 0.0015 AND ABS(`lon` - (-118.2437)) <= 0.0015)
                        OR (ABS(`lat`) < 0.0001 AND ABS(`lon`) < 0.0001)
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
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7
                    )
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
