package com.delifar.app

import com.delifar.app.data.EmployeeRole
import com.delifar.app.data.HoursMode
import com.delifar.app.data.JobStatus
import com.delifar.app.data.StoreHoursEntity
import com.delifar.app.domain.BusinessRules
import com.delifar.app.domain.RolePermissions
import com.delifar.app.network.GoogleRoutesClient
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
        // Monday 2026-06-15
        assertEquals(
            BusinessRules.HoursCheckResult.OK,
            BusinessRules.checkReceivingHours(hours, "2026-06-15", "09:30")
        )
        assertEquals(
            BusinessRules.HoursCheckResult.OUTSIDE_WINDOWS,
            BusinessRules.checkReceivingHours(hours, "2026-06-15", "12:30")
        )
        // Sunday 2026-06-14
        assertEquals(
            BusinessRules.HoursCheckResult.CLOSED,
            BusinessRules.checkReceivingHours(hours, "2026-06-14", "10:00")
        )
        // Tuesday 2026-06-16 (not configured -> UNKNOWN)
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
    fun googlePolylineDecoder_decodesStandardEncodedPolyline() {
        // Canonical Google Maps encoded polyline example: (38.5, -120.2), (40.7, -120.95), (43.252, -126.453)
        val encoded = "_p~iF~ps|U_ulLnnqC_mqNvxq`@"
        val pts = GoogleRoutesClient().decodePolyline(encoded)
        assertNotNull(pts)
        assertEquals(3, pts.size)
        assertEquals(38.5, pts[0].latitude, 0.001)
        assertEquals(-120.2, pts[0].longitude, 0.001)
        assertEquals(40.7, pts[1].latitude, 0.001)
        assertEquals(-120.95, pts[1].longitude, 0.001)
        assertEquals(43.252, pts[2].latitude, 0.001)
        assertEquals(-126.453, pts[2].longitude, 0.001)
    }
}
