package com.delifar.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.delifar.app.data.DeliFarDatabase
import com.delifar.app.data.DeliFarRepository
import com.delifar.app.data.DraftItemInput
import com.delifar.app.data.EmployeeRole
import com.delifar.app.data.HoursMode
import com.delifar.app.data.InvoicePageEntity
import com.delifar.app.data.InvoiceStatus
import com.delifar.app.data.JobType
import com.delifar.app.data.PrefKeys
import com.delifar.app.data.SearchArea
import com.delifar.app.data.StoreHoursEntity
import com.delifar.app.domain.BackupManager
import com.delifar.app.network.AddressGeocoderService
import com.delifar.app.network.GeocodeSource
import com.delifar.app.reminders.ReminderScheduler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeliFarDatabaseAndWorkflowTests {

    private lateinit var context: Context
    private lateinit var db: DeliFarDatabase
    private lateinit var repo: DeliFarRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, DeliFarDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = DeliFarRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun manualStoreCreation_preservesCustomNameRejectsInvalidCoordsHandlesDuplicatesAndEdits() = runTest {
        val company = repo.registerCompany(
            companyName = "Glendale Fresh Logistics",
            ownerUid = "owner_1",
            ownerName = "Armen",
            ownerEmail = "armen@example.com",
            timezone = "America/Los_Angeles",
            defaultArea = SearchArea.LA
        ).getOrThrow()

        // 1. Unconfirmed location MUST be rejected (never silently default to downtown LA)
        val unconfirmed = repo.saveStore(
            existingId = null,
            name = "My Custom Ararat Fish Market",
            address = "1100 S Brand Blvd, Glendale, CA 91204",
            lat = 34.1329,
            lon = -118.2551,
            providerId = null,
            phone = "818-555-0100",
            hours = emptyList(),
            companyId = company.id,
            actorUid = "owner_1",
            actorName = "Armen",
            actorRole = EmployeeRole.OWNER,
            locationConfirmed = false,
            geocodeSource = GeocodeSource.MANUAL_PIN
        )
        assertTrue(unconfirmed.isFailure)

        // 2. Null Island (0.0, 0.0) and out-of-range coordinates MUST be rejected
        val nullIsland = repo.saveStore(
            existingId = null,
            name = "Bad Coords Store",
            address = "1100 S Brand Blvd, Glendale, CA 91204",
            lat = 0.0,
            lon = 0.0,
            providerId = null,
            phone = "",
            hours = emptyList(),
            companyId = company.id,
            actorUid = "owner_1",
            actorRole = EmployeeRole.OWNER,
            locationConfirmed = true,
            geocodeSource = GeocodeSource.MANUAL_PIN
        )
        assertTrue(nullIsland.isFailure)

        // 3. Confirmed custom business name + real Glendale address succeeds verbatim
        val created = repo.saveStore(
            existingId = null,
            name = "My Custom Ararat Fish Market",
            address = "1100 S Brand Blvd, Glendale, CA 91204",
            lat = 34.1329,
            lon = -118.2551,
            providerId = "osm:n:9911",
            phone = "818-555-0100",
            hours = listOf(StoreHoursEntity("", 1, HoursMode.OPEN, "08:00-16:00")),
            companyId = company.id,
            actorUid = "owner_1",
            actorName = "Armen",
            actorRole = EmployeeRole.OWNER,
            locationConfirmed = true,
            geocodeSource = GeocodeSource.ANDROID_GEOCODER
        ).getOrThrow()

        assertEquals("My Custom Ararat Fish Market", created.name)
        assertEquals("1100 S Brand Blvd, Glendale, CA 91204", created.address)
        assertEquals(34.1329, created.lat, 0.00001)
        assertEquals(-118.2551, created.lon, 0.00001)
        assertTrue(created.locationConfirmed)

        // 4. Duplicate store with same name & address in same company is blocked
        val dupStore = repo.saveStore(
            existingId = null,
            name = "my custom ararat fish market",
            address = "1100 s brand blvd, glendale, ca 91204",
            lat = 34.1330,
            lon = -118.2550,
            providerId = null,
            phone = "",
            hours = emptyList(),
            companyId = company.id,
            actorUid = "owner_1",
            actorRole = EmployeeRole.OWNER,
            locationConfirmed = true,
            geocodeSource = GeocodeSource.MANUAL_PIN
        )
        assertTrue(dupStore.isFailure)
        assertEquals("DUPLICATE_STORE", dupStore.exceptionOrNull()?.message)

        // 5. Schedule a job for the store, then edit the store: must succeed AND preserve both job and receiving hours
        repo.saveJobAtomically(
            jobId = "job_for_edit_test",
            storeId = created.id,
            type = JobType.DELIVERY,
            dateIso = "2026-07-05",
            timeIso = "10:00",
            reminderEnabled = true,
            items = listOf(DraftItemInput(name = "Salmon", planned = "3", unit = "cs")),
            companyId = company.id,
            actorUid = "owner_1",
            actorRole = EmployeeRole.OWNER
        ).getOrThrow()

        val edited = repo.saveStore(
            existingId = created.id,
            name = "My Custom Ararat Fish Market",
            address = "1100 S Brand Blvd, Glendale, CA 91204",
            lat = 34.1335,
            lon = -118.2548,
            providerId = "osm:n:9911",
            phone = "818-555-0199",
            hours = listOf(StoreHoursEntity(created.id, 1, HoursMode.OPEN, "07:00-15:00")),
            companyId = company.id,
            actorUid = "owner_1",
            actorRole = EmployeeRole.OWNER,
            locationConfirmed = true,
            geocodeSource = GeocodeSource.MANUAL_PIN
        ).getOrThrow()

        assertEquals(created.id, edited.id)
        assertEquals(34.1335, edited.lat, 0.00001)
        assertEquals(2, edited.version)
        assertEquals(GeocodeSource.MANUAL_PIN, edited.geocodeSource)
        assertNotNull(db.jobDao().getJobWithItems("job_for_edit_test"))
    }

    @Test
    fun nominatimJsonParsingAndGeocodingFailure_skipsInvalidCoordsAndHandlesErrorsGracefully() = runTest {
        val service = AddressGeocoderService(context, repo)
        val sampleJson = """
            [
              {
                "osm_type": "node",
                "osm_id": 1001,
                "lat": "34.14251",
                "lon": "-118.25508",
                "name": "Glendale Galleria",
                "display_name": "100 W Broadway, Glendale, CA 91210, USA",
                "address": {
                  "house_number": "100",
                  "road": "W Broadway",
                  "city": "Glendale",
                  "state": "California",
                  "postcode": "91210"
                }
              },
              {
                "osm_type": "node",
                "osm_id": 1002,
                "lat": "0.0",
                "lon": "0.0",
                "name": "Invalid Null Island",
                "display_name": "Null Island"
              }
            ]
        """.trimIndent()

        val parsed = service.parseNominatimJson(sampleJson)
        assertEquals(1, parsed.size)
        assertEquals("Glendale Galleria", parsed[0].displayName)
        assertEquals("100 W Broadway, Glendale, California, 91210", parsed[0].formattedAddress)
        assertEquals(34.14251, parsed[0].lat, 0.0001)

        // Short address rejected with clear message (never fabricates LA coords)
        val tooShort = service.locateAddress(
            rawAddress = "ab",
            area = SearchArea.LA,
            languageTag = "en",
            osmBaseUrl = AddressGeocoderService.DEFAULT_NOMINATIM_URL,
            allowOsmFallback = false
        )
        assertTrue(tooShort.candidates.isEmpty())
        assertNotNull(tooShort.errorMessage)

        // Invalid HTTP geocoding server rejected cleanly
        val badUrl = service.locateAddress(
            rawAddress = "1100 S Brand Blvd, Glendale, CA",
            area = SearchArea.LA,
            languageTag = "en",
            osmBaseUrl = "http://127.0.0.1/search",
            allowOsmFallback = true
        )
        assertTrue(badUrl.candidates.isEmpty())
        assertNotNull(badUrl.errorMessage)
    }

    @Test
    fun offlineRestartPersistence_storesSurviveDatabaseCloseAndReopen() = runTest {
        val dbName = "delifar_restart_test_${UUID.randomUUID()}.db"
        val dbFile = context.getDatabasePath(dbName)
        try {
            val db1 = Room.databaseBuilder(context, DeliFarDatabase::class.java, dbName)
                .allowMainThreadQueries()
                .build()
            val repo1 = DeliFarRepository(db1)
            val comp = repo1.registerCompany(
                "Restart Co", "u1", "Owner", "o@example.com", "America/Los_Angeles", SearchArea.LA
            ).getOrThrow()
            val saved = repo1.saveStore(
                existingId = null,
                name = "Brand Blvd Bakery",
                address = "215 N Brand Blvd, Glendale, CA 91203",
                lat = 34.1494,
                lon = -118.2551,
                providerId = null,
                phone = "",
                hours = emptyList(),
                companyId = comp.id,
                actorUid = "u1",
                actorRole = EmployeeRole.OWNER,
                locationConfirmed = true,
                geocodeSource = GeocodeSource.MANUAL_PIN
            ).getOrThrow()
            db1.close()

            // Reopen fresh instance simulating app cold start while offline
            val db2 = Room.databaseBuilder(context, DeliFarDatabase::class.java, dbName)
                .allowMainThreadQueries()
                .build()
            val repo2 = DeliFarRepository(db2)
            val loaded = repo2.observeStores(comp.id).first()
            assertEquals(1, loaded.size)
            assertEquals(saved.id, loaded[0].store.id)
            assertEquals("Brand Blvd Bakery", loaded[0].store.name)
            assertEquals(34.1494, loaded[0].store.lat, 0.00001)
            assertEquals(-118.2551, loaded[0].store.lon, 0.00001)
            assertTrue(loaded[0].store.locationConfirmed)
            db2.close()
        } finally {
            dbFile.delete()
            File(dbFile.path + "-wal").delete()
            File(dbFile.path + "-shm").delete()
        }
    }

    @Test
    fun roomMigration5To6_preservesExistingStoresJobsInvoicesAndEnablesCompanyScopedProviderIds() = runTest {
        val dbName = "delifar_mig56_${UUID.randomUUID()}.db"
        val dbFile = context.getDatabasePath(dbName)
        try {
            // Create a genuine Room v5 schema database with existing user records
            val helper = FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration.builder(context)
                    .name(dbName)
                    .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL("""
                                CREATE TABLE companies (
                                    id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, ownerUid TEXT NOT NULL,
                                    timezone TEXT NOT NULL, defaultArea TEXT NOT NULL,
                                    storageUsedBytes INTEGER NOT NULL, storageQuotaBytes INTEGER NOT NULL,
                                    created INTEGER NOT NULL, modified INTEGER NOT NULL
                                )
                            """.trimIndent())
                            db.execSQL("CREATE INDEX index_companies_ownerUid ON companies (ownerUid)")
                            db.execSQL("""
                                CREATE TABLE employees (
                                    uid TEXT NOT NULL PRIMARY KEY, companyId TEXT NOT NULL, displayName TEXT NOT NULL,
                                    email TEXT NOT NULL, phone TEXT NOT NULL, role TEXT NOT NULL, active INTEGER NOT NULL,
                                    fcmToken TEXT NOT NULL, invitedByUid TEXT NOT NULL, joinedAt INTEGER NOT NULL,
                                    lastActiveAt INTEGER NOT NULL
                                )
                            """.trimIndent())
                            db.execSQL("CREATE INDEX index_employees_companyId_active ON employees (companyId, active)")
                            db.execSQL("CREATE INDEX index_employees_email ON employees (email)")
                            db.execSQL("""
                                CREATE TABLE invitations (
                                    id TEXT NOT NULL PRIMARY KEY, companyId TEXT NOT NULL, companyName TEXT NOT NULL,
                                    email TEXT NOT NULL, role TEXT NOT NULL, inviteCode TEXT NOT NULL, status TEXT NOT NULL,
                                    createdByUid TEXT NOT NULL, createdByName TEXT NOT NULL, createdAt INTEGER NOT NULL,
                                    expiresAt INTEGER NOT NULL, acceptedByUid TEXT
                                )
                            """.trimIndent())
                            db.execSQL("CREATE INDEX index_invitations_companyId_status ON invitations (companyId, status)")
                            db.execSQL("CREATE UNIQUE INDEX index_invitations_inviteCode ON invitations (inviteCode)")
                            db.execSQL("""
                                CREATE TABLE stores (
                                    id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, address TEXT NOT NULL,
                                    lat REAL NOT NULL, lon REAL NOT NULL, providerId TEXT, phone TEXT NOT NULL,
                                    created INTEGER NOT NULL, modified INTEGER NOT NULL, archived INTEGER NOT NULL,
                                    companyId TEXT NOT NULL, syncState TEXT NOT NULL, serverUpdatedAt INTEGER,
                                    updatedByUid TEXT NOT NULL, version INTEGER NOT NULL
                                )
                            """.trimIndent())
                            db.execSQL("CREATE UNIQUE INDEX index_stores_providerId ON stores (providerId)")
                            db.execSQL("CREATE INDEX index_stores_archived_name ON stores (archived, name)")
                            db.execSQL("CREATE INDEX index_stores_companyId ON stores (companyId)")
                            db.execSQL("""
                                CREATE TABLE hours (
                                    storeId TEXT NOT NULL, day INTEGER NOT NULL, mode TEXT NOT NULL, windows TEXT NOT NULL,
                                    PRIMARY KEY(storeId, day),
                                    FOREIGN KEY(storeId) REFERENCES stores(id) ON UPDATE NO ACTION ON DELETE CASCADE
                                )
                            """.trimIndent())
                            db.execSQL("CREATE INDEX index_hours_storeId ON hours (storeId)")
                            db.execSQL("""
                                CREATE TABLE jobs (
                                    id TEXT NOT NULL PRIMARY KEY, storeId TEXT NOT NULL, type TEXT NOT NULL,
                                    date TEXT NOT NULL, time TEXT, status TEXT NOT NULL, issue TEXT, issueNote TEXT NOT NULL,
                                    snapshotName TEXT NOT NULL, snapshotAddress TEXT NOT NULL, snapshotLat REAL NOT NULL,
                                    snapshotLon REAL NOT NULL, created INTEGER NOT NULL, modified INTEGER NOT NULL,
                                    completed INTEGER, reminderVersion TEXT NOT NULL, reminderEnabled INTEGER NOT NULL,
                                    companyId TEXT NOT NULL, assignedDriverUid TEXT, assignedDriverName TEXT NOT NULL,
                                    notes TEXT NOT NULL, syncState TEXT NOT NULL, serverUpdatedAt INTEGER,
                                    updatedByUid TEXT NOT NULL, version INTEGER NOT NULL,
                                    FOREIGN KEY(storeId) REFERENCES stores(id) ON UPDATE NO ACTION ON DELETE RESTRICT
                                )
                            """.trimIndent())
                            db.execSQL("CREATE INDEX index_jobs_storeId ON jobs (storeId)")
                            db.execSQL("CREATE INDEX index_jobs_date_time ON jobs (date, time)")
                            db.execSQL("CREATE INDEX index_jobs_status_date ON jobs (status, date)")
                            db.execSQL("CREATE INDEX index_jobs_companyId_assignedDriverUid ON jobs (companyId, assignedDriverUid)")
                            db.execSQL("""
                                CREATE TABLE items (
                                    id TEXT NOT NULL PRIMARY KEY, jobId TEXT NOT NULL, position INTEGER NOT NULL,
                                    name TEXT NOT NULL, planned TEXT NOT NULL, unit TEXT NOT NULL, actual TEXT,
                                    FOREIGN KEY(jobId) REFERENCES jobs(id) ON UPDATE NO ACTION ON DELETE CASCADE
                                )
                            """.trimIndent())
                            db.execSQL("CREATE INDEX index_items_jobId_position ON items (jobId, position)")
                            db.execSQL("""
                                CREATE TABLE invoices (
                                    id TEXT NOT NULL PRIMARY KEY, companyId TEXT NOT NULL, jobId TEXT,
                                    storeId TEXT NOT NULL, storeName TEXT NOT NULL, invoiceNumber TEXT NOT NULL,
                                    notes TEXT NOT NULL, status TEXT NOT NULL, version INTEGER NOT NULL,
                                    supersedesInvoiceId TEXT, voidedReason TEXT, createdByUid TEXT NOT NULL,
                                    createdByName TEXT NOT NULL, createdAt INTEGER NOT NULL, modifiedAt INTEGER NOT NULL,
                                    signed INTEGER NOT NULL, recipientName TEXT NOT NULL, recipientTitle TEXT NOT NULL,
                                    acknowledgmentConfirmed INTEGER NOT NULL, signatureLocalPath TEXT,
                                    signatureStoragePath TEXT, signaturePageIndex INTEGER NOT NULL,
                                    signaturePosX REAL NOT NULL, signaturePosY REAL NOT NULL, signedLocalAt INTEGER,
                                    signedServerAt INTEGER, signedPdfLocalPath TEXT, signedPdfStoragePath TEXT,
                                    signedPdfSha256 TEXT, totalBytes INTEGER NOT NULL, syncState TEXT NOT NULL,
                                    serverUpdatedAt INTEGER, uploadError TEXT
                                )
                            """.trimIndent())
                            db.execSQL("CREATE INDEX index_invoices_companyId_createdAt ON invoices (companyId, createdAt)")
                            db.execSQL("CREATE INDEX index_invoices_jobId ON invoices (jobId)")
                            db.execSQL("CREATE INDEX index_invoices_storeId ON invoices (storeId)")
                            db.execSQL("CREATE INDEX index_invoices_invoiceNumber ON invoices (invoiceNumber)")
                            db.execSQL("""
                                CREATE TABLE invoice_pages (
                                    id TEXT NOT NULL PRIMARY KEY, invoiceId TEXT NOT NULL, pageIndex INTEGER NOT NULL,
                                    originalLocalPath TEXT NOT NULL, editedLocalPath TEXT NOT NULL,
                                    originalStoragePath TEXT, editedStoragePath TEXT, originalSha256 TEXT NOT NULL,
                                    editedSha256 TEXT NOT NULL, rotationDegrees INTEGER NOT NULL,
                                    contrastBoost INTEGER NOT NULL, cropInsetPercent INTEGER NOT NULL,
                                    byteSize INTEGER NOT NULL, mimeType TEXT NOT NULL,
                                    FOREIGN KEY(invoiceId) REFERENCES invoices(id) ON UPDATE NO ACTION ON DELETE CASCADE
                                )
                            """.trimIndent())
                            db.execSQL("CREATE INDEX index_invoice_pages_invoiceId_pageIndex ON invoice_pages (invoiceId, pageIndex)")
                            db.execSQL("""
                                CREATE TABLE audit_logs (
                                    id TEXT NOT NULL PRIMARY KEY, companyId TEXT NOT NULL, actorUid TEXT NOT NULL,
                                    actorName TEXT NOT NULL, actorRole TEXT NOT NULL, action TEXT NOT NULL,
                                    targetType TEXT NOT NULL, targetId TEXT NOT NULL, summary TEXT NOT NULL,
                                    createdAt INTEGER NOT NULL, serverTimestamp INTEGER, syncState TEXT NOT NULL
                                )
                            """.trimIndent())
                            db.execSQL("CREATE INDEX index_audit_logs_companyId_createdAt ON audit_logs (companyId, createdAt)")
                            db.execSQL("""
                                CREATE TABLE sync_queue (
                                    id TEXT NOT NULL PRIMARY KEY, companyId TEXT NOT NULL, entityType TEXT NOT NULL,
                                    entityId TEXT NOT NULL, operation TEXT NOT NULL, payloadJson TEXT NOT NULL,
                                    retryCount INTEGER NOT NULL, status TEXT NOT NULL, lastError TEXT,
                                    createdAt INTEGER NOT NULL, nextAttemptAt INTEGER NOT NULL
                                )
                            """.trimIndent())
                            db.execSQL("CREATE INDEX index_sync_queue_status_nextAttemptAt ON sync_queue (status, nextAttemptAt)")
                            db.execSQL("CREATE TABLE preferences (`key` TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)")
                            db.execSQL("""
                                CREATE TABLE drafts (
                                    jobId TEXT NOT NULL PRIMARY KEY, isEditingExisting INTEGER NOT NULL,
                                    storeId TEXT NOT NULL, type TEXT NOT NULL, date TEXT NOT NULL,
                                    time TEXT NOT NULL, reminderEnabled INTEGER NOT NULL,
                                    itemsJson TEXT NOT NULL, updatedAt INTEGER NOT NULL
                                )
                            """.trimIndent())
                            db.execSQL("CREATE TABLE receipts (receiptKey TEXT NOT NULL PRIMARY KEY, deliveredAt INTEGER NOT NULL)")
                            db.execSQL("CREATE TABLE search_cache (cacheKey TEXT NOT NULL PRIMARY KEY, responseJson TEXT NOT NULL, cachedAt INTEGER NOT NULL)")

                            // Seed legacy v5 store, hours, and invoice
                            db.execSQL("""
                                INSERT INTO stores VALUES (
                                    'legacy_s1', 'Legacy Market', '500 N Central Ave, Glendale, CA',
                                    34.1520, -118.2580, 'shared_osm_id', '818-555-0111',
                                    1700000000000, 1700000000000, 0, 'compA', 'synced', NULL, 'ownerA', 1
                                )
                            """.trimIndent())
                            db.execSQL("INSERT INTO hours VALUES ('legacy_s1', 1, 'open', '08:00-17:00')")
                            db.execSQL("""
                                INSERT INTO invoices VALUES (
                                    'legacy_inv1', 'compA', NULL, 'legacy_s1', 'Legacy Market', 'INV-900',
                                    '', 'signed', 1, NULL, NULL, 'drvA', 'Driver A', 1700000000000, 1700000000000,
                                    1, 'Sam', 'Mgr', 1, NULL, NULL, 0, 0.5, 0.8, 1700000000000, NULL, NULL, NULL,
                                    'sha', 100, 'synced', NULL, NULL
                                )
                            """.trimIndent())
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    })
                    .build()
            )
            helper.writableDatabase.close()

            // Open via Room v6 with MIGRATION_5_6
            val migratedDb = Room.databaseBuilder(context, DeliFarDatabase::class.java, dbName)
                .addMigrations(DeliFarDatabase.MIGRATION_5_6)
                .allowMainThreadQueries()
                .build()
            val migratedRepo = DeliFarRepository(migratedDb)

            // Existing v5 store & hours survive intact with default locationConfirmed=true & geocodeSource='legacy'
            val legacyStore = migratedDb.storeDao().getStoreWithHours("legacy_s1")
            assertNotNull(legacyStore)
            assertEquals("Legacy Market", legacyStore!!.store.name)
            assertTrue(legacyStore.store.locationConfirmed)
            assertEquals("legacy", legacyStore.store.geocodeSource)
            assertEquals(1, legacyStore.hours.size)

            // Existing v5 invoice survives with assignedDriverUid=null
            val legacyInv = migratedDb.invoiceDao().getInvoiceWithPages("legacy_inv1")
            assertNotNull(legacyInv)
            assertEquals("INV-900", legacyInv!!.invoice.invoiceNumber)

            // Company B can now save a store with the SAME providerId ('shared_osm_id') without colliding with Company A!
            val compBStore = migratedRepo.saveStore(
                existingId = null,
                name = "CompB Same Provider Store",
                address = "500 N Central Ave, Glendale, CA",
                lat = 34.1520,
                lon = -118.2580,
                providerId = "shared_osm_id",
                phone = "",
                hours = emptyList(),
                companyId = "compB",
                actorUid = "ownerB",
                actorRole = EmployeeRole.OWNER,
                locationConfirmed = true,
                geocodeSource = GeocodeSource.OSM_NOMINATIM
            )
            assertTrue(compBStore.isSuccess)
            migratedDb.close()
        } finally {
            dbFile.delete()
            File(dbFile.path + "-wal").delete()
            File(dbFile.path + "-shm").delete()
        }
    }

    @Test
    fun companyIsolation_preventsCrossTenantQueriesEditsBackupsAndSyncQueueLeakage() = runTest {
        val compA = repo.registerCompany(
            "Alpha Foods", "ownerA", "Alice", "a@example.com", "America/Los_Angeles", SearchArea.LA
        ).getOrThrow()
        val compB = repo.registerCompany(
            "Beta Produce", "ownerB", "Bob", "b@example.com", "America/Los_Angeles", SearchArea.SAC
        ).getOrThrow()

        val storeA = repo.saveStore(
            null, "Alpha Store", "100 Main St, Glendale, CA", 34.1425, -118.2551,
            null, "", emptyList(), compA.id, "ownerA", "Alice", EmployeeRole.OWNER, true, GeocodeSource.MANUAL_PIN
        ).getOrThrow()
        val storeB = repo.saveStore(
            null, "Beta Store", "200 Capitol Mall, Sacramento, CA", 38.5790, -121.5030,
            null, "", emptyList(), compB.id, "ownerB", "Bob", EmployeeRole.OWNER, true, GeocodeSource.MANUAL_PIN
        ).getOrThrow()

        // Cross-company store edit rejected
        val crossEdit = repo.saveStore(
            storeA.id, "Hijacked", "100 Main St", 34.1425, -118.2551,
            null, "", emptyList(), compB.id, "ownerB", "Bob", EmployeeRole.OWNER, true, GeocodeSource.MANUAL_PIN
        )
        assertTrue(crossEdit.isFailure)
        assertTrue(crossEdit.exceptionOrNull() is SecurityException)

        // Cross-company job scheduling rejected
        val crossJob = repo.saveJobAtomically(
            jobId = "j_cross",
            storeId = storeA.id,
            type = JobType.DELIVERY,
            dateIso = "2026-07-01",
            timeIso = "10:00",
            reminderEnabled = true,
            items = listOf(DraftItemInput(name = "Milk", planned = "5", unit = "cs")),
            companyId = compB.id,
            actorUid = "ownerB",
            actorRole = EmployeeRole.OWNER
        )
        assertTrue(crossJob.isFailure)
        assertTrue(crossJob.exceptionOrNull() is SecurityException)

        // Scoped observations strictly isolate A and B
        assertEquals(listOf(storeA.id), repo.observeStores(compA.id).first().map { it.store.id })
        assertEquals(listOf(storeB.id), repo.observeStores(compB.id).first().map { it.store.id })

        // Sync queue strictly isolates A and B + deduplicates repeated enqueues
        repo.enqueueSyncInternal(compA.id, "store", storeA.id, "upsert")
        repo.enqueueSyncInternal(compA.id, "store", storeA.id, "upsert")
        val queueA = db.syncQueueDao().getDueItemsForCompany(compA.id, Long.MAX_VALUE)
        val queueB = db.syncQueueDao().getDueItemsForCompany(compB.id, Long.MAX_VALUE)
        assertTrue(queueA.all { it.companyId == compA.id })
        assertTrue(queueB.all { it.companyId == compB.id })
        assertEquals(1, queueA.count { it.entityType == "store" && it.entityId == storeA.id })

        // BackupManager export scoped strictly to active company
        val backupJsonA = BackupManager(db).exportJson(compA.id)
        assertTrue(backupJsonA.contains("Alpha Store"))
        assertFalse(backupJsonA.contains("Beta Store"))
    }

    @Test
    fun invoiceSigning_populatesAssignedDriverEnforcesImmutabilityAndSupportsVoidAndCorrection() = runTest {
        val comp = repo.registerCompany(
            "Deli Corp", "owner1", "Owner", "o@example.com", "America/Los_Angeles", SearchArea.LA
        ).getOrThrow()
        val store = repo.saveStore(
            null, "Corner Deli", "300 S Brand Blvd, Glendale, CA", 34.1400, -118.2551,
            null, "", emptyList(), comp.id, "owner1", "Owner", EmployeeRole.OWNER, true, GeocodeSource.MANUAL_PIN
        ).getOrThrow()
        val job = repo.saveJobAtomically(
            jobId = "job_1",
            storeId = store.id,
            type = JobType.DELIVERY,
            dateIso = "2026-07-02",
            timeIso = "09:00",
            reminderEnabled = true,
            items = listOf(DraftItemInput(name = "Cheese", planned = "4", unit = "cs")),
            assignedDriverUid = "drv_7",
            assignedDriverName = "Tigran",
            companyId = comp.id,
            actorUid = "owner1",
            actorName = "Owner",
            actorRole = EmployeeRole.OWNER
        ).getOrThrow()

        val invId = "inv_1"
        val created = repo.createInvoice(
            companyId = comp.id,
            jobId = job.id,
            storeId = store.id,
            storeName = store.name,
            invoiceNumber = "INV-2026-01",
            notes = "Cold items",
            pages = listOf(
                InvoicePageEntity(
                    id = "p1", invoiceId = invId, pageIndex = 0,
                    originalLocalPath = "/tmp/o.jpg", editedLocalPath = "/tmp/e.jpg",
                    originalSha256 = "aaa", editedSha256 = "bbb", byteSize = 2048
                )
            ),
            actorUid = "owner1",
            actorName = "Owner",
            actorRole = EmployeeRole.OWNER
        ).getOrThrow()

        // assignedDriverUid automatically inherited from linked job
        assertEquals("drv_7", created.invoice.assignedDriverUid)

        // Sign invoice
        val signed = repo.signInvoice(
            invoiceId = created.invoice.id,
            recipientName = "Elena Petrova",
            recipientTitle = "Manager",
            acknowledgmentConfirmed = true,
            signatureLocalPath = "/tmp/sig.png",
            signaturePageIndex = 0,
            signaturePosX = 0.5f,
            signaturePosY = 0.8f,
            signedPdfLocalPath = "/tmp/signed.pdf",
            signedPdfSha256 = "deadbeef",
            signedPdfBytes = 4096,
            actorUid = "drv_7",
            actorName = "Tigran",
            actorRole = EmployeeRole.DRIVER
        ).getOrThrow()
        assertTrue(signed.signed)

        // Re-signing already signed invoice MUST fail
        val reSign = repo.signInvoice(
            invoiceId = signed.id,
            recipientName = "Someone Else",
            recipientTitle = "",
            acknowledgmentConfirmed = true,
            signatureLocalPath = "/tmp/sig2.png",
            signaturePageIndex = 0,
            signaturePosX = 0.5f,
            signaturePosY = 0.8f,
            signedPdfLocalPath = "/tmp/signed2.pdf",
            signedPdfSha256 = "1234",
            signedPdfBytes = 1000,
            actorUid = "owner1",
            actorName = "Owner",
            actorRole = EmployeeRole.OWNER
        )
        assertTrue(reSign.isFailure)

        // Driver cannot void; Owner CAN void with reason
        assertTrue(repo.voidInvoice(signed.id, "Mistake", "drv_7", "Tigran", EmployeeRole.DRIVER).isFailure)
        val voided = repo.voidInvoice(signed.id, "Wrong item count", "owner1", "Owner", EmployeeRole.OWNER).getOrThrow()
        assertEquals(InvoiceStatus.VOIDED, voided.status)
        assertTrue(voided.signed) // Original signature record preserved

        // Create corrected version v2 superseding v1
        val v2 = repo.createCorrectedInvoiceVersion(signed.id, "Updated count", "owner1", "Owner", EmployeeRole.OWNER).getOrThrow()
        assertEquals(2, v2.invoice.version)
        assertEquals(signed.id, v2.invoice.supersedesInvoiceId)
        assertFalse(v2.invoice.signed)
    }

    @Test
    fun reminderScheduler_neverRecordsReceiptWhenDeliveryFailsAndPreventsDuplicatesWhenDelivered() = runTest {
        val comp = repo.registerCompany(
            "Reminder Co", "owner1", "Owner", "o@example.com", "America/Los_Angeles", SearchArea.LA
        ).getOrThrow()
        val store = repo.saveStore(
            null, "Morning Market", "400 N Brand Blvd, Glendale, CA", 34.1510, -118.2551,
            null, "", emptyList(), comp.id, "owner1", "Owner", EmployeeRole.OWNER, true, GeocodeSource.MANUAL_PIN
        ).getOrThrow()

        // Job scheduled for 2026-06-15 at 10:00 America/Los_Angeles (17:00 UTC in PDT)
        val job = repo.saveJobAtomically(
            jobId = "job_rem_1",
            storeId = store.id,
            type = JobType.DELIVERY,
            dateIso = "2026-06-15",
            timeIso = "10:00",
            reminderEnabled = true,
            items = listOf(DraftItemInput(name = "Bread", planned = "20", unit = "ea")),
            assignedDriverUid = "drv_1",
            assignedDriverName = "Driver One",
            companyId = comp.id,
            actorUid = "owner1",
            actorName = "Owner",
            actorRole = EmployeeRole.OWNER
        ).getOrThrow()

        // Configure 60-minute advance reminder; disable daily summary so we test job reminder cleanly
        repo.setPreference(PrefKeys.DAILY, "false")
        repo.setPreference(PrefKeys.JOB_REMINDERS, "true")
        repo.setPreference(PrefKeys.NOTIF_UPCOMING, "true")
        repo.setPreference(PrefKeys.ADVANCE, "60")

        // 09:15 PDT = 16:15 UTC (inside the 60-minute advance window before 10:00 PDT)
        val dueInstant = Instant.parse("2026-06-15T16:15:00Z")
        val receiptKey = "job:${job.id}:${job.reminderVersion}"

        // 1. Simulate denied notification permission -> poster returns false
        val deniedResult = ReminderScheduler.rebuildAndDispatchIfDue(
            context = context,
            db = db,
            nowInstant = dueInstant,
            scheduleAlarm = false,
            poster = { _, _, _, _ -> false }
        )
        assertEquals(0, deniedResult.deliveredCount)
        assertEquals(1, deniedResult.failedOrSuppressedCount)
        assertFalse(repo.hasReceipt(receiptKey)) // CRITICAL: must NOT record receipt on failed delivery

        // 2. User grants permission -> poster returns true
        val deliveredTitles = mutableListOf<String>()
        val grantedResult = ReminderScheduler.rebuildAndDispatchIfDue(
            context = context,
            db = db,
            nowInstant = dueInstant,
            scheduleAlarm = false,
            poster = { title, _, _, _ ->
                deliveredTitles += title
                true
            }
        )
        assertEquals(1, grantedResult.deliveredCount)
        assertEquals(0, grantedResult.failedOrSuppressedCount)
        assertEquals(1, deliveredTitles.size)
        assertTrue(repo.hasReceipt(receiptKey))

        // 3. Running again does NOT fire duplicate notification
        val repeatResult = ReminderScheduler.rebuildAndDispatchIfDue(
            context = context,
            db = db,
            nowInstant = dueInstant,
            scheduleAlarm = false,
            poster = { _, _, _, _ -> true }
        )
        assertEquals(0, repeatResult.deliveredCount)
        assertEquals(0, repeatResult.failedOrSuppressedCount)
    }
}
