package com.delifar.app

import com.delifar.app.cloud.AuditRemoteGateway
import com.delifar.app.cloud.AuditUploadResolution
import com.delifar.app.cloud.BackendHealth
import com.delifar.app.cloud.CloudSyncStatus
import com.delifar.app.cloud.CloudSyncUiState
import com.delifar.app.cloud.FirebaseServices
import com.delifar.app.cloud.IdempotentAuditUploader
import com.delifar.app.cloud.InvoiceMetadataGateway
import com.delifar.app.cloud.InvoiceMetadataUploadResolution
import com.delifar.app.cloud.InvoiceSyncValidator
import com.delifar.app.cloud.InvoiceUploadErrorCode
import com.delifar.app.cloud.InvoiceUploadException
import com.delifar.app.cloud.RemoteAuditDocument
import com.delifar.app.cloud.RemoteInvoiceSnapshot
import com.delifar.app.cloud.SyncStatusResolver
import com.delifar.app.data.AuditLogEntity
import com.delifar.app.data.EmployeeRole
import com.delifar.app.data.HoursMode
import com.delifar.app.data.InvoiceEntity
import com.delifar.app.data.InvoicePageEntity
import com.delifar.app.data.InvoiceStatus
import com.delifar.app.data.InvoiceWithPages
import com.delifar.app.data.JobStatus
import com.delifar.app.data.StoreHoursEntity
import com.delifar.app.data.SyncState
import com.delifar.app.domain.BusinessRules
import com.delifar.app.domain.RolePermissions
import com.delifar.app.network.GeoPoint
import com.delifar.app.network.GeoValidation
import com.delifar.app.network.GeocodeSource
import com.delifar.app.network.OsmRoutesClient
import com.delifar.app.network.ParsedShareInput
import com.delifar.app.network.ShareLocationParser
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class DeliFarUnitTests {

    @Test
    fun rolePermissionsMatrix_enforcesAllFourRoles() {
        // Owner: full access
        assertTrue(RolePermissions.canManageCompany(EmployeeRole.OWNER))
        assertTrue(RolePermissions.canManageEmployees(EmployeeRole.OWNER))
        assertTrue(RolePermissions.canManageStoresAndJobs(EmployeeRole.OWNER))
        assertTrue(RolePermissions.canAssignDrivers(EmployeeRole.OWNER))
        assertTrue(RolePermissions.canUploadAndSignInvoices(EmployeeRole.OWNER))
        assertTrue(RolePermissions.canExportInvoices(EmployeeRole.OWNER))
        assertTrue(RolePermissions.canVoidOrCorrectInvoices(EmployeeRole.OWNER))

        // Manager: operational + invoices, no company/employee ownership
        assertFalse(RolePermissions.canManageCompany(EmployeeRole.MANAGER))
        assertFalse(RolePermissions.canManageEmployees(EmployeeRole.MANAGER))
        assertTrue(RolePermissions.canManageStoresAndJobs(EmployeeRole.MANAGER))
        assertTrue(RolePermissions.canAssignDrivers(EmployeeRole.MANAGER))
        assertTrue(RolePermissions.canUploadAndSignInvoices(EmployeeRole.MANAGER))
        assertTrue(RolePermissions.canVoidOrCorrectInvoices(EmployeeRole.MANAGER))

        // Driver: assigned jobs + upload/sign invoices only
        assertFalse(RolePermissions.canManageCompany(EmployeeRole.DRIVER))
        assertFalse(RolePermissions.canManageStoresAndJobs(EmployeeRole.DRIVER))
        assertFalse(RolePermissions.canAssignDrivers(EmployeeRole.DRIVER))
        assertTrue(RolePermissions.canUpdateAssignedJobs(EmployeeRole.DRIVER))
        assertTrue(RolePermissions.canUploadAndSignInvoices(EmployeeRole.DRIVER))
        assertFalse(RolePermissions.canExportInvoices(EmployeeRole.DRIVER))
        assertFalse(RolePermissions.canVoidOrCorrectInvoices(EmployeeRole.DRIVER))

        // Accountant: view & export invoices only
        assertFalse(RolePermissions.canManageStoresAndJobs(EmployeeRole.ACCOUNTANT))
        assertFalse(RolePermissions.canUpdateAssignedJobs(EmployeeRole.ACCOUNTANT))
        assertFalse(RolePermissions.canUploadAndSignInvoices(EmployeeRole.ACCOUNTANT))
        assertTrue(RolePermissions.canViewAllInvoices(EmployeeRole.ACCOUNTANT))
        assertTrue(RolePermissions.canExportInvoices(EmployeeRole.ACCOUNTANT))
        assertFalse(RolePermissions.canVoidOrCorrectInvoices(EmployeeRole.ACCOUNTANT))
    }

    @Test
    fun timezoneResolution_supportsCompanyTimezonesAndFallsBackSafely() {
        val fixedClock = Clock.fixed(Instant.parse("2026-06-15T05:30:00Z"), ZoneId.of("UTC"))
        val laDate = BusinessRules.todayInZone(BusinessRules.resolveZone("America/Los_Angeles"), fixedClock)
        val yerevanDate = BusinessRules.todayInZone(BusinessRules.resolveZone("Asia/Yerevan"), fixedClock)

        // 05:30 UTC is 22:30 previous day in LA (PDT UTC-7), and 09:30 same day in Yerevan (UTC+4)
        assertEquals(LocalDate.of(2026, 6, 14), laDate)
        assertEquals(LocalDate.of(2026, 6, 15), yerevanDate)
        assertEquals(BusinessRules.LA_ZONE, BusinessRules.resolveZone("Invalid/Zone"))
    }

    @Test
    fun quantityNormalization_handlesBigDecimalPrecision() {
        assertEquals("12.5", BusinessRules.normalizeQuantity("12.500", allowZero = false))
        assertEquals("10", BusinessRules.normalizeQuantity("010.00", allowZero = false))
        assertNull(BusinessRules.normalizeQuantity("0", allowZero = false))
        assertEquals("0", BusinessRules.normalizeQuantity("0.00", allowZero = true))
        assertNull(BusinessRules.normalizeQuantity("-5", allowZero = true))
        assertNull(BusinessRules.normalizeQuantity("abc", allowZero = true))
    }

    @Test
    fun receivingHoursCheck_detectsOpenClosedOutsideAndUnknown() {
        val hours = listOf(
            StoreHoursEntity("s1", 1, HoursMode.OPEN, "08:00-12:00;13:00-17:00"),
            StoreHoursEntity("s1", 7, HoursMode.CLOSED, "")
        )
        assertEquals(
            BusinessRules.HoursCheckResult.OK,
            BusinessRules.checkReceivingHours(hours, "2026-06-15", "09:30")
        )
        assertEquals(
            BusinessRules.HoursCheckResult.OUTSIDE_WINDOWS,
            BusinessRules.checkReceivingHours(hours, "2026-06-15", "12:30")
        )
        assertEquals(
            BusinessRules.HoursCheckResult.CLOSED,
            BusinessRules.checkReceivingHours(hours, "2026-06-14", "10:00")
        )
        assertEquals(
            BusinessRules.HoursCheckResult.UNKNOWN,
            BusinessRules.checkReceivingHours(hours, "2026-06-16", "10:00")
        )
    }

    @Test
    fun overdueCheck_onlyMarksActivePastJobs() {
        val today = LocalDate.of(2026, 6, 15)
        assertTrue(BusinessRules.isOverdue("2026-06-14", JobStatus.PENDING, today))
        assertTrue(BusinessRules.isOverdue("2026-06-14", JobStatus.PROGRESS, today))
        assertFalse(BusinessRules.isOverdue("2026-06-14", JobStatus.COMPLETED, today))
        assertFalse(BusinessRules.isOverdue("2026-06-14", JobStatus.CANCELED, today))
        assertFalse(BusinessRules.isOverdue("2026-06-15", JobStatus.PENDING, today))
    }

    @Test
    fun sha256AndInviteCode_generateValidFormats() {
        val hash = BusinessRules.sha256Hex("DeliFar".toByteArray(Charsets.UTF_8))
        assertEquals(64, hash.length)
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })

        val code = BusinessRules.generateInviteCode()
        assertEquals(8, code.length)
        assertTrue(code.all { it.isLetterOrDigit() })
    }

    @Test
    fun geoValidation_validatesCoordinatesAndEndpointSecurity() {
        // Real Glendale, CA coordinates
        assertTrue(GeoValidation.isValidCoordinate(34.1425, -118.2551))
        // Reject Null Island, NaN, Infinite, out-of-range
        assertFalse(GeoValidation.isValidCoordinate(0.0, 0.0))
        assertFalse(GeoValidation.isValidCoordinate(Double.NaN, -118.2551))
        assertFalse(GeoValidation.isValidCoordinate(91.0, -118.2551))
        assertFalse(GeoValidation.isValidCoordinate(34.1425, 181.0))

        // Public HTTPS endpoints allowed; HTTP, localhost, private IPs, embedded credentials rejected
        assertTrue(GeoValidation.isSafeHttpsEndpointUrl("https://tiles.openfreemap.org/styles/liberty"))
        assertTrue(GeoValidation.isSafeHttpsEndpointUrl("https://nominatim.openstreetmap.org"))
        assertFalse(GeoValidation.isSafeHttpsEndpointUrl("http://nominatim.openstreetmap.org"))
        assertFalse(GeoValidation.isSafeHttpsEndpointUrl("https://localhost:8080/search"))
        assertFalse(GeoValidation.isSafeHttpsEndpointUrl("https://127.0.0.1/search"))
        assertFalse(GeoValidation.isSafeHttpsEndpointUrl("https://192.168.1.50/search"))
        assertFalse(GeoValidation.isSafeHttpsEndpointUrl("https://10.0.0.2/search"))
        assertFalse(GeoValidation.isSafeHttpsEndpointUrl("https://169.254.169.254/latest/meta-data"))
        assertFalse(GeoValidation.isSafeHttpsEndpointUrl("https://user:pass@nominatim.openstreetmap.org"))
    }

    @Test
    fun shareLocationParser_parsesCoordsAndGoogleMapsUrlsSafely() {
        // 1. Raw coordinates paste
        val raw = ShareLocationParser.parseLocal("34.1425, -118.2551")
        assertTrue(raw is ParsedShareInput.ExactLocation)
        val rawLoc = raw as ParsedShareInput.ExactLocation
        assertEquals(34.1425, rawLoc.lat, 0.0001)
        assertEquals(-118.2551, rawLoc.lon, 0.0001)
        assertEquals(GeocodeSource.PASTED_COORDS, rawLoc.source)

        // 2. Full Google Maps place URL prefers exact !3d!4d pin over viewport @lat,lon
        val fullUrl = "https://www.google.com/maps/place/1100+S+Brand+Blvd,+Glendale,+CA+91204/@34.0522,-118.2437,15z/data=!3m1!4b1!4m6!3m5!1s0x0:0x0!8m2!3d34.1329!4d-118.2551"
        val parsedPin = ShareLocationParser.parseLocal(fullUrl)
        assertTrue(parsedPin is ParsedShareInput.ExactLocation)
        val pinLoc = parsedPin as ParsedShareInput.ExactLocation
        assertEquals(34.1329, pinLoc.lat, 0.0001)
        assertEquals(-118.2551, pinLoc.lon, 0.0001)

        // 3. Google Maps search URL without coords extracts address query
        val queryUrl = "https://www.google.com/maps/search/?api=1&query=200+N+Brand+Blvd%2C+Glendale%2C+CA"
        val parsedQuery = ShareLocationParser.parseLocal(queryUrl)
        assertTrue(parsedQuery is ParsedShareInput.AddressQuery)
        assertEquals("200 N Brand Blvd, Glendale, CA", (parsedQuery as ParsedShareInput.AddressQuery).query)

        // 4. Short link allowlist accepts https://maps.app.goo.gl only; rejects arbitrary shorteners & HTTP
        assertNotNull(ShareLocationParser.isAllowedShortGoogleMapsLink("https://maps.app.goo.gl/abc123XYZ"))
        assertNull(ShareLocationParser.isAllowedShortGoogleMapsLink("http://maps.app.goo.gl/abc123XYZ"))
        assertNull(ShareLocationParser.isAllowedShortGoogleMapsLink("https://bit.ly/3xyz"))
        assertNull(ShareLocationParser.parseLocal("https://evil.example.com/maps/@34.14,-118.25"))
    }

    @Test
    fun osmRoutesClient_decodesPolylineAndOrdersOfflineStops() = runTest {
        val client = OsmRoutesClient()
        // Canonical Polyline5 test vector
        val encoded = "_p~iF~ps|U_ulLnnqC_mqNvxq`@"
        val pts = client.decodePolyline(encoded)
        assertEquals(3, pts.size)
        assertEquals(38.5, pts[0].lat, 0.001)
        assertEquals(-120.2, pts[0].lon, 0.001)
        assertEquals(40.7, pts[1].lat, 0.001)
        assertEquals(-120.95, pts[1].lon, 0.001)
        assertEquals(43.252, pts[2].lat, 0.001)
        assertEquals(-126.453, pts[2].lon, 0.001)

        // Offline nearest-neighbor ordering when no external OSRM URL is configured
        val stops = listOf(
            GeoPoint(34.1425, -118.2551), // Start: Glendale
            GeoPoint(34.1808, -118.3090), // Farther: Burbank
            GeoPoint(34.1478, -118.2480), // Closer to start: Glendale East
            GeoPoint(34.1684, -118.6059)  // End: Woodland Hills
        )
        val res = client.computeRoute(stops, routingBaseUrl = "", optimizeIntermediates = true)
        assertTrue(res.isSuccess)
        val route = res.getOrThrow()
        assertFalse(route.isStreetRoute)
        assertEquals(listOf(0, 2, 1, 3), route.optimizedStopIndices)
        assertTrue(route.distanceMeters > 0)

        // Rejects fewer than 2 valid stops (including Null Island filtering)
        val invalid = client.computeRoute(
            listOf(GeoPoint(34.1425, -118.2551), GeoPoint(0.0, 0.0)),
            routingBaseUrl = ""
        )
        assertTrue(invalid.isFailure)
    }

    @Test
    fun syncStatusResolver_accuratelyDistinguishesAllNineStatesAndNeverFakesSynced() {
        // 1. Offline — saving locally (Firebase config not bundled)
        assertEquals(
            CloudSyncStatus.OFFLINE_SAVING_LOCALLY,
            SyncStatusResolver.resolve(
                state = CloudSyncUiState(firebaseConfigured = false, isAuthenticated = false, isOnline = true),
                pendingCount = 0,
                failedCount = 0,
                activeCompanyId = "cmp_1"
            )
        )

        // 2. Firebase configured but not signed in (empty queue MUST NOT show FULLY_SYNCHRONIZED!)
        assertEquals(
            CloudSyncStatus.CONFIGURED_NOT_SIGNED_IN,
            SyncStatusResolver.resolve(
                state = CloudSyncUiState(
                    firebaseConfigured = true,
                    isAuthenticated = false,
                    isOnline = true,
                    backendHealth = BackendHealth.NOT_SIGNED_IN
                ),
                pendingCount = 0,
                failedCount = 0,
                activeCompanyId = ""
            )
        )

        // 3. Authentication required (company or pending items exist while not signed in)
        assertEquals(
            CloudSyncStatus.AUTHENTICATION_REQUIRED,
            SyncStatusResolver.resolve(
                state = CloudSyncUiState(
                    firebaseConfigured = true,
                    isAuthenticated = false,
                    isOnline = true,
                    backendHealth = BackendHealth.NOT_SIGNED_IN
                ),
                pendingCount = 2,
                failedCount = 0,
                activeCompanyId = "cmp_1"
            )
        )

        // 4. Connecting
        assertEquals(
            CloudSyncStatus.CONNECTING,
            SyncStatusResolver.resolve(
                state = CloudSyncUiState(firebaseConfigured = true, isAuthenticated = true, isConnecting = true),
                pendingCount = 0,
                failedCount = 0,
                activeCompanyId = "cmp_1"
            )
        )

        // 5. Synchronizing
        assertEquals(
            CloudSyncStatus.SYNCHRONIZING,
            SyncStatusResolver.resolve(
                state = CloudSyncUiState(firebaseConfigured = true, isAuthenticated = true, isSyncing = true),
                pendingCount = 1,
                failedCount = 0,
                activeCompanyId = "cmp_1"
            )
        )

        // 6. Access denied or backend not configured
        assertEquals(
            CloudSyncStatus.ACCESS_DENIED_OR_UNCONFIGURED,
            SyncStatusResolver.resolve(
                state = CloudSyncUiState(
                    firebaseConfigured = true,
                    isAuthenticated = true,
                    isOnline = true,
                    backendHealth = BackendHealth.ACCESS_DENIED
                ),
                pendingCount = 0,
                failedCount = 0,
                activeCompanyId = "cmp_1"
            )
        )

        // 7. Synchronization failed (failed count > 0 OR persisted error even if pending == 0)
        assertEquals(
            CloudSyncStatus.SYNCHRONIZATION_FAILED,
            SyncStatusResolver.resolve(
                state = CloudSyncUiState(
                    firebaseConfigured = true,
                    isAuthenticated = true,
                    isOnline = true,
                    backendHealth = BackendHealth.REACHABLE,
                    lastError = "Upload failed"
                ),
                pendingCount = 0,
                failedCount = 0,
                activeCompanyId = "cmp_1"
            )
        )

        // 8. Upload pending
        assertEquals(
            CloudSyncStatus.UPLOAD_PENDING,
            SyncStatusResolver.resolve(
                state = CloudSyncUiState(
                    firebaseConfigured = true,
                    isAuthenticated = true,
                    isOnline = true,
                    backendHealth = BackendHealth.REACHABLE
                ),
                pendingCount = 3,
                failedCount = 0,
                activeCompanyId = "cmp_1"
            )
        )

        // 9. Empty queue WITHOUT verified lastSyncedAt MUST NOT claim FULLY_SYNCHRONIZED
        assertEquals(
            CloudSyncStatus.CONNECTING,
            SyncStatusResolver.resolve(
                state = CloudSyncUiState(
                    firebaseConfigured = true,
                    isAuthenticated = true,
                    isOnline = true,
                    backendHealth = BackendHealth.REACHABLE,
                    lastSyncedAt = null
                ),
                pendingCount = 0,
                failedCount = 0,
                activeCompanyId = "cmp_1",
                persistedLastSyncedAt = null
            )
        )

        // 10. Fully synchronized ONLY when configured, authenticated, online, reachable, 0 pending/failed, AND verified timestamp
        assertEquals(
            CloudSyncStatus.FULLY_SYNCHRONIZED,
            SyncStatusResolver.resolve(
                state = CloudSyncUiState(
                    firebaseConfigured = true,
                    isAuthenticated = true,
                    isOnline = true,
                    backendHealth = BackendHealth.REACHABLE,
                    lastSyncedAt = 1_750_000_000_000L
                ),
                pendingCount = 0,
                failedCount = 0,
                activeCompanyId = "cmp_1"
            )
        )
    }

    @Test
    fun firebaseErrorClassification_distinguishesPermissionOfflineAndMissingBackend() {
        assertEquals(
            BackendHealth.ACCESS_DENIED,
            FirebaseServices.classifyBackendError(RuntimeException("PERMISSION_DENIED: Missing or insufficient permissions")).health
        )
        assertEquals(
            BackendHealth.BACKEND_NOT_CONFIGURED,
            FirebaseServices.classifyBackendError(RuntimeException("database (default) does not exist for project")).health
        )
        assertEquals(
            BackendHealth.OFFLINE,
            FirebaseServices.classifyBackendError(RuntimeException("Failed to get document because the client is offline")).health
        )
        assertEquals(
            BackendHealth.NOT_SIGNED_IN,
            FirebaseServices.classifyBackendError(RuntimeException("UNAUTHENTICATED")).health
        )
    }

    @Test
    fun offlineRouting_handlesLargeFleetOfStoresQuicklyAndDeterministically() = runTest {
        val client = OsmRoutesClient()
        val stops = (0 until 120).map { i ->
            GeoPoint(34.05 + (i % 15) * 0.01, -118.25 - (i / 15) * 0.01)
        }
        val res = client.computeRoute(stops, routingBaseUrl = "", optimizeIntermediates = true)
        assertTrue(res.isSuccess)
        val route = res.getOrThrow()
        assertFalse(route.isStreetRoute)
        assertEquals(120, route.optimizedStopIndices.size)
        assertEquals(120, route.optimizedStopIndices.toSet().size)
    }

    @Test
    fun idempotentAuditUploader_handlesCreateDuplicateRetryTamperingAndGenuinePermissionDenied() = runTest {
        val entry = AuditLogEntity(
            id = "audit_company_reg_1",
            companyId = "cmp_farster",
            actorUid = "uid_owner_1",
            actorName = "Owner",
            actorRole = EmployeeRole.OWNER,
            action = "COMPANY_REGISTERED",
            targetType = "company",
            targetId = "cmp_farster",
            summary = "Registered company 'FARSTER LLC' (America/Los_Angeles)",
            createdAt = 1_750_000_000_000L
        )
        val serverStore = mutableMapOf<String, RemoteAuditDocument>()
        var createCalls = 0
        var fetchCalls = 0
        var denyAllCreates = false

        val strictAppendOnlyGateway = object : AuditRemoteGateway {
            override suspend fun createAudit(companyId: String, logId: String, payload: Map<String, Any?>) {
                createCalls++
                if (denyAllCreates || serverStore.containsKey(logId)) {
                    // Mirrors Firestore append-only rule: allow update, delete: if false
                    throw IllegalStateException("PERMISSION_DENIED: Missing or insufficient permissions.")
                }
                serverStore[logId] = RemoteAuditDocument(
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
                    serverTimestampMillis = 1_750_000_123_456L
                )
            }

            override suspend fun fetchAuditFromServer(companyId: String, logId: String): RemoteAuditDocument? {
                fetchCalls++
                return serverStore[logId]
            }
        }

        // 1. First create succeeds normally
        val created = IdempotentAuditUploader.uploadIdempotently(
            entry = entry,
            actorUidToSend = entry.actorUid,
            preferVerifyFirst = false,
            gateway = strictAppendOnlyGateway,
            serverTimestampValue = "SERVER_TS"
        )
        assertEquals(AuditUploadResolution.CREATED, created.resolution)
        assertEquals(1, createCalls)
        assertEquals(0, fetchCalls)

        // 2. Duplicate upload (e.g. after dropped network ack before queue delete) hits PERMISSION_DENIED,
        // verifies matching remote immutable fields, and succeeds idempotently
        val dupAfterAckDrop = IdempotentAuditUploader.uploadIdempotently(
            entry = entry,
            actorUidToSend = entry.actorUid,
            preferVerifyFirst = false,
            gateway = strictAppendOnlyGateway,
            serverTimestampValue = "SERVER_TS"
        )
        assertEquals(AuditUploadResolution.ALREADY_EXISTS_VERIFIED, dupAfterAckDrop.resolution)
        assertEquals(1_750_000_123_456L, dupAfterAckDrop.serverTimestampMillis)
        assertEquals(2, createCalls)
        assertEquals(1, fetchCalls)

        // 3. Explicit retry (preferVerifyFirst = true) verifies server doc directly without a doomed write
        val explicitRetry = IdempotentAuditUploader.uploadIdempotently(
            entry = entry,
            actorUidToSend = entry.actorUid,
            preferVerifyFirst = true,
            gateway = strictAppendOnlyGateway,
            serverTimestampValue = "SERVER_TS"
        )
        assertEquals(AuditUploadResolution.ALREADY_EXISTS_VERIFIED, explicitRetry.resolution)
        assertEquals(2, createCalls) // No extra doomed create call
        assertEquals(2, fetchCalls)

        // 4. Remote document with tampered immutable field MUST be rejected (never falsely treated as success)
        serverStore[entry.id] = serverStore[entry.id]!!.copy(summary = "TAMPERED SUMMARY")
        val tamperedResult = runCatching {
            IdempotentAuditUploader.uploadIdempotently(
                entry = entry,
                actorUidToSend = entry.actorUid,
                preferVerifyFirst = true,
                gateway = strictAppendOnlyGateway,
                serverTimestampValue = "SERVER_TS"
            )
        }
        assertTrue(tamperedResult.isFailure)
        assertTrue(tamperedResult.exceptionOrNull()!!.message!!.contains("mismatched", ignoreCase = true))

        // 5. Genuine PERMISSION_DENIED when document does NOT exist on server MUST fail and preserve error
        serverStore.clear()
        denyAllCreates = true
        val genuineDenied = runCatching {
            IdempotentAuditUploader.uploadIdempotently(
                entry = entry,
                actorUidToSend = entry.actorUid,
                preferVerifyFirst = true,
                gateway = strictAppendOnlyGateway,
                serverTimestampValue = "SERVER_TS"
            )
        }
        assertTrue(genuineDenied.isFailure)
        assertTrue(genuineDenied.exceptionOrNull()!!.message!!.contains("PERMISSION_DENIED", ignoreCase = true))
    }

    @Test
    fun idempotentAuditUploader_rejectsMissingCreatedAtAndLocalUidFallback() = runTest {
        val localEntry = AuditLogEntity(
            id = "audit_local_1",
            companyId = "cmp_farster",
            actorUid = "local_offline_owner_1",
            actorName = "Owner",
            actorRole = EmployeeRole.OWNER,
            action = "COMPANY_REGISTERED",
            targetType = "company",
            targetId = "cmp_farster",
            summary = "Registered company",
            createdAt = 1_750_000_000_000L
        )
        val missingCreatedAtRemote = RemoteAuditDocument(
            documentId = localEntry.id,
            id = localEntry.id,
            companyId = localEntry.companyId,
            actorUid = "firebase_uid_owner",
            actorName = localEntry.actorName,
            actorRole = localEntry.actorRole,
            action = localEntry.action,
            targetType = localEntry.targetType,
            targetId = localEntry.targetId,
            summary = localEntry.summary,
            createdAt = null,
            serverTimestampMillis = 1_750_000_123_456L
        )
        assertFalse(
            IdempotentAuditUploader.matchesImmutableAudit(
                entry = localEntry,
                expectedActorUid = "firebase_uid_owner",
                remote = missingCreatedAtRemote
            )
        )

        // When entry.actorUid is local_*, remote doc holding local_* MUST NOT match a different expectedActorUid
        val localUidRemote = missingCreatedAtRemote.copy(
            actorUid = "local_offline_owner_1",
            createdAt = localEntry.createdAt
        )
        assertFalse(
            IdempotentAuditUploader.matchesImmutableAudit(
                entry = localEntry,
                expectedActorUid = "firebase_uid_owner",
                remote = localUidRemote
            )
        )

        val validRemote = localUidRemote.copy(actorUid = "firebase_uid_owner")
        assertTrue(
            IdempotentAuditUploader.matchesImmutableAudit(
                entry = localEntry,
                expectedActorUid = "firebase_uid_owner",
                remote = validRemote
            )
        )
    }

    @Test
    fun invoiceSyncValidator_uploadInvoiceMetadataIdempotently_recoversPartialSuccessAndEnforcesImmutability() = runTest {
        val page = InvoicePageEntity(
            id = "pg_1",
            invoiceId = "inv_meta_1",
            pageIndex = 0,
            originalLocalPath = "/tmp/orig.jpg",
            editedLocalPath = "/tmp/edit.jpg",
            originalStoragePath = "companies/cmp_1/invoices/inv_meta_1/orig_pg_1.jpg",
            editedStoragePath = "companies/cmp_1/invoices/inv_meta_1/edit_pg_1.jpg",
            originalSha256 = "sha_o",
            editedSha256 = "sha_e",
            byteSize = 1024L
        )
        val signedInvoice = InvoiceEntity(
            id = "inv_meta_1",
            companyId = "cmp_1",
            jobId = "job_1",
            storeId = "store_1",
            storeName = "Glendale Deli",
            invoiceNumber = "INV-1001",
            status = InvoiceStatus.PENDING_UPLOAD,
            version = 1,
            createdByUid = "uid_drv",
            createdByName = "Driver",
            createdAt = 1_750_000_000_000L,
            modifiedAt = 1_750_000_050_000L,
            signed = true,
            recipientName = "Elena Petrova",
            recipientTitle = "Manager",
            acknowledgmentConfirmed = true,
            signatureStoragePath = "companies/cmp_1/invoices/inv_meta_1/signature.png",
            signedPdfStoragePath = "companies/cmp_1/invoices/inv_meta_1/signed_v1.pdf",
            signedPdfSha256 = "deadbeef1234",
            totalBytes = 2048L,
            syncState = SyncState.PENDING
        )
        val signedIwp = InvoiceWithPages(signedInvoice, listOf(page))

        // Signed invoice missing signedPdfSha256 MUST fail validation before metadata sync
        val missingShaEx = runCatching {
            InvoiceSyncValidator.assertInvoiceReadyForMetadataSync(
                InvoiceWithPages(signedInvoice.copy(signedPdfSha256 = ""), listOf(page))
            )
        }.exceptionOrNull() as? InvoiceUploadException
        assertEquals(InvoiceUploadErrorCode.CHECKSUM_MISMATCH, missingShaEx?.code)

        var remoteSnapshot: RemoteInvoiceSnapshot? = null
        var createShouldDeny = false
        var createCalls = 0
        var mergeCalls = 0
        val recordedTargetedUpdates = mutableListOf<Map<String, Any?>>()

        val fakeGateway = object : InvoiceMetadataGateway {
            override suspend fun fetchInvoiceFromServer(companyId: String, invoiceId: String): RemoteInvoiceSnapshot? =
                remoteSnapshot

            override suspend fun createInvoice(companyId: String, invoiceId: String, payload: Map<String, Any?>) {
                createCalls++
                if (createShouldDeny || remoteSnapshot != null) {
                    throw IllegalStateException("PERMISSION_DENIED: Signed invoice is immutable.")
                }
                remoteSnapshot = RemoteInvoiceSnapshot(
                    documentId = invoiceId,
                    id = invoiceId,
                    companyId = companyId,
                    storeId = signedInvoice.storeId,
                    invoiceNumber = signedInvoice.invoiceNumber,
                    version = signedInvoice.version,
                    status = payload["status"] as String,
                    signed = payload["signed"] as Boolean,
                    recipientName = signedInvoice.recipientName,
                    signatureStoragePath = signedInvoice.signatureStoragePath,
                    signedPdfStoragePath = signedInvoice.signedPdfStoragePath,
                    signedPdfSha256 = signedInvoice.signedPdfSha256,
                    signedServerAtMillis = 1_750_000_060_000L
                )
            }

            override suspend fun mergeUnsignedInvoice(companyId: String, invoiceId: String, payload: Map<String, Any?>) {
                mergeCalls++
            }

            override suspend fun updateSignedInvoiceFields(companyId: String, invoiceId: String, fields: Map<String, Any?>) {
                recordedTargetedUpdates += fields
                val cur = remoteSnapshot!!
                remoteSnapshot = cur.copy(
                    status = (fields["status"] as? String) ?: cur.status,
                    signedServerAtMillis = cur.signedServerAtMillis ?: if (fields.containsKey("signedServerAt")) 1_750_000_070_000L else null
                )
            }
        }

        val buildPayload: (InvoiceWithPages, String) -> Map<String, Any?> = { iwp, finalStatus ->
            mapOf("id" to iwp.invoice.id, "status" to finalStatus, "signed" to iwp.invoice.signed)
        }

        // 1. Fresh signed invoice creates once
        val created = InvoiceSyncValidator.uploadInvoiceMetadataIdempotently(
            iwp = signedIwp,
            finalStatus = InvoiceStatus.SYNCHRONIZED,
            wasRetry = false,
            gateway = fakeGateway,
            buildFullPayload = buildPayload,
            serverTimestampValue = "SERVER_TS"
        )
        assertEquals(InvoiceMetadataUploadResolution.CREATED, created.resolution)
        assertEquals(1, createCalls)

        // 2. Retry after dropped ack finds SYNCHRONIZED doc matching all immutable fields -> ALREADY_SYNCED_VERIFIED
        val verified = InvoiceSyncValidator.uploadInvoiceMetadataIdempotently(
            iwp = signedIwp,
            finalStatus = InvoiceStatus.SYNCHRONIZED,
            wasRetry = true,
            gateway = fakeGateway,
            buildFullPayload = buildPayload,
            serverTimestampValue = "SERVER_TS"
        )
        assertEquals(InvoiceMetadataUploadResolution.ALREADY_SYNCED_VERIFIED, verified.resolution)
        assertEquals(1_750_000_060_000L, verified.signedServerAtMillis)
        assertEquals(1, createCalls)
        assertTrue(recordedTargetedUpdates.isEmpty())

        // 3. Partial success where remote is SIGNED (without signedServerAt) -> 4-field targeted finalization
        remoteSnapshot = remoteSnapshot!!.copy(status = InvoiceStatus.SIGNED, signedServerAtMillis = null)
        val finalized = InvoiceSyncValidator.uploadInvoiceMetadataIdempotently(
            iwp = signedIwp,
            finalStatus = InvoiceStatus.SYNCHRONIZED,
            wasRetry = true,
            gateway = fakeGateway,
            buildFullPayload = buildPayload,
            serverTimestampValue = "SERVER_TS"
        )
        assertEquals(InvoiceMetadataUploadResolution.FINALIZED_SIGNED, finalized.resolution)
        assertEquals(1, recordedTargetedUpdates.size)
        assertEquals(
            setOf("status", "modifiedAt", "serverUpdatedAt", "signedServerAt"),
            recordedTargetedUpdates.last().keys
        )
        assertEquals(InvoiceStatus.SYNCHRONIZED, remoteSnapshot!!.status)

        // 4. Voiding signed invoice uses targeted 4-field void update without rewriting immutable fields (and blank reason falls back to "Voided")
        val voided = InvoiceSyncValidator.uploadInvoiceMetadataIdempotently(
            iwp = InvoiceWithPages(signedInvoice.copy(status = InvoiceStatus.VOIDED, voidedReason = "  "), listOf(page)),
            finalStatus = InvoiceStatus.VOIDED,
            wasRetry = false,
            gateway = fakeGateway,
            buildFullPayload = buildPayload,
            serverTimestampValue = "SERVER_TS"
        )
        assertEquals(InvoiceMetadataUploadResolution.VOIDED_SIGNED, voided.resolution)
        assertEquals(
            setOf("status", "voidedReason", "modifiedAt", "serverUpdatedAt"),
            recordedTargetedUpdates.last().keys
        )
        assertEquals("Voided", recordedTargetedUpdates.last()["voidedReason"])

        // 4b. resolveEditedAttachmentSource aligns PNG/JPEG and fallback to originalLocalPath
        val fallbackPngPage = page.copy(originalLocalPath = "/tmp/scan.png", editedLocalPath = "", editedSha256 = "")
        assertEquals(Triple("/tmp/scan.png", "image/png", "png"), InvoiceSyncValidator.resolveEditedAttachmentSource(fallbackPngPage))
        assertEquals(Triple("/tmp/edit.jpg", "image/jpeg", "jpg"), InvoiceSyncValidator.resolveEditedAttachmentSource(page))

        // 5. Trying to un-void a server-VOIDED invoice MUST fail
        val unvoidAttempt = runCatching {
            InvoiceSyncValidator.uploadInvoiceMetadataIdempotently(
                iwp = signedIwp,
                finalStatus = InvoiceStatus.SYNCHRONIZED,
                wasRetry = true,
                gateway = fakeGateway,
                buildFullPayload = buildPayload,
                serverTimestampValue = "SERVER_TS"
            )
        }
        assertTrue(unvoidAttempt.isFailure)
        assertTrue(unvoidAttempt.exceptionOrNull()!!.message!!.contains("voided", ignoreCase = true))

        // 6. Tampered remote signedPdfSha256 MUST be rejected
        remoteSnapshot = remoteSnapshot!!.copy(status = InvoiceStatus.SYNCHRONIZED, signedPdfSha256 = "tampered_hash")
        val tamperAttempt = runCatching {
            InvoiceSyncValidator.uploadInvoiceMetadataIdempotently(
                iwp = signedIwp,
                finalStatus = InvoiceStatus.SYNCHRONIZED,
                wasRetry = true,
                gateway = fakeGateway,
                buildFullPayload = buildPayload,
                serverTimestampValue = "SERVER_TS"
            )
        }
        assertTrue(tamperAttempt.isFailure)
        assertTrue(tamperAttempt.exceptionOrNull()!!.message!!.contains("mismatched", ignoreCase = true))

        // 7. Genuine PERMISSION_DENIED when no remote doc exists MUST propagate
        remoteSnapshot = null
        createShouldDeny = true
        val genuineDenied = runCatching {
            InvoiceSyncValidator.uploadInvoiceMetadataIdempotently(
                iwp = signedIwp,
                finalStatus = InvoiceStatus.SYNCHRONIZED,
                wasRetry = false,
                gateway = fakeGateway,
                buildFullPayload = buildPayload,
                serverTimestampValue = "SERVER_TS"
            )
        }
        assertTrue(genuineDenied.isFailure)
        assertTrue(genuineDenied.exceptionOrNull()!!.message!!.contains("PERMISSION_DENIED"))

        // 8. Unsigned invoice already on server merges cleanly
        createShouldDeny = false
        val unsignedIwp = InvoiceWithPages(signedInvoice.copy(signed = false, status = InvoiceStatus.NOT_SIGNED), listOf(page))
        remoteSnapshot = RemoteInvoiceSnapshot(
            documentId = signedInvoice.id, id = signedInvoice.id, companyId = signedInvoice.companyId,
            storeId = signedInvoice.storeId, invoiceNumber = signedInvoice.invoiceNumber, version = 1,
            status = InvoiceStatus.NOT_SIGNED, signed = false, recipientName = "",
            signatureStoragePath = null, signedPdfStoragePath = null, signedPdfSha256 = null, signedServerAtMillis = null
        )
        val mergedUnsigned = InvoiceSyncValidator.uploadInvoiceMetadataIdempotently(
            iwp = unsignedIwp,
            finalStatus = InvoiceStatus.NOT_SIGNED,
            wasRetry = true,
            gateway = fakeGateway,
            buildFullPayload = buildPayload,
            serverTimestampValue = "SERVER_TS"
        )
        assertEquals(InvoiceMetadataUploadResolution.MERGED_UNSIGNED, mergedUnsigned.resolution)
        assertEquals(1, mergeCalls)
    }

    @Test
    fun syncStatusResolver_usesPersistedErrorAndTimestampFallbacksAccurately() {
        assertEquals(
            CloudSyncStatus.SYNCHRONIZATION_FAILED,
            SyncStatusResolver.resolve(
                state = CloudSyncUiState(
                    firebaseConfigured = true,
                    isAuthenticated = true,
                    isOnline = true,
                    backendHealth = BackendHealth.REACHABLE,
                    lastError = null
                ),
                pendingCount = 0,
                failedCount = 0,
                activeCompanyId = "cmp_1",
                persistedLastSyncedAt = 1_750_000_000_000L,
                persistedLastError = "invoice (upsert) [inv_1] (attempt 2): [MISSING_FILE] Signed invoice PDF missing"
            )
        )
        assertEquals(
            CloudSyncStatus.FULLY_SYNCHRONIZED,
            SyncStatusResolver.resolve(
                state = CloudSyncUiState(
                    firebaseConfigured = true,
                    isAuthenticated = true,
                    isOnline = true,
                    backendHealth = BackendHealth.REACHABLE,
                    lastSyncedAt = null,
                    lastError = null
                ),
                pendingCount = 0,
                failedCount = 0,
                activeCompanyId = "cmp_1",
                persistedLastSyncedAt = 1_750_000_000_000L,
                persistedLastError = ""
            )
        )
    }
}
