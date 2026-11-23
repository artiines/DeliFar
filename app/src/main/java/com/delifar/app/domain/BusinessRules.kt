package com.delifar.app.domain

import com.delifar.app.data.EmployeeRole
import com.delifar.app.data.HoursMode
import com.delifar.app.data.JobListRow
import com.delifar.app.data.JobStatus
import com.delifar.app.data.StoreHoursEntity
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.TemporalAdjusters

object RolePermissions {
    fun canManageCompany(role: String): Boolean = role == EmployeeRole.OWNER

    fun canManageEmployees(role: String): Boolean = role == EmployeeRole.OWNER

    fun canManageStoresAndJobs(role: String): Boolean =
        role == EmployeeRole.OWNER || role == EmployeeRole.MANAGER

    fun canAssignDrivers(role: String): Boolean =
        role == EmployeeRole.OWNER || role == EmployeeRole.MANAGER

    fun canUpdateAssignedJobs(role: String): Boolean =
        role == EmployeeRole.OWNER || role == EmployeeRole.MANAGER || role == EmployeeRole.DRIVER

    fun canUploadAndSignInvoices(role: String): Boolean =
        role == EmployeeRole.OWNER || role == EmployeeRole.MANAGER || role == EmployeeRole.DRIVER

    fun canViewAllInvoices(role: String): Boolean =
        role == EmployeeRole.OWNER || role == EmployeeRole.MANAGER || role == EmployeeRole.ACCOUNTANT

    fun canExportInvoices(role: String): Boolean =
        role == EmployeeRole.OWNER || role == EmployeeRole.MANAGER || role == EmployeeRole.ACCOUNTANT

    fun canVoidOrCorrectInvoices(role: String): Boolean =
        role == EmployeeRole.OWNER || role == EmployeeRole.MANAGER
}

object BusinessRules {
    val LA_ZONE: ZoneId = ZoneId.of("America/Los_Angeles")
    val SUPPORTED_TIMEZONES: List<String> = listOf(
        "America/Los_Angeles",
        "America/Denver",
        "America/Chicago",
        "America/New_York",
        "Asia/Yerevan",
        "Europe/Moscow",
        "Europe/London",
        "UTC"
    )

    private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val DATETIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")
    private val QUANTITY_REGEX = Regex("^\\d{1,12}(\\.\\d{1,6})?$")

    fun resolveZone(zoneIdString: String?): ZoneId {
        if (zoneIdString.isNullOrBlank()) return LA_ZONE
        return runCatching { ZoneId.of(zoneIdString.trim()) }.getOrDefault(LA_ZONE)
    }

    fun nowInZone(zoneId: ZoneId = LA_ZONE, clock: Clock = Clock.systemUTC()): ZonedDateTime =
        ZonedDateTime.now(clock).withZoneSameInstant(zoneId)

    fun nowLa(clock: Clock = Clock.systemUTC()): ZonedDateTime = nowInZone(LA_ZONE, clock)

    fun todayInZone(zoneId: ZoneId = LA_ZONE, clock: Clock = Clock.systemUTC()): LocalDate =
        nowInZone(zoneId, clock).toLocalDate()

    fun todayLa(clock: Clock = Clock.systemUTC()): LocalDate = todayInZone(LA_ZONE, clock)

    fun todayIso(zoneId: ZoneId = LA_ZONE, clock: Clock = Clock.systemUTC()): String =
        todayInZone(zoneId, clock).toString()

    fun formatTimestamp(epochMs: Long, zoneId: ZoneId = LA_ZONE): String {
        return Instant.ofEpochMilli(epochMs).atZone(zoneId).format(DATETIME_FMT)
    }

    fun parseIsoDate(raw: String): LocalDate? = try {
        if (raw.length != 10) null else LocalDate.parse(raw)
    } catch (_: DateTimeParseException) {
        null
    }

    fun parseIsoTime(raw: String?): LocalTime? {
        if (raw.isNullOrBlank()) return null
        if (!Regex("^\\d{2}:\\d{2}$").matches(raw)) return null
        return try {
            LocalTime.parse(raw, TIME_FMT)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    fun formatMinuteOfDay(minute: Int): String {
        val clamped = minute.coerceIn(0, 1439)
        return "%02d:%02d".format(clamped / 60, clamped % 60)
    }

    fun parseMinuteOfDay(raw: String): Int? {
        val t = parseIsoTime(raw.trim()) ?: return null
        return t.hour * 60 + t.minute
    }

    /** Monday–Sunday week range containing [date]. */
    fun weekRange(date: LocalDate): Pair<LocalDate, LocalDate> {
        val monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val sunday = monday.plusDays(6)
        return monday to sunday
    }

    fun weekDays(date: LocalDate): List<LocalDate> {
        val (monday, _) = weekRange(date)
        return (0L..6L).map { monday.plusDays(it) }
    }

    fun isOverdue(jobDate: String, status: String, today: LocalDate = todayLa()): Boolean {
        if (status !in JobStatus.ACTIVE) return false
        val parsed = parseIsoDate(jobDate) ?: return false
        return parsed.isBefore(today)
    }

    fun isScheduledInPastMoment(
        jobDate: String,
        jobTime: String?,
        now: ZonedDateTime = nowLa()
    ): Boolean {
        val d = parseIsoDate(jobDate) ?: return false
        if (d.isBefore(now.toLocalDate())) return true
        if (d.isAfter(now.toLocalDate())) return false
        val t = parseIsoTime(jobTime) ?: return false
        return t.isBefore(now.toLocalTime())
    }

    fun jobInstant(date: String, time: String, zoneId: ZoneId = LA_ZONE): Instant? {
        val d = parseIsoDate(date) ?: return null
        val t = parseIsoTime(time) ?: return null
        return ZonedDateTime.of(d, t, zoneId).toInstant()
    }

    fun jobInstantLa(date: String, time: String): Instant? = jobInstant(date, time, LA_ZONE)

    /**
     * Parses decimal quantity allowing '.' or ',' as locale separator.
     * Enforces up to 12 integer digits and 6 fractional digits.
     */
    fun normalizeQuantity(input: String, allowZero: Boolean): String? {
        val trimmed = input.trim().replace(" ", "")
        if (trimmed.isEmpty()) return null
        val normalized = if (trimmed.contains(',') && !trimmed.contains('.')) {
            trimmed.replace(',', '.')
        } else {
            trimmed
        }
        if (!QUANTITY_REGEX.matches(normalized)) return null
        val bd = try {
            BigDecimal(normalized)
        } catch (_: NumberFormatException) {
            return null
        }
        val minOk = if (allowZero) bd.compareTo(BigDecimal.ZERO) >= 0 else bd.compareTo(BigDecimal.ZERO) > 0
        if (!minOk) return null
        return bd.stripTrailingZeros().let { stripped ->
            if (stripped.scale() < 0) stripped.setScale(0) else stripped
        }.toPlainString()
    }

    data class TimeWindow(val start: LocalTime, val end: LocalTime)

    fun parseAndValidateWindows(raw: String): List<TimeWindow>? {
        if (raw.isBlank()) return emptyList()
        val parts = raw.split(";").map { it.trim() }
        if (parts.any { it.isEmpty() }) return null
        val windows = mutableListOf<TimeWindow>()
        for (part in parts) {
            val bounds = part.split("-")
            if (bounds.size != 2) return null
            val start = parseIsoTime(bounds[0].trim()) ?: return null
            val end = parseIsoTime(bounds[1].trim()) ?: return null
            if (!start.isBefore(end)) return null
            windows += TimeWindow(start, end)
        }
        val sorted = windows.sortedBy { it.start }
        for (i in 1 until sorted.size) {
            if (!sorted[i - 1].end.isBefore(sorted[i].start) && sorted[i - 1].end != sorted[i].start) {
                return null // Overlapping
            }
        }
        return sorted
    }

    fun formatWindowsCanonical(windows: List<TimeWindow>): String =
        windows.sortedBy { it.start }.joinToString(";") { "${it.start.format(TIME_FMT)}-${it.end.format(TIME_FMT)}" }

    enum class HoursCheckResult {
        OK,
        CLOSED,
        UNKNOWN,
        OUTSIDE_WINDOWS
    }

    fun checkReceivingHours(
        hours: List<StoreHoursEntity>,
        dateIso: String,
        timeIso: String?
    ): HoursCheckResult {
        val date = parseIsoDate(dateIso) ?: return HoursCheckResult.UNKNOWN
        val dayNum = date.dayOfWeek.value // 1=Mon..7=Sun
        val entry = hours.find { it.day == dayNum } ?: return HoursCheckResult.UNKNOWN
        return when (entry.mode) {
            HoursMode.CLOSED -> HoursCheckResult.CLOSED
            HoursMode.UNKNOWN -> HoursCheckResult.UNKNOWN
            HoursMode.OPEN -> {
                val time = parseIsoTime(timeIso) ?: return HoursCheckResult.OK
                val windows = parseAndValidateWindows(entry.windows) ?: return HoursCheckResult.UNKNOWN
                if (windows.isEmpty()) return HoursCheckResult.UNKNOWN
                val inside = windows.any { !time.isBefore(it.start) && !time.isAfter(it.end) }
                if (inside) HoursCheckResult.OK else HoursCheckResult.OUTSIDE_WINDOWS
            }
            else -> HoursCheckResult.UNKNOWN
        }
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun generateInviteCode(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val rnd = java.security.SecureRandom()
        return (1..8).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
    }

    fun sortJobsForDay(rows: List<JobListRow>): List<JobListRow> {
        return rows.sortedWith(
            compareBy<JobListRow>(
                { it.job.status !in JobStatus.ACTIVE }, // active first
                { it.job.date },
                { it.job.time == null }, // timed before untimed
                { it.job.time ?: "99:99" },
                { it.job.created }
            )
        )
    }
}
