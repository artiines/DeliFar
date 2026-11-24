package com.delifar.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.delifar.app.cloud.AuditRemoteGateway
import com.delifar.app.cloud.CloudSyncManager
import com.delifar.app.cloud.FirebaseServices
import com.delifar.app.cloud.IdempotentAuditUploader
import com.delifar.app.cloud.InvoiceSyncValidator
import com.delifar.app.cloud.InvoiceUploadErrorCode
import com.delifar.app.cloud.InvoiceUploadException
import com.delifar.app.cloud.RemoteAuditDocument
import com.delifar.app.cloud.SyncFlushOutcome
import com.delifar.app.data.DeliFarDatabase
import com.delifar.app.data.DeliFarRepository
import com.delifar.app.data.DraftItemInput
import com.delifar.app.data.EmployeeRole
import com.delifar.app.data.HoursMode
import com.delifar.app.data.InvoicePageEntity
import com.delifar.app.data.InvoiceStatus
import com.delifar.app.data.InvoiceWithPages
import com.delifar.app.data.JobIssue
import com.delifar.app.data.JobStatus
import com.delifar.app.data.JobType
import com.delifar.app.data.PrefKeys
import com.delifar.app.data.SearchArea
import com.delifar.app.data.StoreHoursEntity
import com.delifar.app.data.SyncState
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.delifar.app.domain.BackupManager
import com.delifar.app.domain.EncryptedArchiveBackupManager
import com.delifar.app.network.AddressGeocoderService
import com.delifar.app.network.GeoValidation
import com.delifar.app.network.GeocodeSource
import com.delifar.app.reminders.ReminderScheduler
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertNull
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
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

                            // Seed legacy v5 stores (one real Glendale store, one suspect silent downtown-LA default), hours, and invoice
                            db.execSQL("""
                                INSERT INTO stores VALUES (
                                    'legacy_s1', 'Legacy Market', '500 N Central Ave, Glendale, CA',
                                    34.1520, -118.2580, 'shared_osm_id', '818-555-0111',
                                    1700000000000, 1700000000000, 0, 'compA', 'synced', NULL, 'ownerA', 1
                                )
                            """.trimIndent())
                            db.execSQL("""
                                INSERT INTO stores VALUES (
                                    'legacy_suspect_la', 'Unverified Legacy Store', '1234 Oak St, Burbank, CA',
                                    34.0522, -118.2437, NULL, '',
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

            // Open via Room v7 with MIGRATION_5_6 and MIGRATION_6_7
            val migratedDb = Room.databaseBuilder(context, DeliFarDatabase::class.java, dbName)
                .addMigrations(DeliFarDatabase.MIGRATION_5_6, DeliFarDatabase.MIGRATION_6_7)
                .allowMainThreadQueries()
                .build()
            val migratedRepo = DeliFarRepository(migratedDb)

            // Existing v5 store with real Glendale coords stays locationConfirmed=true
            val legacyStore = migratedDb.storeDao().getStoreWithHours("legacy_s1")
            assertNotNull(legacyStore)
            assertEquals("Legacy Market", legacyStore!!.store.name)
            assertTrue(legacyStore.store.locationConfirmed)
            assertEquals("legacy", legacyStore.store.geocodeSource)
            assertEquals(1, legacyStore.hours.size)

            // Existing v5 store with suspect downtown-LA fallback (34.0522, -118.2437) is PRESERVED (not deleted/moved)
            // and flagged locationConfirmed=false so the user is prompted to review & confirm its pin
            val suspectStore = migratedDb.storeDao().getStoreWithHours("legacy_suspect_la")
            assertNotNull(suspectStore)
            assertEquals("Unverified Legacy Store", suspectStore!!.store.name)
            assertEquals(34.0522, suspectStore.store.lat, 0.00001)
            assertEquals(-118.2437, suspectStore.store.lon, 0.00001)
            assertFalse(suspectStore.store.locationConfirmed)
            assertTrue(GeoValidation.isSuspectDefaultLosAngelesCoord(suspectStore.store.lat, suspectStore.store.lon))

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

    @Test
    fun invoiceSyncValidator_blocksMissingEmptyOversizedCorruptedFilesAndPartialMetadataSync() = runTest {
        val tempDir = File(context.cacheDir, "inv_sync_test_${UUID.randomUUID()}").apply { mkdirs() }
        try {
            // 1. Missing file fails explicitly with MISSING_FILE (never silently skipped!)
            val missingEx = runCatching {
                InvoiceSyncValidator.validateLocalAttachment(
                    localPath = File(tempDir, "does_not_exist.jpg").absolutePath,
                    label = "page 1",
                    expectedMimeType = "image/jpeg"
                )
            }.exceptionOrNull() as? InvoiceUploadException
            assertNotNull(missingEx)
            assertEquals(InvoiceUploadErrorCode.MISSING_FILE, missingEx!!.code)

            // 2. Empty file fails with EMPTY_FILE
            val emptyFile = File(tempDir, "empty.jpg").apply { writeBytes(ByteArray(0)) }
            val emptyEx = runCatching {
                InvoiceSyncValidator.validateLocalAttachment(emptyFile.absolutePath, "empty", "image/jpeg")
            }.exceptionOrNull() as? InvoiceUploadException
            assertEquals(InvoiceUploadErrorCode.EMPTY_FILE, emptyEx?.code)

            // 3. Wrong magic bytes fails with INVALID_CONTENT_TYPE
            val fakePdf = File(tempDir, "fake.pdf").apply { writeBytes("NOT A REAL PDF".toByteArray()) }
            val mimeEx = runCatching {
                InvoiceSyncValidator.validateLocalAttachment(fakePdf.absolutePath, "fake pdf", "application/pdf")
            }.exceptionOrNull() as? InvoiceUploadException
            assertEquals(InvoiceUploadErrorCode.INVALID_CONTENT_TYPE, mimeEx?.code)

            // 4. Oversized file fails with OVERSIZED_FILE
            val bigJpg = File(tempDir, "big.jpg").apply {
                writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(2048))
            }
            val sizeEx = runCatching {
                InvoiceSyncValidator.validateLocalAttachment(bigJpg.absolutePath, "big", "image/jpeg", maxBytes = 1024L)
            }.exceptionOrNull() as? InvoiceUploadException
            assertEquals(InvoiceUploadErrorCode.OVERSIZED_FILE, sizeEx?.code)

            // 5. Valid JPEG + SHA-256 mismatch rejected with CHECKSUM_MISMATCH
            val validJpgBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4)
            val validJpg = File(tempDir, "orig.jpg").apply { writeBytes(validJpgBytes) }
            val shaEx = runCatching {
                InvoiceSyncValidator.validateLocalAttachment(
                    validJpg.absolutePath, "orig", "image/jpeg", expectedSha256 = "000000000000"
                )
            }.exceptionOrNull() as? InvoiceUploadException
            assertEquals(InvoiceUploadErrorCode.CHECKSUM_MISMATCH, shaEx?.code)

            // 6. Valid JPEG, PNG, and PDF pass with matching SHA-256
            val expectedJpgSha = InvoiceSyncValidator.sha256Bytes(validJpgBytes)
            val validatedJpg = InvoiceSyncValidator.validateLocalAttachment(
                validJpg.absolutePath, "orig", "image/jpeg", expectedSha256 = expectedJpgSha
            )
            assertEquals(expectedJpgSha, validatedJpg.sha256)
            assertEquals("image/jpeg", validatedJpg.contentType)

            val validPngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2)
            val validPng = File(tempDir, "sig.png").apply { writeBytes(validPngBytes) }
            assertEquals("image/png", InvoiceSyncValidator.validateLocalAttachment(validPng.absolutePath, "sig", "image/png").contentType)

            val validPdfBytes = "%PDF-1.4\n%%EOF\n".toByteArray(Charsets.US_ASCII)
            val validPdf = File(tempDir, "signed.pdf").apply { writeBytes(validPdfBytes) }
            assertEquals("application/pdf", InvoiceSyncValidator.validateLocalAttachment(validPdf.absolutePath, "pdf", "application/pdf").contentType)

            // 7. Partially uploaded invoice MUST be blocked from transitioning to SYNCHRONIZED
            val comp = repo.registerCompany("Sync Co", "u1", "Owner", "o@example.com", "America/Los_Angeles", SearchArea.LA).getOrThrow()
            val store = repo.saveStore(
                null, "Sync Store", "100 Main St, Glendale, CA", 34.1425, -118.2551,
                null, "", emptyList(), comp.id, "u1", "Owner", EmployeeRole.OWNER, true, GeocodeSource.MANUAL_PIN
            ).getOrThrow()
            val created = repo.createInvoice(
                companyId = comp.id, jobId = null, storeId = store.id, storeName = store.name,
                invoiceNumber = "INV-SYNC-1", notes = "",
                pages = listOf(
                    InvoicePageEntity(
                        id = "pg1", invoiceId = "inv_sync_1", pageIndex = 0,
                        originalLocalPath = validJpg.absolutePath, editedLocalPath = validJpg.absolutePath,
                        originalSha256 = expectedJpgSha, editedSha256 = expectedJpgSha, byteSize = validJpg.length()
                    )
                ),
                actorUid = "u1", actorName = "Owner", actorRole = EmployeeRole.OWNER
            ).getOrThrow()
            val signedInv = repo.signInvoice(
                invoiceId = created.invoice.id, recipientName = "Sam Receiver", recipientTitle = "Lead",
                acknowledgmentConfirmed = true, signatureLocalPath = validPng.absolutePath,
                signaturePageIndex = 0, signaturePosX = 0.5f, signaturePosY = 0.8f,
                signedPdfLocalPath = validPdf.absolutePath, signedPdfSha256 = InvoiceSyncValidator.sha256Bytes(validPdfBytes),
                signedPdfBytes = validPdf.length(), actorUid = "u1", actorName = "Owner", actorRole = EmployeeRole.OWNER,
                companyId = comp.id
            ).getOrThrow()

            val incompleteIwp = InvoiceWithPages(signedInv, created.pages)
            val incompleteEx = runCatching {
                InvoiceSyncValidator.assertInvoiceReadyForMetadataSync(incompleteIwp)
            }.exceptionOrNull() as? InvoiceUploadException
            assertEquals(InvoiceUploadErrorCode.INCOMPLETE_REMOTE_ATTACHMENTS, incompleteEx?.code)

            // Once every remote path is confirmed, readiness check passes
            val completeIwp = InvoiceWithPages(
                signedInv.copy(
                    signatureStoragePath = "companies/${comp.id}/invoices/${signedInv.id}/signature.png",
                    signedPdfStoragePath = "companies/${comp.id}/invoices/${signedInv.id}/signed_v1.pdf"
                ),
                created.pages.map {
                    it.copy(
                        originalStoragePath = "companies/${comp.id}/invoices/${signedInv.id}/orig_pg1.jpg",
                        editedStoragePath = "companies/${comp.id}/invoices/${signedInv.id}/edit_pg1.jpg"
                    )
                }
            )
            InvoiceSyncValidator.assertInvoiceReadyForMetadataSync(completeIwp)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun encryptedBackupArchive_roundTripCleanDeviceConflictsCrossCompanyCorruptionAndRollback() = runTest {
        val workDir = File(context.cacheDir, "backup_v2_test_${UUID.randomUUID()}").apply { mkdirs() }
        val cleanDb = Room.inMemoryDatabaseBuilder(context, DeliFarDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            // Seed source DB with full company, store + hours, job + items, signed invoice + binary attachments
            val comp = repo.registerCompany(
                "Ararat Foods Co", "owner_uid_secret", "Armen", "armen.secret@example.com", "America/Los_Angeles", SearchArea.LA
            ).getOrThrow()
            val store = repo.saveStore(
                null, "Brand Market", "1100 S Brand Blvd, Glendale, CA", 34.1329, -118.2551,
                "osm:n:555", "818-555-0101",
                listOf(StoreHoursEntity("", 1, HoursMode.OPEN, "07:00-16:00")),
                comp.id, "owner_uid_secret", "Armen", EmployeeRole.OWNER, true, GeocodeSource.MANUAL_PIN
            ).getOrThrow()
            val job = repo.saveJobAtomically(
                jobId = "job_bak_1", storeId = store.id, type = JobType.DELIVERY,
                dateIso = "2026-08-01", timeIso = "09:30", reminderEnabled = true,
                items = listOf(DraftItemInput(id = "itm_1", name = "Lavash", planned = "12", unit = "cs")),
                assignedDriverUid = "drv_9", assignedDriverName = "Gevorg", notes = "Side door",
                companyId = comp.id, actorUid = "owner_uid_secret", actorName = "Armen", actorRole = EmployeeRole.OWNER
            ).getOrThrow()

            val origBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 11, 22, 33, 44)
            val editBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 55, 66, 77, 88)
            val sigBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 9, 8, 7)
            val pdfBytes = "%PDF-1.4\n1 0 obj\n<<>>\nendobj\n%%EOF\n".toByteArray(Charsets.US_ASCII)

            val origFile = File(workDir, "orig.jpg").apply { writeBytes(origBytes) }
            val editFile = File(workDir, "edit.jpg").apply { writeBytes(editBytes) }
            val sigFile = File(workDir, "sig.png").apply { writeBytes(sigBytes) }
            val pdfFile = File(workDir, "signed.pdf").apply { writeBytes(pdfBytes) }

            val createdInv = repo.createInvoice(
                companyId = comp.id, jobId = job.id, storeId = store.id, storeName = store.name,
                invoiceNumber = "INV-BAK-77", notes = "Handle cold",
                pages = listOf(
                    InvoicePageEntity(
                        id = "pg_bak_1", invoiceId = "inv_bak_1", pageIndex = 0,
                        originalLocalPath = origFile.absolutePath, editedLocalPath = editFile.absolutePath,
                        originalSha256 = InvoiceSyncValidator.sha256Bytes(origBytes),
                        editedSha256 = InvoiceSyncValidator.sha256Bytes(editBytes),
                        byteSize = origFile.length() + editFile.length()
                    )
                ),
                actorUid = "owner_uid_secret", actorName = "Armen", actorRole = EmployeeRole.OWNER
            ).getOrThrow()
            repo.signInvoice(
                invoiceId = createdInv.invoice.id, recipientName = "Sona Receiver", recipientTitle = "Manager",
                acknowledgmentConfirmed = true, signatureLocalPath = sigFile.absolutePath,
                signaturePageIndex = 0, signaturePosX = 0.5f, signaturePosY = 0.8f,
                signedPdfLocalPath = pdfFile.absolutePath, signedPdfSha256 = InvoiceSyncValidator.sha256Bytes(pdfBytes),
                signedPdfBytes = pdfFile.length(), actorUid = "owner_uid_secret", actorName = "Armen",
                actorRole = EmployeeRole.OWNER, companyId = comp.id
            ).getOrThrow()

            val archiveFile = File(workDir, "delifar-full.delifarbak")
            val sourceMgr = EncryptedArchiveBackupManager(db)
            val exportSummary = sourceMgr.exportEncryptedArchive(comp.id, "StrongPass123!", archiveFile).getOrThrow()
            assertEquals(1, exportSummary.storeCount)
            assertEquals(1, exportSummary.jobCount)
            assertEquals(1, exportSummary.invoiceCount)
            assertEquals(4, exportSummary.attachmentCount) // orig, edit, sig, pdf
            assertTrue(EncryptedArchiveBackupManager.isEncryptedArchive(archiveFile))
            // Raw encrypted archive MUST NOT leak plaintext credentials or store names
            val rawText = String(archiveFile.readBytes(), Charsets.ISO_8859_1)
            assertFalse(rawText.contains("Brand Market"))
            assertFalse(rawText.contains("armen.secret@example.com"))

            // 1. Wrong passphrase rejected
            val cleanMgr = EncryptedArchiveBackupManager(cleanDb)
            assertTrue(cleanMgr.inspectEncryptedArchive(archiveFile, "WrongPass999!").isFailure)

            // 2. Corrupted ciphertext rejected
            val corruptFile = File(workDir, "corrupt.delifarbak").apply {
                val b = archiveFile.readBytes()
                b[b.size - 1] = (b[b.size - 1].toInt() xor 0xFF).toByte()
                writeBytes(b)
            }
            assertTrue(cleanMgr.inspectEncryptedArchive(corruptFile, "StrongPass123!").isFailure)

            // 3. ZipSlip path traversal archive rejected
            val zipSlipFile = File(workDir, "zipslip.delifarbak")
            val maliciousZip = ByteArrayOutputStream().apply {
                ZipOutputStream(this).use { zos ->
                    zos.putNextEntry(ZipEntry("../../etc/passwd"))
                    zos.write("evil".toByteArray())
                    zos.closeEntry()
                }
            }.toByteArray()
            zipSlipFile.writeBytes(encryptTestArchive(maliciousZip, "StrongPass123!"))
            assertTrue(cleanMgr.inspectEncryptedArchive(zipSlipFile, "StrongPass123!").isFailure)

            // 4. Interrupted restore rolls back both Room AND staged files
            val restoreDir = File(workDir, "clean_device_files").apply { mkdirs() }
            val interrupted = cleanMgr.restoreEncryptedArchive(
                archiveFile = archiveFile,
                passphrase = "StrongPass123!",
                targetFilesDir = restoreDir,
                replaceConflicts = false,
                restorePreferences = true,
                activeCompanyId = "",
                beforeCommitHook = { throw RuntimeException("Simulated process interruption") }
            )
            assertTrue(interrupted.isFailure)
            assertTrue(cleanDb.storeDao().getAllStoresWithHours().isEmpty())
            assertTrue(cleanDb.invoiceDao().getAllInvoicesWithPages().isEmpty())

            // 5. Clean-device restore succeeds with exact binary attachments & ZERO auth secrets leaked
            val previewClean = cleanMgr.inspectEncryptedArchive(archiveFile, "StrongPass123!", activeCompanyId = "").getOrThrow()
            assertTrue(previewClean.isCleanDeviceRestore)
            assertEquals(1, previewClean.newInvoices)
            assertEquals(4, previewClean.attachmentCount)

            val restored = cleanMgr.restoreEncryptedArchive(
                archiveFile = archiveFile,
                passphrase = "StrongPass123!",
                targetFilesDir = restoreDir,
                replaceConflicts = false,
                restorePreferences = true,
                activeCompanyId = ""
            ).getOrThrow()
            assertTrue(restored.companyRestored)
            assertEquals(1, restored.storesWritten)
            assertEquals(1, restored.jobsWritten)
            assertEquals(1, restored.invoicesWritten)
            assertEquals(4, restored.attachmentsRestored)

            val restoredInv = cleanDb.invoiceDao().getInvoiceWithPages(createdInv.invoice.id)
            assertNotNull(restoredInv)
            assertEquals("INV-BAK-77", restoredInv!!.invoice.invoiceNumber)
            assertTrue(restoredInv.invoice.signed)
            assertEquals("Sona Receiver", restoredInv.invoice.recipientName)
            assertTrue(File(restoredInv.invoice.signatureLocalPath!!).readBytes().contentEquals(sigBytes))
            assertTrue(File(restoredInv.invoice.signedPdfLocalPath!!).readBytes().contentEquals(pdfBytes))
            assertTrue(File(restoredInv.sortedPages[0].originalLocalPath).readBytes().contentEquals(origBytes))
            assertTrue(File(restoredInv.sortedPages[0].editedLocalPath).readBytes().contentEquals(editBytes))
            // Auth email/UID credentials were NEVER exported or imported
            val restoredPrefs = cleanDb.preferenceDao().getAll().associate { it.key to it.value }
            assertNull(restoredPrefs[PrefKeys.ACTIVE_USER_EMAIL])
            assertNull(restoredPrefs[PrefKeys.ACTIVE_USER_UID])

            // 6. Cross-company restore blocked unless explicitly allowed
            val crossBlocked = cleanMgr.restoreEncryptedArchive(
                archiveFile = archiveFile,
                passphrase = "StrongPass123!",
                targetFilesDir = restoreDir,
                replaceConflicts = false,
                restorePreferences = false,
                activeCompanyId = "different_company_id",
                allowCrossCompanyRestore = false
            )
            assertTrue(crossBlocked.isFailure)
            assertTrue(crossBlocked.exceptionOrNull() is SecurityException)

            // 7. Existing-record conflict handling: retain when replaceConflicts=false, update when true, never delete extra local stores
            val cleanRepo = DeliFarRepository(cleanDb)
            cleanRepo.saveStore(
                store.id, "Local Edited Name", store.address, store.lat, store.lon,
                store.providerId, store.phone, emptyList(), comp.id, "u", "User", EmployeeRole.OWNER, true, GeocodeSource.MANUAL_PIN
            ).getOrThrow()
            val extraLocalStore = cleanRepo.saveStore(
                null, "Extra Local Store", "999 Extra Ave, Glendale, CA", 34.1555, -118.2500,
                null, "", emptyList(), comp.id, "u", "User", EmployeeRole.OWNER, true, GeocodeSource.MANUAL_PIN
            ).getOrThrow()

            cleanMgr.restoreEncryptedArchive(
                archiveFile, "StrongPass123!", restoreDir, replaceConflicts = false, restorePreferences = false, activeCompanyId = comp.id
            ).getOrThrow()
            assertEquals("Local Edited Name", cleanDb.storeDao().getStore(store.id)!!.name)
            assertNotNull(cleanDb.storeDao().getStore(extraLocalStore.id))

            cleanMgr.restoreEncryptedArchive(
                archiveFile, "StrongPass123!", restoreDir, replaceConflicts = true, restorePreferences = false, activeCompanyId = comp.id
            ).getOrThrow()
            assertEquals("Brand Market", cleanDb.storeDao().getStore(store.id)!!.name)
            assertNotNull(cleanDb.storeDao().getStore(extraLocalStore.id)) // Never deletes existing records!

            // 8. Legacy v1 JSON backup remains 100% backward-compatible
            val v1Json = BackupManager(db).exportJson(comp.id)
            val parsedV1 = BackupManager(cleanDb).parseAndValidate(v1Json).getOrThrow()
            assertEquals(1, parsedV1.stores.size)
            assertEquals(1, parsedV1.jobs.size)
        } finally {
            cleanDb.close()
            workDir.deleteRecursively()
        }
    }

    @Test
    fun localIsolationAndInvitationPolicy_blocksCrossCompanyDetailAccessUnauthorizedDriversAndEmailMismatch() = runTest {
        val compA = repo.registerCompany("Tenant A", "ownerA", "Alice", "a@example.com", "America/Los_Angeles", SearchArea.LA).getOrThrow()
        val compB = repo.registerCompany("Tenant B", "ownerB", "Bob", "b@example.com", "America/Los_Angeles", SearchArea.SAC).getOrThrow()

        val storeA = repo.saveStore(
            null, "Tenant A Store", "100 Main St, Glendale, CA", 34.1425, -118.2551,
            null, "", emptyList(), compA.id, "ownerA", "Alice", EmployeeRole.OWNER, true, GeocodeSource.MANUAL_PIN
        ).getOrThrow()
        val invA = repo.createInvoice(
            companyId = compA.id, jobId = null, storeId = storeA.id, storeName = storeA.name,
            invoiceNumber = "INV-A-1", notes = "",
            pages = listOf(
                InvoicePageEntity("pA", "invA", 0, "/tmp/a.jpg", "/tmp/ae.jpg", "s1", "s2", byteSize = 512)
            ),
            actorUid = "drvA1", actorName = "Driver A1", actorRole = EmployeeRole.DRIVER
        ).getOrThrow()

        // Cross-company detail queries return null
        assertNull(repo.getStoreWithHours(storeA.id, companyId = compB.id))
        assertNull(repo.getInvoiceWithPages(invA.invoice.id, companyId = compB.id))

        // Cross-company mutations rejected
        assertTrue(repo.archiveStore(storeA.id, "ownerB", "Bob", EmployeeRole.OWNER, compB.id).exceptionOrNull() is SecurityException)
        assertTrue(repo.voidInvoice(invA.invoice.id, "Tamper", "ownerB", "Bob", EmployeeRole.OWNER, compB.id).exceptionOrNull() is SecurityException)

        // Driver 2 cannot sign Driver 1's invoice even inside same company
        val drv2Sign = repo.signInvoice(
            invoiceId = invA.invoice.id, recipientName = "Customer", recipientTitle = "",
            acknowledgmentConfirmed = true, signatureLocalPath = "/tmp/s.png",
            signaturePageIndex = 0, signaturePosX = 0.5f, signaturePosY = 0.8f,
            signedPdfLocalPath = "/tmp/p.pdf", signedPdfSha256 = "abc", signedPdfBytes = 100,
            actorUid = "drvA2", actorName = "Other Driver", actorRole = EmployeeRole.DRIVER,
            companyId = compA.id
        )
        assertTrue(drv2Sign.isFailure)
        assertTrue(drv2Sign.exceptionOrNull() is SecurityException)

        // Invitation redemption strictly requires matching email and blocks reuse
        val invite = repo.createInvitation(
            compA.id, compA.name, "driver.invited@example.com", EmployeeRole.DRIVER,
            "ownerA", "Alice", EmployeeRole.OWNER
        ).getOrThrow()

        val blankEmailAttempt = repo.redeemInvitationLocally(invite.inviteCode, "u_x", "Impostor", "")
        assertTrue(blankEmailAttempt.isFailure)
        assertTrue(blankEmailAttempt.exceptionOrNull() is SecurityException)

        val mismatchEmailAttempt = repo.redeemInvitationLocally(invite.inviteCode, "u_x", "Impostor", "wrong@example.com")
        assertTrue(mismatchEmailAttempt.isFailure)
        assertTrue(mismatchEmailAttempt.exceptionOrNull() is SecurityException)

        val matchedAttempt = repo.redeemInvitationLocally(invite.inviteCode, "u_good", "Driver Invited", "DRIVER.INVITED@example.com")
        assertTrue(matchedAttempt.isSuccess)
        assertEquals(EmployeeRole.DRIVER, matchedAttempt.getOrThrow().role)

        // Reuse blocked after acceptance
        val reuseAttempt = repo.redeemInvitationLocally(invite.inviteCode, "u_good2", "Driver Invited", "driver.invited@example.com")
        assertTrue(reuseAttempt.isFailure)
    }

    @Test
    fun firebaseStartupAndOfflineStartup_initializeCleanlyWithoutCrashOrFakeSyncedState() = runTest {
        val syncMgr = CloudSyncManager(context, db, repo)
        val mappingIdRes = context.resources.getIdentifier(
            "com.google.firebase.crashlytics.mapping_file_id",
            "string",
            context.packageName
        )
        val googleAppIdRes = context.resources.getIdentifier(
            "google_app_id",
            "string",
            context.packageName
        )

        if (googleAppIdRes != 0) {
            // Firebase-enabled build (google-services.json bundled):
            // Crashlytics Gradle plugin MUST have generated mapping_file_id so FirebaseApp + Crashlytics never crash.
            assertTrue(
                "Crashlytics mapping_file_id must be generated whenever google-services.json is present",
                mappingIdRes != 0 && context.getString(mappingIdRes).isNotBlank()
            )
            FirebaseServices.initializeIfConfigured(context)
            assertTrue(FirebaseServices.isConfigured)
            assertFalse(FirebaseServices.isAuthenticated)

            // Flush when configured but not signed in must return NOT_SIGNED_IN (never SUCCEEDED)
            val outcome = syncMgr.flushPendingQueueDetailed("cmp_test")
            assertEquals(SyncFlushOutcome.NOT_SIGNED_IN, outcome)
            assertNull(syncMgr.syncState.value.lastSyncedAt)
        } else {
            // Offline build (no google-services.json bundled):
            // Startup must remain cleanly offline with zero crashes and never report synced.
            FirebaseApp.getApps(context).forEach { runCatching { it.delete() } }
            FirebaseServices.resetForTest()
            FirebaseServices.initializeIfConfigured(context)

            assertFalse(FirebaseServices.isConfigured)
            assertFalse(FirebaseServices.isAuthenticated)
            assertEquals(SyncFlushOutcome.NOT_CONFIGURED, syncMgr.flushPendingQueueDetailed("cmp_test"))
            assertNull(syncMgr.syncState.value.lastSyncedAt)
        }
    }

    @Test
    fun offlineToCloudCompanyClaimAndSwitching_transfersOwnershipSafelyWithoutDuplicatesOrTheft() = runTest {
        // 1. Create an offline company (local_* owner) with store & job
        val localCompany = repo.registerCompany(
            companyName = "Valley Fresh Offline",
            ownerUid = "local_offline_owner_1",
            ownerName = "Local Owner",
            ownerEmail = "owner@valleyfresh.com",
            timezone = "America/Los_Angeles",
            defaultArea = SearchArea.LA
        ).getOrThrow()

        val store = repo.saveStore(
            existingId = null,
            name = "Valley Deli",
            address = "100 Main St, Glendale, CA",
            lat = 34.1425,
            lon = -118.2551,
            providerId = null,
            phone = "818-555-0101",
            hours = emptyList(),
            companyId = localCompany.id,
            actorUid = "local_offline_owner_1",
            actorName = "Local Owner",
            actorRole = EmployeeRole.OWNER,
            locationConfirmed = true,
            geocodeSource = GeocodeSource.MANUAL_PIN
        ).getOrThrow()

        repo.saveJobAtomically(
            jobId = "job_local_1",
            storeId = store.id,
            type = JobType.DELIVERY,
            dateIso = "2026-07-10",
            timeIso = "09:30",
            reminderEnabled = false,
            items = listOf(DraftItemInput(name = "Milk", planned = "10", unit = "cs")),
            assignedDriverUid = "local_offline_owner_1",
            assignedDriverName = "Local Owner",
            companyId = localCompany.id,
            actorUid = "local_offline_owner_1",
            actorName = "Local Owner",
            actorRole = EmployeeRole.OWNER
        ).getOrThrow()

        assertEquals(1, repo.getOfflineOwnedCompanies().size)

        // 2. Authenticated Firebase user claims the offline company explicitly
        val claimed = repo.claimLocalCompanyForAuthenticatedUser(
            localCompanyId = localCompany.id,
            authUid = "firebase_uid_owner_A",
            authName = "Armen Cloud",
            authEmail = "armen@valleyfresh.com"
        ).getOrThrow()

        assertEquals(localCompany.id, claimed.id)
        assertEquals("firebase_uid_owner_A", claimed.ownerUid)
        assertTrue(repo.getOfflineOwnedCompanies().isEmpty())
        // Zero duplicate stores or jobs
        assertEquals(1, repo.observeStores(localCompany.id).first().size)
        val jobsAfterClaim = repo.observeJobRows(localCompany.id).first()
        assertEquals(1, jobsAfterClaim.size)
        assertEquals("firebase_uid_owner_A", jobsAfterClaim.first().job.assignedDriverUid)

        // 3. Re-claiming by the SAME owner is idempotent
        assertTrue(
            repo.claimLocalCompanyForAuthenticatedUser(
                localCompany.id, "firebase_uid_owner_A", "Armen Cloud", "armen@valleyfresh.com"
            ).isSuccess
        )

        // 4. Another Firebase user MUST BE BLOCKED from stealing the claimed company
        val theftAttempt = repo.claimLocalCompanyForAuthenticatedUser(
            localCompanyId = localCompany.id,
            authUid = "firebase_uid_other_B",
            authName = "Other User",
            authEmail = "other@example.com"
        )
        assertTrue(theftAttempt.isFailure)
        assertTrue(theftAttempt.exceptionOrNull() is SecurityException)

        // 5. Company switching enforces active membership
        val secondCompany = repo.registerCompany(
            companyName = "Coast Distributors",
            ownerUid = "firebase_uid_owner_A",
            ownerName = "Armen Cloud",
            ownerEmail = "armen@valleyfresh.com",
            timezone = "America/Los_Angeles",
            defaultArea = SearchArea.SAC
        ).getOrThrow()
        assertEquals(2, repo.getCompaniesForUser("firebase_uid_owner_A").size)

        assertTrue(repo.switchActiveCompany(localCompany.id, "firebase_uid_owner_A").isSuccess)
        assertEquals(localCompany.id, repo.getPreferences().activeCompanyId)
        assertTrue(repo.switchActiveCompany(secondCompany.id, "firebase_uid_owner_A").isSuccess)
        assertEquals(secondCompany.id, repo.getPreferences().activeCompanyId)

        // Unauthorized user cannot switch into company A
        val unauthSwitch = repo.switchActiveCompany(localCompany.id, "firebase_uid_other_B")
        assertTrue(unauthSwitch.isFailure)
        assertTrue(unauthSwitch.exceptionOrNull() is SecurityException)
    }

    @Test
    fun invoiceRetryUpload_reportsExactMissingAttachmentAndRecoversCleanlyPreservingSignature() = runTest {
        val tempDir = File(context.cacheDir, "inv_retry_test_${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val comp = repo.registerCompany(
                "Retry Co", "u_owner", "Owner", "o@retry.com", "America/Los_Angeles", SearchArea.LA
            ).getOrThrow()
            val store = repo.saveStore(
                null, "Retry Market", "200 Main St, Glendale, CA", 34.1425, -118.2551,
                null, "", emptyList(), comp.id, "u_owner", "Owner", EmployeeRole.OWNER, true, GeocodeSource.MANUAL_PIN
            ).getOrThrow()

            val jpgBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 7, 8, 9)
            val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 4, 5)
            val pdfBytes = "%PDF-1.4\n%%EOF\n".toByteArray(Charsets.US_ASCII)

            val pageFile = File(tempDir, "page1.jpg").apply { writeBytes(jpgBytes) }
            val sigFile = File(tempDir, "sig.png").apply { writeBytes(pngBytes) }
            val pdfFile = File(tempDir, "signed.pdf").apply { writeBytes(pdfBytes) }

            val created = repo.createInvoice(
                companyId = comp.id,
                jobId = null,
                storeId = store.id,
                storeName = store.name,
                invoiceNumber = "INV-RETRY-7",
                notes = "",
                pages = listOf(
                    InvoicePageEntity(
                        id = "pg_retry_1",
                        invoiceId = "inv_retry_1",
                        pageIndex = 0,
                        originalLocalPath = pageFile.absolutePath,
                        editedLocalPath = pageFile.absolutePath,
                        originalSha256 = InvoiceSyncValidator.sha256Bytes(jpgBytes),
                        editedSha256 = InvoiceSyncValidator.sha256Bytes(jpgBytes),
                        byteSize = pageFile.length()
                    )
                ),
                actorUid = "u_owner",
                actorName = "Owner",
                actorRole = EmployeeRole.OWNER
            ).getOrThrow()

            val signed = repo.signInvoice(
                invoiceId = created.invoice.id,
                recipientName = "Maria Lopez",
                recipientTitle = "Receiver",
                acknowledgmentConfirmed = true,
                signatureLocalPath = sigFile.absolutePath,
                signaturePageIndex = 0,
                signaturePosX = 0.5f,
                signaturePosY = 0.8f,
                signedPdfLocalPath = pdfFile.absolutePath,
                signedPdfSha256 = InvoiceSyncValidator.sha256Bytes(pdfBytes),
                signedPdfBytes = pdfFile.length(),
                actorUid = "u_owner",
                actorName = "Owner",
                actorRole = EmployeeRole.OWNER,
                companyId = comp.id
            ).getOrThrow()

            // Simulate missing signed PDF on device -> retryInvoiceUpload must fail clearly and explain why
            pdfFile.delete()
            val failRetry = repo.retryInvoiceUpload(signed.id, comp.id)
            assertTrue(failRetry.isFailure)
            val errMsg = failRetry.exceptionOrNull()!!.message.orEmpty()
            assertTrue("Expected missing signed PDF diagnostic, got: $errMsg", errMsg.contains("Signed invoice PDF", ignoreCase = true))
            val failedInv = repo.getInvoiceWithPages(signed.id, comp.id)!!.invoice
            assertEquals(InvoiceStatus.UPLOAD_FAILED, failedInv.status)
            assertTrue(failedInv.signed) // Signature metadata preserved!
            assertEquals("Maria Lopez", failedInv.recipientName)

            // Restore signed PDF -> retryInvoiceUpload succeeds and queues upload without losing signature/version
            pdfFile.writeBytes(pdfBytes)
            val recoverRetry = repo.retryInvoiceUpload(signed.id, comp.id)
            assertTrue(recoverRetry.isSuccess)
            val recoveredInv = repo.getInvoiceWithPages(signed.id, comp.id)!!.invoice
            assertEquals(InvoiceStatus.PENDING_UPLOAD, recoveredInv.status)
            assertEquals(SyncState.PENDING, recoveredInv.syncState)
            assertTrue(recoveredInv.signed)
            assertEquals(1, recoveredInv.version)
            assertNull(recoveredInv.uploadError)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun driverDailyWorkflow_supportsQuickStartCompleteAndIssueReporting() = runTest {
        val comp = repo.registerCompany(
            "FastRoute Co", "owner_d", "Owner", "o@fast.com", "America/Los_Angeles", SearchArea.LA
        ).getOrThrow()
        val store = repo.saveStore(
            null, "Fast Deli", "500 S Brand Blvd, Glendale, CA", 34.1395, -118.2551,
            null, "818-555-0199",
            listOf(StoreHoursEntity("", 1, HoursMode.OPEN, "07:00-14:00")),
            comp.id, "owner_d", "Owner", EmployeeRole.OWNER, true, GeocodeSource.MANUAL_PIN
        ).getOrThrow()
        val job = repo.saveJobAtomically(
            jobId = "drv_job_1",
            storeId = store.id,
            type = JobType.DELIVERY,
            dateIso = "2026-07-15",
            timeIso = "08:30",
            reminderEnabled = true,
            items = listOf(DraftItemInput(id = "item_1", name = "Lavash", planned = "20", unit = "cs")),
            assignedDriverUid = "drv_fast",
            assignedDriverName = "Hayk",
            companyId = comp.id,
            actorUid = "owner_d",
            actorName = "Owner",
            actorRole = EmployeeRole.OWNER
        ).getOrThrow()

        // Driver starts -> reports partial delivery -> completes
        val started = repo.updateJobStatus(job.id, JobStatus.PROGRESS, "drv_fast", "Hayk", EmployeeRole.DRIVER, comp.id).getOrThrow()
        assertEquals(JobStatus.PROGRESS, started.status)

        val reported = repo.reportIssue(
            jobId = job.id,
            issueType = JobIssue.PARTIAL,
            note = "4 cases short",
            keepOpen = false,
            actualQuantitiesByItemId = mapOf("item_1" to "16"),
            actorUid = "drv_fast",
            actorName = "Hayk",
            actorRole = EmployeeRole.DRIVER,
            companyId = comp.id
        ).getOrThrow()
        assertEquals(JobIssue.PARTIAL, reported.issue)
        assertEquals(JobStatus.COMPLETED, reported.status)
        assertEquals("16", db.jobDao().getJobWithItems(job.id)!!.items.first().actual)
    }

    @Test
    fun concurrentSyncWorkers_neverProcessSameQueueItemTwiceOrTriggerDuplicateAuditPermissionDenied() = runTest {
        val company = repo.registerCompany(
            companyName = "FARSTER LLC",
            ownerUid = "uid_farster_owner",
            ownerName = "Owner",
            ownerEmail = "owner@farster.com",
            timezone = "America/Los_Angeles",
            defaultArea = SearchArea.LA
        ).getOrThrow()

        // Confirm exact 3 queued items created by company registration: company, employee, audit
        val initialDue = db.syncQueueDao().getDueItemsForCompany(company.id, System.currentTimeMillis() + 1000L)
        assertEquals(setOf("company", "employee", "audit"), initialDue.map { it.entityType }.toSet())

        val uploadCountsByKey = ConcurrentHashMap<String, AtomicInteger>()
        val remoteAudits = ConcurrentHashMap<String, RemoteAuditDocument>()
        val activeUploads = AtomicInteger(0)
        val maxConcurrentUploads = AtomicInteger(0)

        val appendOnlyAuditGateway = object : AuditRemoteGateway {
            override suspend fun createAudit(companyId: String, logId: String, payload: Map<String, Any?>) {
                if (remoteAudits.containsKey(logId)) {
                    throw IllegalStateException("PERMISSION_DENIED: Audit log updates are forbidden.")
                }
                remoteAudits[logId] = RemoteAuditDocument(
                    documentId = logId,
                    id = payload["id"] as String,
                    companyId = payload["companyId"] as String,
                    actorUid = payload["actorUid"] as String,
                    actorName = payload["actorName"] as String,
                    actorRole = payload["actorRole"] as String,
                    action = payload["action"] as String,
                    targetType = payload["targetType"] as String,
                    targetId = payload["targetId"] as String,
                    summary = payload["summary"] as String,
                    createdAt = payload["createdAt"] as Long,
                    serverTimestampMillis = 1_750_001_000_000L
                )
            }

            override suspend fun fetchAuditFromServer(companyId: String, logId: String): RemoteAuditDocument? =
                remoteAudits[logId]
        }

        // Two separate CloudSyncManager instances simulate DeliFarViewModel + SyncWorker running concurrently
        val foregroundSync = CloudSyncManager(context, db, repo)
        val backgroundWorkerSync = CloudSyncManager(context, db, repo)

        suspend fun simulatedUpload(item: com.delifar.app.data.SyncQueueEntity, wasRetry: Boolean) {
            val concurrent = activeUploads.incrementAndGet()
            maxConcurrentUploads.accumulateAndGet(concurrent, ::maxOf)
            try {
                // Deliberately suspend to widen race window if mutex were missing
                delay(40)
                val key = "${item.entityType}:${item.entityId}"
                uploadCountsByKey.computeIfAbsent(key) { AtomicInteger(0) }.incrementAndGet()
                if (item.entityType == "audit") {
                    val entry = db.auditLogDao().getById(item.entityId)!!
                    val res = IdempotentAuditUploader.uploadIdempotently(
                        entry = entry,
                        actorUidToSend = "uid_farster_owner",
                        preferVerifyFirst = wasRetry,
                        gateway = appendOnlyAuditGateway,
                        serverTimestampValue = "SERVER_TS"
                    )
                    db.auditLogDao().markSynced(entry.id, SyncState.SYNCED, res.serverTimestampMillis)
                }
            } finally {
                activeUploads.decrementAndGet()
            }
        }

        val outcomes = listOf(
            async { foregroundSync.executeLockedQueueFlush(company.id, retryFailedFirst = true, uploadItem = ::simulatedUpload) },
            async { backgroundWorkerSync.executeLockedQueueFlush(company.id, retryFailedFirst = false, uploadItem = ::simulatedUpload) }
        ).awaitAll()

        assertEquals(listOf(SyncFlushOutcome.SUCCEEDED, SyncFlushOutcome.SUCCEEDED), outcomes)
        assertEquals("Workers must never overlap on queue processing", 1, maxConcurrentUploads.get())
        assertEquals(3, uploadCountsByKey.size)
        uploadCountsByKey.forEach { (key, count) ->
            assertEquals("Queue item $key must be uploaded exactly once", 1, count.get())
        }
        assertEquals(0, db.syncQueueDao().getPendingCountNow(company.id))
        assertEquals(0, db.syncQueueDao().getFailedCountNow(company.id))

        // Verify local company and COMPANY_REGISTERED audit record are preserved & marked synced
        assertEquals("FARSTER LLC", repo.getCompany(company.id)?.name)
        val logs = repo.observeAuditLogs(company.id).first()
        assertEquals(1, logs.size)
        assertEquals("COMPANY_REGISTERED", logs.first().action)
        assertEquals(SyncState.SYNCED, logs.first().syncState)
    }

    @Test
    fun duplicateAuditUploadAfterPartialSuccess_recoversIdempotentlyAndDiagnosticsExposeExactFailedItem() = runTest {
        val company = repo.registerCompany(
            companyName = "FARSTER LLC",
            ownerUid = "uid_farster_owner",
            ownerName = "Owner",
            ownerEmail = "owner@farster.com",
            timezone = "America/Los_Angeles",
            defaultArea = SearchArea.LA
        ).getOrThrow()

        // Simulate exact current phone state:
        // company + employee already synced & removed from queue;
        // COMPANY_REGISTERED audit doc ALREADY exists in Firestore, while local sync_queue still has 1 failed audit item with PERMISSION_DENIED.
        val queueItems = db.syncQueueDao().getDueItemsForCompany(company.id, System.currentTimeMillis() + 1000L)
        val auditQueueItem = queueItems.first { it.entityType == "audit" }
        queueItems.filter { it.entityType != "audit" }.forEach { db.syncQueueDao().delete(it.id) }

        val auditEntry = db.auditLogDao().getById(auditQueueItem.entityId)!!
        assertEquals("COMPANY_REGISTERED", auditEntry.action)

        db.syncQueueDao().upsert(
            auditQueueItem.copy(
                retryCount = 2,
                status = SyncState.FAILED,
                lastError = "PERMISSION_DENIED: Missing or insufficient permissions.",
                nextAttemptAt = System.currentTimeMillis() + 60_000L
            )
        )

        // 1. Diagnostics MUST expose the exact failed entityType, entityId, operation, retryCount, and Firebase error
        val failedDiagnostics = repo.observeFailedSyncItems(company.id).first()
        assertEquals(1, failedDiagnostics.size)
        assertEquals("audit", failedDiagnostics.first().entityType)
        assertEquals(auditEntry.id, failedDiagnostics.first().entityId)
        assertEquals("upsert", failedDiagnostics.first().operation)
        assertEquals(2, failedDiagnostics.first().retryCount)
        assertTrue(failedDiagnostics.first().lastError!!.contains("PERMISSION_DENIED"))

        // 2. Remote Firestore already holds the exact immutable COMPANY_REGISTERED document (append-only, updates forbidden)
        val remoteServerTimestamp = 1_750_002_500_000L
        val remoteAudits = mutableMapOf(
            auditEntry.id to RemoteAuditDocument(
                documentId = auditEntry.id,
                id = auditEntry.id,
                companyId = auditEntry.companyId,
                actorUid = "uid_farster_owner",
                actorName = auditEntry.actorName,
                actorRole = auditEntry.actorRole,
                action = auditEntry.action,
                targetType = auditEntry.targetType,
                targetId = auditEntry.targetId,
                summary = auditEntry.summary,
                createdAt = auditEntry.createdAt,
                serverTimestampMillis = remoteServerTimestamp
            )
        )
        var createAttemptCount = 0
        val strictAppendOnlyGateway = object : AuditRemoteGateway {
            override suspend fun createAudit(companyId: String, logId: String, payload: Map<String, Any?>) {
                createAttemptCount++
                throw IllegalStateException("PERMISSION_DENIED: Missing or insufficient permissions.")
            }

            override suspend fun fetchAuditFromServer(companyId: String, logId: String): RemoteAuditDocument? =
                remoteAudits[logId]
        }

        val syncMgr = CloudSyncManager(context, db, repo)
        val outcome = syncMgr.executeLockedQueueFlush(
            targetCompanyId = company.id,
            retryFailedFirst = true,
            uploadItem = { item, wasRetry ->
                assertEquals("audit", item.entityType)
                assertTrue("Retried failed audit must prefer server verification first", wasRetry)
                val entry = db.auditLogDao().getById(item.entityId)!!
                val res = IdempotentAuditUploader.uploadIdempotently(
                    entry = entry,
                    actorUidToSend = "uid_farster_owner",
                    preferVerifyFirst = wasRetry,
                    gateway = strictAppendOnlyGateway,
                    serverTimestampValue = "SERVER_TS"
                )
                db.auditLogDao().markSynced(entry.id, SyncState.SYNCED, res.serverTimestampMillis)
            }
        )

        assertEquals(SyncFlushOutcome.SUCCEEDED, outcome)
        assertEquals("Should verify existing remote audit without issuing a doomed update/create", 0, createAttemptCount)
        assertEquals(0, db.syncQueueDao().getPendingCountNow(company.id))
        assertEquals(0, db.syncQueueDao().getFailedCountNow(company.id))
        assertTrue(repo.observeFailedSyncItems(company.id).first().isEmpty())

        // Local audit log and FARSTER LLC company remain intact (never deleted or recreated)
        val syncedAudit = db.auditLogDao().getById(auditEntry.id)!!
        assertEquals(SyncState.SYNCED, syncedAudit.syncState)
        assertEquals(remoteServerTimestamp, syncedAudit.serverTimestamp)
        assertEquals(company.id, repo.getCompany(company.id)!!.id)
        assertEquals("FARSTER LLC", repo.getCompany(company.id)!!.name)

        // 3. Genuine PERMISSION_DENIED on a non-existent remote audit MUST stay FAILED and populate diagnostics
        val freshAuditId = UUID.randomUUID().toString()
        db.auditLogDao().insert(
            auditEntry.copy(
                id = freshAuditId,
                action = "TEST_DENIED_AUDIT",
                syncState = SyncState.PENDING,
                serverTimestamp = null
            )
        )
        repo.enqueueSyncInternal(company.id, "audit", freshAuditId, "upsert")
        val deniedOutcome = syncMgr.executeLockedQueueFlush(
            targetCompanyId = company.id,
            retryFailedFirst = false,
            uploadItem = { item, wasRetry ->
                val entry = db.auditLogDao().getById(item.entityId)!!
                IdempotentAuditUploader.uploadIdempotently(
                    entry = entry,
                    actorUidToSend = "uid_farster_owner",
                    preferVerifyFirst = wasRetry,
                    gateway = strictAppendOnlyGateway,
                    serverTimestampValue = "SERVER_TS"
                )
            }
        )
        assertEquals(SyncFlushOutcome.ACCESS_DENIED_OR_UNCONFIGURED, deniedOutcome)
        val afterDenied = repo.observeFailedSyncItems(company.id).first()
        assertEquals(1, afterDenied.size)
        assertEquals("audit", afterDenied.first().entityType)
        assertEquals(freshAuditId, afterDenied.first().entityId)
        assertEquals("upsert", afterDenied.first().operation)
        assertTrue(afterDenied.first().lastError!!.contains("PERMISSION_DENIED"))
        assertTrue(syncMgr.syncState.value.lastError!!.contains(freshAuditId))
    }

    private fun encryptTestArchive(zipBytes: ByteArray, passphrase: String): ByteArray {
        val salt = ByteArray(16) { it.toByte() }
        val iv = ByteArray(12) { (it + 1).toByte() }
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, EncryptedArchiveBackupManager.PBKDF2_ITERATIONS, 256)
        val key = SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(EncryptedArchiveBackupManager.MAGIC_BYTES)
        val ct = cipher.doFinal(zipBytes)
        return EncryptedArchiveBackupManager.MAGIC_BYTES + salt + iv + ct
    }
}
