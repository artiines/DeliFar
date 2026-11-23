package com.delifar.app.data

import androidx.room.withTransaction
import com.delifar.app.domain.BusinessRules
import com.delifar.app.domain.RolePermissions
import com.delifar.app.network.GeoValidation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class DraftItemInput(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val planned: String = "",
    val unit: String = "cs"
)

data class MigrationSummary(
    val storesCount: Int,
    val jobsCount: Int,
    val invoicesCount: Int
) {
    val totalCount: Int get() = storesCount + jobsCount + invoicesCount
}

class DeliFarRepository(
    val db: DeliFarDatabase
) {
    private val companyDao = db.companyDao()
    private val employeeDao = db.employeeDao()
    private val invitationDao = db.invitationDao()
    private val storeDao = db.storeDao()
    private val jobDao = db.jobDao()
    private val invoiceDao = db.invoiceDao()
    private val auditLogDao = db.auditLogDao()
    private val syncQueueDao = db.syncQueueDao()
    private val prefDao = db.preferenceDao()
    private val draftDao = db.draftDao()
    private val receiptDao = db.receiptDao()
    private val searchCacheDao = db.searchCacheDao()

    fun observeCompanies(): Flow<List<CompanyEntity>> = companyDao.observeAll()
    fun observeCompany(companyId: String): Flow<CompanyEntity?> = companyDao.observeCompany(companyId)
    fun observeEmployees(companyId: String): Flow<List<EmployeeEntity>> = employeeDao.observeByCompany(companyId)
    fun observeEmployee(uid: String): Flow<EmployeeEntity?> = employeeDao.observeByUid(uid)
    fun observeInvitations(companyId: String): Flow<List<InvitationEntity>> = invitationDao.observeByCompany(companyId)
    fun observeStores(companyId: String): Flow<List<StoreWithHours>> =
        storeDao.observeStoresWithHoursByCompany(companyId)
    fun observeStore(storeId: String): Flow<StoreWithHours?> = storeDao.observeStoreWithHours(storeId)
    fun observeJobRows(companyId: String): Flow<List<JobListRow>> =
        jobDao.observeJobRowsByCompany(companyId)
    fun observeJobWithItems(jobId: String): Flow<JobWithItems?> = jobDao.observeJobWithItems(jobId)
    fun observeInvoices(companyId: String): Flow<List<InvoiceWithPages>> =
        invoiceDao.observeInvoicesWithPagesByCompany(companyId)
    fun observeInvoicesForJob(jobId: String, companyId: String? = null): Flow<List<InvoiceWithPages>> =
        if (companyId != null) invoiceDao.observeInvoicesForJobInCompany(companyId, jobId)
        else invoiceDao.observeInvoicesForJob(jobId)
    fun observeInvoice(invoiceId: String): Flow<InvoiceWithPages?> = invoiceDao.observeInvoice(invoiceId)
    fun observeAuditLogs(companyId: String): Flow<List<AuditLogEntity>> =
        auditLogDao.observeByCompany(companyId)
    fun observePendingSyncCount(companyId: String): Flow<Int> =
        if (companyId.isBlank()) syncQueueDao.observePendingCountByCompany("")
        else syncQueueDao.observePendingCountByCompany(companyId)
    fun observeFailedSyncCount(companyId: String): Flow<Int> =
        if (companyId.isBlank()) syncQueueDao.observeFailedCountByCompany("")
        else syncQueueDao.observeFailedCountByCompany(companyId)
    fun observeLatestDraft(): Flow<DraftEntity?> = draftDao.observeLatestDraft()

    fun observePreferences(): Flow<AppPreferences> =
        prefDao.observeAll().map { list -> AppPreferences.fromMap(list.associate { it.key to it.value }) }

    suspend fun getPreferences(): AppPreferences {
        val list = prefDao.getAll()
        return AppPreferences.fromMap(list.associate { it.key to it.value })
    }

    suspend fun setPreference(key: String, value: String) {
        prefDao.upsert(PreferenceEntity(key, value))
    }

    suspend fun findStoreByProviderId(providerId: String, companyId: String = ""): StoreEntity? =
        storeDao.findByProviderIdInCompany(companyId, providerId)

    suspend fun getStoreWithHours(storeId: String): StoreWithHours? =
        storeDao.getStoreWithHours(storeId)

    suspend fun getCompany(companyId: String): CompanyEntity? = companyDao.getCompany(companyId)

    suspend fun getInvoiceWithPages(invoiceId: String): InvoiceWithPages? =
        invoiceDao.getInvoiceWithPages(invoiceId)

    // --- Company & Multi-User Operations ---

    suspend fun registerCompany(
        companyName: String,
        ownerUid: String,
        ownerName: String,
        ownerEmail: String,
        timezone: String,
        defaultArea: String
    ): Result<CompanyEntity> {
        val trimmed = companyName.trim()
        if (trimmed.length < 2) {
            return Result.failure(IllegalArgumentException("Company name must be at least 2 characters."))
        }
        val safeTz = BusinessRules.resolveZone(timezone).id
        val now = System.currentTimeMillis()
        val companyId = "cmp_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val uid = ownerUid.ifBlank { "local_${UUID.randomUUID().toString().take(12)}" }
        val company = CompanyEntity(
            id = companyId,
            name = trimmed,
            ownerUid = uid,
            timezone = safeTz,
            defaultArea = defaultArea.takeIf { it in SearchArea.ALL } ?: SearchArea.LA,
            storageUsedBytes = 0L,
            storageQuotaBytes = 5L * 1024L * 1024L * 1024L,
            created = now,
            modified = now
        )
        val ownerEmployee = EmployeeEntity(
            uid = uid,
            companyId = companyId,
            displayName = ownerName.trim().ifEmpty { "Owner" },
            email = ownerEmail.trim().lowercase(),
            role = EmployeeRole.OWNER,
            active = true,
            invitedByUid = uid,
            joinedAt = now,
            lastActiveAt = now
        )

        db.withTransaction {
            companyDao.upsert(company)
            employeeDao.upsert(ownerEmployee)
            prefDao.upsertAll(
                listOf(
                    PreferenceEntity(PrefKeys.ACTIVE_COMPANY_ID, companyId),
                    PreferenceEntity(PrefKeys.ACTIVE_USER_UID, uid),
                    PreferenceEntity(PrefKeys.ACTIVE_USER_NAME, ownerEmployee.displayName),
                    PreferenceEntity(PrefKeys.ACTIVE_USER_EMAIL, ownerEmployee.email),
                    PreferenceEntity(PrefKeys.ACTIVE_USER_ROLE, EmployeeRole.OWNER),
                    PreferenceEntity(PrefKeys.COMPANY_TIMEZONE, safeTz),
                    PreferenceEntity(PrefKeys.AREA, company.defaultArea)
                )
            )
            recordAuditInternal(
                companyId = companyId,
                actorUid = uid,
                actorName = ownerEmployee.displayName,
                actorRole = EmployeeRole.OWNER,
                action = "COMPANY_REGISTERED",
                targetType = "company",
                targetId = companyId,
                summary = "Registered company '${company.name}' ($safeTz)"
            )
            enqueueSyncInternal(companyId, "company", companyId, "upsert")
            enqueueSyncInternal(companyId, "employee", uid, "upsert")
        }
        return Result.success(company)
    }

    suspend fun updateCompanySettings(
        companyId: String,
        name: String,
        timezone: String,
        defaultArea: String,
        actorUid: String,
        actorName: String,
        actorRole: String
    ): Result<CompanyEntity> {
        if (!RolePermissions.canManageCompany(actorRole)) {
            return Result.failure(SecurityException("Only the company owner can change company settings."))
        }
        val existing = companyDao.getCompany(companyId)
            ?: return Result.failure(IllegalArgumentException("Company not found."))
        val trimmedName = name.trim()
        if (trimmedName.length < 2) {
            return Result.failure(IllegalArgumentException("Company name is too short."))
        }
        val safeTz = BusinessRules.resolveZone(timezone).id
        val safeArea = defaultArea.takeIf { it in SearchArea.ALL } ?: existing.defaultArea
        val updated = existing.copy(
            name = trimmedName,
            timezone = safeTz,
            defaultArea = safeArea,
            modified = System.currentTimeMillis()
        )
        db.withTransaction {
            companyDao.upsert(updated)
            prefDao.upsert(PreferenceEntity(PrefKeys.COMPANY_TIMEZONE, safeTz))
            prefDao.upsert(PreferenceEntity(PrefKeys.AREA, safeArea))
            recordAuditInternal(
                companyId, actorUid, actorName, actorRole,
                "COMPANY_UPDATED", "company", companyId,
                "Updated company settings (${updated.name}, $safeTz)"
            )
            enqueueSyncInternal(companyId, "company", companyId, "upsert")
        }
        return Result.success(updated)
    }

    suspend fun createInvitation(
        companyId: String,
        companyName: String,
        email: String,
        role: String,
        actorUid: String,
        actorName: String,
        actorRole: String
    ): Result<InvitationEntity> {
        if (!RolePermissions.canManageEmployees(actorRole)) {
            return Result.failure(SecurityException("Only company owners can invite employees."))
        }
        val normalizedEmail = email.trim().lowercase()
        if (!normalizedEmail.contains("@") || normalizedEmail.length < 5) {
            return Result.failure(IllegalArgumentException("Enter a valid employee email address."))
        }
        if (role !in EmployeeRole.ALL || role == EmployeeRole.OWNER) {
            return Result.failure(IllegalArgumentException("Select Manager, Driver, or Accountant role."))
        }
        val now = System.currentTimeMillis()
        val invite = InvitationEntity(
            id = UUID.randomUUID().toString(),
            companyId = companyId,
            companyName = companyName,
            email = normalizedEmail,
            role = role,
            inviteCode = BusinessRules.generateInviteCode(),
            status = InviteStatus.PENDING,
            createdByUid = actorUid,
            createdByName = actorName,
            createdAt = now,
            expiresAt = now + 7L * 24 * 60 * 60 * 1000 // 7 days
        )
        db.withTransaction {
            invitationDao.upsert(invite)
            recordAuditInternal(
                companyId, actorUid, actorName, actorRole,
                "EMPLOYEE_INVITED", "invitation", invite.id,
                "Invited $normalizedEmail as $role (code ${invite.inviteCode})"
            )
            enqueueSyncInternal(companyId, "invitation", invite.id, "upsert")
        }
        return Result.success(invite)
    }

    suspend fun revokeInvitation(
        inviteId: String,
        actorUid: String,
        actorName: String,
        actorRole: String
    ): Result<Unit> {
        if (!RolePermissions.canManageEmployees(actorRole)) {
            return Result.failure(SecurityException("Only owners can revoke invitations."))
        }
        val invite = invitationDao.getById(inviteId)
            ?: return Result.failure(IllegalArgumentException("Invitation not found."))
        db.withTransaction {
            invitationDao.setStatus(inviteId, InviteStatus.REVOKED)
            recordAuditInternal(
                invite.companyId, actorUid, actorName, actorRole,
                "INVITATION_REVOKED", "invitation", inviteId,
                "Revoked invitation for ${invite.email}"
            )
            enqueueSyncInternal(invite.companyId, "invitation", inviteId, "upsert")
        }
        return Result.success(Unit)
    }

    suspend fun redeemInvitationLocally(
        inviteCode: String,
        userUid: String,
        displayName: String,
        email: String
    ): Result<EmployeeEntity> {
        val code = inviteCode.trim().uppercase()
        val invite = invitationDao.findByCode(code)
            ?: return Result.failure(IllegalArgumentException("Invitation code not found locally. Connect to Firebase to redeem cloud invitations."))
        if (invite.status != InviteStatus.PENDING) {
            return Result.failure(IllegalStateException("Invitation is no longer active (${invite.status})."))
        }
        val now = System.currentTimeMillis()
        if (now > invite.expiresAt) {
            invitationDao.setStatus(invite.id, InviteStatus.EXPIRED)
            return Result.failure(IllegalStateException("Invitation code has expired."))
        }
        val uid = userUid.ifBlank { "emp_${UUID.randomUUID().toString().take(12)}" }
        val employee = EmployeeEntity(
            uid = uid,
            companyId = invite.companyId,
            displayName = displayName.trim().ifEmpty { email.substringBefore("@") },
            email = email.trim().lowercase(),
            role = invite.role,
            active = true,
            invitedByUid = invite.createdByUid,
            joinedAt = now,
            lastActiveAt = now
        )
        db.withTransaction {
            employeeDao.upsert(employee)
            invitationDao.upsert(invite.copy(status = InviteStatus.ACCEPTED, acceptedByUid = uid))
            prefDao.upsertAll(
                listOf(
                    PreferenceEntity(PrefKeys.ACTIVE_COMPANY_ID, invite.companyId),
                    PreferenceEntity(PrefKeys.ACTIVE_USER_UID, uid),
                    PreferenceEntity(PrefKeys.ACTIVE_USER_NAME, employee.displayName),
                    PreferenceEntity(PrefKeys.ACTIVE_USER_EMAIL, employee.email),
                    PreferenceEntity(PrefKeys.ACTIVE_USER_ROLE, employee.role)
                )
            )
            recordAuditInternal(
                invite.companyId, uid, employee.displayName, employee.role,
                "INVITATION_ACCEPTED", "employee", uid,
                "${employee.displayName} joined as ${employee.role}"
            )
            enqueueSyncInternal(invite.companyId, "employee", uid, "upsert")
        }
        return Result.success(employee)
    }

    suspend fun updateEmployeeRole(
        targetUid: String,
        newRole: String,
        actorUid: String,
        actorName: String,
        actorRole: String
    ): Result<Unit> {
        if (!RolePermissions.canManageEmployees(actorRole)) {
            return Result.failure(SecurityException("Only company owners can change employee roles."))
        }
        if (newRole !in EmployeeRole.ALL) {
            return Result.failure(IllegalArgumentException("Invalid role."))
        }
        val emp = employeeDao.getByUid(targetUid)
            ?: return Result.failure(IllegalArgumentException("Employee not found."))
        if (emp.uid == actorUid && newRole != EmployeeRole.OWNER) {
            return Result.failure(IllegalStateException("Owner cannot demote themselves."))
        }
        val now = System.currentTimeMillis()
        db.withTransaction {
            employeeDao.setRole(targetUid, newRole, now)
            recordAuditInternal(
                emp.companyId, actorUid, actorName, actorRole,
                "EMPLOYEE_ROLE_CHANGED", "employee", targetUid,
                "Changed ${emp.displayName}'s role from ${emp.role} to $newRole"
            )
            enqueueSyncInternal(emp.companyId, "employee", targetUid, "upsert")
        }
        return Result.success(Unit)
    }

    suspend fun setEmployeeActive(
        targetUid: String,
        active: Boolean,
        actorUid: String,
        actorName: String,
        actorRole: String
    ): Result<Unit> {
        if (!RolePermissions.canManageEmployees(actorRole)) {
            return Result.failure(SecurityException("Only company owners can activate or deactivate employees."))
        }
        val emp = employeeDao.getByUid(targetUid)
            ?: return Result.failure(IllegalArgumentException("Employee not found."))
        if (emp.uid == actorUid && !active) {
            return Result.failure(IllegalStateException("Owner cannot deactivate their own account."))
        }
        val now = System.currentTimeMillis()
        db.withTransaction {
            employeeDao.setActive(targetUid, active, now)
            recordAuditInternal(
                emp.companyId, actorUid, actorName, actorRole,
                if (active) "EMPLOYEE_ACTIVATED" else "EMPLOYEE_DEACTIVATED",
                "employee", targetUid,
                "${if (active) "Activated" else "Deactivated"} ${emp.displayName}"
            )
            enqueueSyncInternal(emp.companyId, "employee", targetUid, "upsert")
        }
        return Result.success(Unit)
    }

    // --- Local-to-Cloud Data Migration ---

    suspend fun getUnmigratedLocalSummary(): MigrationSummary {
        return MigrationSummary(
            storesCount = storeDao.getUnmigratedStores().size,
            jobsCount = jobDao.getUnmigratedJobs().size,
            invoicesCount = invoiceDao.getUnmigratedInvoices().size
        )
    }

    /**
     * Requires explicit owner confirmation of which company receives existing local data.
     * Prevents duplication by preserving deterministic IDs and queueing each record once.
     */
    suspend fun migrateLocalDataToCompany(
        companyId: String,
        actorUid: String,
        actorName: String,
        actorRole: String
    ): Result<MigrationSummary> {
        if (!RolePermissions.canManageCompany(actorRole)) {
            return Result.failure(SecurityException("Only the company owner can confirm local data migration."))
        }
        val company = companyDao.getCompany(companyId)
            ?: return Result.failure(IllegalArgumentException("Target company not found."))
        val unmigratedStores = storeDao.getUnmigratedStores()
        val unmigratedJobs = jobDao.getUnmigratedJobs()
        val unmigratedInvoices = invoiceDao.getUnmigratedInvoices()

        db.withTransaction {
            storeDao.assignCompanyToUnmigrated(companyId)
            jobDao.assignCompanyToUnmigrated(companyId)
            invoiceDao.assignCompanyToUnmigrated(companyId)

            unmigratedStores.forEach { enqueueSyncInternal(companyId, "store", it.id, "upsert") }
            unmigratedJobs.forEach { enqueueSyncInternal(companyId, "job", it.id, "upsert") }
            unmigratedInvoices.forEach { enqueueSyncInternal(companyId, "invoice", it.id, "upsert") }

            recordAuditInternal(
                companyId, actorUid, actorName, actorRole,
                "LOCAL_DATA_MIGRATED", "company", companyId,
                "Migrated ${unmigratedStores.size} stores, ${unmigratedJobs.size} jobs, ${unmigratedInvoices.size} invoices into '${company.name}'"
            )
        }

        return Result.success(
            MigrationSummary(unmigratedStores.size, unmigratedJobs.size, unmigratedInvoices.size)
        )
    }

    // --- Stores ---

    suspend fun saveStore(
        existingId: String?,
        name: String,
        address: String,
        lat: Double,
        lon: Double,
        providerId: String?,
        phone: String,
        hours: List<StoreHoursEntity>,
        companyId: String = "",
        actorUid: String = "",
        actorName: String = "",
        actorRole: String = EmployeeRole.OWNER,
        locationConfirmed: Boolean = true,
        geocodeSource: String = "manual"
    ): Result<StoreEntity> {
        if (!RolePermissions.canManageStoresAndJobs(actorRole)) {
            return Result.failure(SecurityException("Only Owners and Managers can add or edit stores."))
        }
        val trimmedName = name.trim()
        val trimmedAddress = address.trim()
        if (trimmedName.isEmpty() || trimmedAddress.isEmpty()) {
            return Result.failure(IllegalArgumentException("Store name and full street address are required."))
        }
        if (!locationConfirmed) {
            return Result.failure(
                IllegalArgumentException("Locate the address and confirm the pin on the map before saving.")
            )
        }
        if (!GeoValidation.isValidCoordinate(lat, lon)) {
            return Result.failure(IllegalArgumentException("Valid map coordinates are required."))
        }
        val existing = existingId?.let { storeDao.getStore(it) }
        val effectiveCompanyId = companyId.ifBlank { existing?.companyId.orEmpty() }
        if (existing != null && companyId.isNotBlank() && existing.companyId.isNotBlank() && existing.companyId != companyId) {
            return Result.failure(SecurityException("Cannot modify a store belonging to another company."))
        }
        val cleanProvider = providerId?.trim()?.ifEmpty { null }
        if (cleanProvider != null) {
            val dup = storeDao.findByProviderIdInCompany(effectiveCompanyId, cleanProvider)
            if (dup != null && dup.id != existingId) {
                return Result.failure(IllegalStateException("DUPLICATE_PROVIDER"))
            }
        }
        val dupNameAddr = storeDao.findDuplicateByNameAndAddress(effectiveCompanyId, trimmedName, trimmedAddress)
        if (dupNameAddr != null && dupNameAddr.id != existingId) {
            return Result.failure(IllegalStateException("DUPLICATE_STORE"))
        }
        val now = System.currentTimeMillis()
        val storeId = existing?.id ?: UUID.randomUUID().toString()
        val entity = StoreEntity(
            id = storeId,
            name = trimmedName,
            address = trimmedAddress,
            lat = lat,
            lon = lon,
            providerId = cleanProvider,
            phone = phone.trim(),
            created = existing?.created ?: now,
            modified = now.coerceAtLeast(existing?.created ?: now),
            archived = existing?.archived ?: false,
            companyId = effectiveCompanyId,
            syncState = SyncState.PENDING,
            serverUpdatedAt = existing?.serverUpdatedAt,
            updatedByUid = actorUid,
            version = (existing?.version ?: 0) + 1,
            locationConfirmed = true,
            geocodeSource = geocodeSource.ifBlank { "manual" }
        )
        val validatedHours = (1..7).map { day ->
            val found = hours.find { it.day == day }
            val mode = found?.mode?.takeIf { it in HoursMode.ALL } ?: HoursMode.UNKNOWN
            val canonicalWindows = if (mode == HoursMode.OPEN) {
                val parsed = BusinessRules.parseAndValidateWindows(found?.windows ?: "")
                    ?: return Result.failure(IllegalArgumentException("Invalid receiving hours on day $day."))
                if (parsed.isEmpty()) return Result.failure(IllegalArgumentException("Open day $day requires at least one window."))
                BusinessRules.formatWindowsCanonical(parsed)
            } else {
                ""
            }
            StoreHoursEntity(storeId = storeId, day = day, mode = mode, windows = canonicalWindows)
        }

        db.withTransaction {
            storeDao.upsertStore(entity)
            storeDao.deleteHoursForStore(storeId)
            storeDao.upsertHours(validatedHours)
            if (effectiveCompanyId.isNotBlank()) {
                enqueueSyncInternal(effectiveCompanyId, "store", storeId, "upsert")
                recordAuditInternal(
                    effectiveCompanyId, actorUid, actorName, actorRole,
                    if (existing == null) "STORE_CREATED" else "STORE_UPDATED",
                    "store", storeId,
                    "${if (existing == null) "Created" else "Updated"} store '${entity.name}'"
                )
            }
        }
        return Result.success(entity)
    }

    suspend fun archiveStore(
        storeId: String,
        actorUid: String = "",
        actorName: String = "",
        actorRole: String = EmployeeRole.OWNER
    ): Result<Unit> {
        if (!RolePermissions.canManageStoresAndJobs(actorRole)) {
            return Result.failure(SecurityException("Only Owners and Managers can archive stores."))
        }
        val activeCount = jobDao.countActiveJobsForStore(storeId)
        if (activeCount > 0) {
            return Result.failure(IllegalStateException("ACTIVE_JOBS"))
        }
        val existing = storeDao.getStore(storeId)
        db.withTransaction {
            storeDao.setArchived(storeId, true, System.currentTimeMillis())
            if (existing != null && existing.companyId.isNotBlank()) {
                enqueueSyncInternal(existing.companyId, "store", storeId, "upsert")
                recordAuditInternal(
                    existing.companyId, actorUid, actorName, actorRole,
                    "STORE_ARCHIVED", "store", storeId,
                    "Archived store '${existing.name}'"
                )
            }
        }
        return Result.success(Unit)
    }

    suspend fun restoreStore(
        storeId: String,
        actorUid: String = "",
        actorName: String = "",
        actorRole: String = EmployeeRole.OWNER
    ) {
        if (!RolePermissions.canManageStoresAndJobs(actorRole)) return
        val existing = storeDao.getStore(storeId)
        db.withTransaction {
            storeDao.setArchived(storeId, false, System.currentTimeMillis())
            if (existing != null && existing.companyId.isNotBlank()) {
                enqueueSyncInternal(existing.companyId, "store", storeId, "upsert")
                recordAuditInternal(
                    existing.companyId, actorUid, actorName, actorRole,
                    "STORE_RESTORED", "store", storeId,
                    "Restored store '${existing.name}'"
                )
            }
        }
    }

    // --- Drafts & Jobs ---

    suspend fun saveDraft(draft: DraftEntity) {
        draftDao.upsert(draft.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun clearDraft(jobId: String) {
        draftDao.delete(jobId)
    }

    suspend fun clearAllDrafts() {
        draftDao.clearAll()
    }

    suspend fun saveJobAtomically(
        jobId: String,
        storeId: String,
        type: String,
        dateIso: String,
        timeIso: String?,
        reminderEnabled: Boolean,
        items: List<DraftItemInput>,
        assignedDriverUid: String? = null,
        assignedDriverName: String = "",
        notes: String = "",
        companyId: String = "",
        actorUid: String = "",
        actorName: String = "",
        actorRole: String = EmployeeRole.OWNER
    ): Result<JobEntity> {
        if (!RolePermissions.canManageStoresAndJobs(actorRole)) {
            return Result.failure(SecurityException("Only Owners and Managers can create or edit deliveries and pickups."))
        }
        val store = storeDao.getStore(storeId)
            ?: return Result.failure(IllegalArgumentException("Please select a saved store."))
        if (companyId.isNotBlank() && store.companyId.isNotBlank() && store.companyId != companyId) {
            return Result.failure(SecurityException("Cannot schedule work for a store from another company."))
        }
        if (store.archived) {
            val existing = jobDao.getJobWithItems(jobId)?.job
            if (existing == null || existing.status in JobStatus.ACTIVE) {
                return Result.failure(IllegalArgumentException("Cannot schedule active work for an archived store."))
            }
        }
        if (type !in JobType.ALL) {
            return Result.failure(IllegalArgumentException("Invalid job type."))
        }
        if (BusinessRules.parseIsoDate(dateIso) == null) {
            return Result.failure(IllegalArgumentException("Enter a valid date (YYYY-MM-DD)."))
        }
        val cleanTime = timeIso?.trim()?.ifEmpty { null }
        if (cleanTime != null && BusinessRules.parseIsoTime(cleanTime) == null) {
            return Result.failure(IllegalArgumentException("Enter a valid 24-hour time (HH:mm) or leave blank."))
        }
        if (items.isEmpty() || items.size > 50) {
            return Result.failure(IllegalArgumentException("Add between 1 and 50 product lines."))
        }

        val normalizedItems = mutableListOf<JobItemEntity>()
        val existingWithItems = jobDao.getJobWithItems(jobId)
        val existingById = existingWithItems?.items?.associateBy { it.id }.orEmpty()
        val existingJob = existingWithItems?.job
        val isPartial = existingJob?.issue == JobIssue.PARTIAL

        items.forEachIndexed { idx, row ->
            val name = row.name.trim()
            val unit = row.unit.trim().ifEmpty { "ea" }
            if (name.isEmpty()) {
                return Result.failure(IllegalArgumentException("Line ${idx + 1} needs a product name."))
            }
            val planned = BusinessRules.normalizeQuantity(row.planned, allowZero = false)
                ?: return Result.failure(IllegalArgumentException("Line ${idx + 1} needs a positive planned quantity."))
            val existingActual = if (isPartial) existingById[row.id]?.actual ?: "0" else null
            normalizedItems += JobItemEntity(
                id = row.id.ifBlank { UUID.randomUUID().toString() },
                jobId = jobId,
                position = idx,
                name = name,
                planned = planned,
                unit = unit,
                actual = existingActual
            )
        }

        val now = System.currentTimeMillis()
        val scheduleOrReminderChanged = existingJob == null ||
            existingJob.date != dateIso ||
            existingJob.time != cleanTime ||
            existingJob.reminderEnabled != reminderEnabled
        val newReminderVersion = if (scheduleOrReminderChanged) {
            UUID.randomUUID().toString().take(12)
        } else {
            existingJob!!.reminderVersion
        }
        val effectiveCompanyId = companyId.ifBlank { existingJob?.companyId ?: store.companyId }

        val entity = JobEntity(
            id = jobId,
            storeId = store.id,
            type = type,
            date = dateIso,
            time = cleanTime,
            status = existingJob?.status ?: JobStatus.PENDING,
            issue = existingJob?.issue,
            issueNote = existingJob?.issueNote ?: "",
            snapshotName = store.name,
            snapshotAddress = store.address,
            snapshotLat = store.lat,
            snapshotLon = store.lon,
            created = existingJob?.created ?: now,
            modified = now.coerceAtLeast(existingJob?.created ?: now),
            completed = existingJob?.completed,
            reminderVersion = newReminderVersion,
            reminderEnabled = reminderEnabled,
            companyId = effectiveCompanyId,
            assignedDriverUid = assignedDriverUid?.ifBlank { null } ?: existingJob?.assignedDriverUid,
            assignedDriverName = assignedDriverName.ifBlank { existingJob?.assignedDriverName.orEmpty() },
            notes = notes.trim(),
            syncState = SyncState.PENDING,
            serverUpdatedAt = existingJob?.serverUpdatedAt,
            updatedByUid = actorUid,
            version = (existingJob?.version ?: 0) + 1
        )

        db.withTransaction {
            jobDao.upsertJob(entity)
            jobDao.deleteItemsForJob(jobId)
            jobDao.upsertItems(normalizedItems)
            draftDao.delete(jobId)
            if (effectiveCompanyId.isNotBlank()) {
                enqueueSyncInternal(effectiveCompanyId, "job", jobId, "upsert")
                recordAuditInternal(
                    effectiveCompanyId, actorUid, actorName, actorRole,
                    if (existingJob == null) "JOB_CREATED" else "JOB_UPDATED",
                    "job", jobId,
                    "${if (existingJob == null) "Scheduled" else "Updated"} ${entity.type} at ${entity.snapshotName} ($dateIso)" +
                        if (entity.assignedDriverName.isNotBlank()) " — assigned to ${entity.assignedDriverName}" else ""
                )
            }
        }
        return Result.success(entity)
    }

    suspend fun assignDriverToJob(
        jobId: String,
        driverUid: String?,
        driverName: String,
        actorUid: String,
        actorName: String,
        actorRole: String
    ): Result<JobEntity> {
        if (!RolePermissions.canAssignDrivers(actorRole)) {
            return Result.failure(SecurityException("Only Owners and Managers can assign drivers."))
        }
        val current = jobDao.getJobWithItems(jobId)?.job
            ?: return Result.failure(IllegalArgumentException("Job not found"))
        val now = System.currentTimeMillis()
        val updated = current.copy(
            assignedDriverUid = driverUid?.ifBlank { null },
            assignedDriverName = if (driverUid.isNullOrBlank()) "" else driverName.trim(),
            modified = now.coerceAtLeast(current.created),
            syncState = SyncState.PENDING,
            updatedByUid = actorUid,
            version = current.version + 1
        )
        db.withTransaction {
            jobDao.upsertJob(updated)
            if (updated.companyId.isNotBlank()) {
                enqueueSyncInternal(updated.companyId, "job", jobId, "upsert")
                recordAuditInternal(
                    updated.companyId, actorUid, actorName, actorRole,
                    "JOB_ASSIGNED", "job", jobId,
                    if (updated.assignedDriverName.isNotBlank())
                        "Assigned ${updated.snapshotName} (${updated.date}) to ${updated.assignedDriverName}"
                    else
                        "Unassigned driver from ${updated.snapshotName} (${updated.date})"
                )
            }
        }
        return Result.success(updated)
    }

    suspend fun updateJobStatus(
        jobId: String,
        newStatus: String,
        actorUid: String = "",
        actorName: String = "",
        actorRole: String = EmployeeRole.OWNER
    ): Result<JobEntity> {
        if (!RolePermissions.canUpdateAssignedJobs(actorRole)) {
            return Result.failure(SecurityException("You do not have permission to update job status."))
        }
        val current = jobDao.getJobWithItems(jobId)
            ?: return Result.failure(IllegalArgumentException("Job not found"))
        if (actorRole == EmployeeRole.DRIVER &&
            !current.job.assignedDriverUid.isNullOrBlank() &&
            actorUid.isNotBlank() &&
            current.job.assignedDriverUid != actorUid
        ) {
            return Result.failure(SecurityException("Drivers can only update deliveries assigned to them."))
        }
        val store = storeDao.getStore(current.job.storeId)
        val now = System.currentTimeMillis()
        val reopening = newStatus in JobStatus.ACTIVE && current.job.status !in JobStatus.ACTIVE
        if (reopening && store?.archived == true) {
            return Result.failure(IllegalStateException("Restore the archived store before reopening this job."))
        }
        val updated = current.job.copy(
            status = newStatus,
            snapshotName = store?.name ?: current.job.snapshotName,
            snapshotAddress = store?.address ?: current.job.snapshotAddress,
            snapshotLat = store?.lat ?: current.job.snapshotLat,
            snapshotLon = store?.lon ?: current.job.snapshotLon,
            modified = now.coerceAtLeast(current.job.created),
            completed = if (newStatus == JobStatus.COMPLETED) now else null,
            reminderVersion = if (reopening) UUID.randomUUID().toString().take(12) else current.job.reminderVersion,
            syncState = SyncState.PENDING,
            updatedByUid = actorUid,
            version = current.job.version + 1
        )
        db.withTransaction {
            jobDao.upsertJob(updated)
            if (updated.companyId.isNotBlank()) {
                enqueueSyncInternal(updated.companyId, "job", jobId, "upsert")
                recordAuditInternal(
                    updated.companyId, actorUid, actorName, actorRole,
                    "JOB_STATUS_${newStatus.uppercase()}", "job", jobId,
                    "Marked ${updated.type} at ${updated.snapshotName} as $newStatus"
                )
            }
        }
        return Result.success(updated)
    }

    suspend fun rescheduleJob(
        jobId: String,
        newDate: String,
        newTime: String?,
        actorUid: String = "",
        actorName: String = "",
        actorRole: String = EmployeeRole.OWNER
    ): Result<JobEntity> {
        if (!RolePermissions.canManageStoresAndJobs(actorRole)) {
            return Result.failure(SecurityException("Only Owners and Managers can reschedule jobs."))
        }
        if (BusinessRules.parseIsoDate(newDate) == null) {
            return Result.failure(IllegalArgumentException("Invalid date."))
        }
        val cleanTime = newTime?.trim()?.ifEmpty { null }
        if (cleanTime != null && BusinessRules.parseIsoTime(cleanTime) == null) {
            return Result.failure(IllegalArgumentException("Invalid time."))
        }
        val current = jobDao.getJobWithItems(jobId)?.job
            ?: return Result.failure(IllegalArgumentException("Job not found"))
        val now = System.currentTimeMillis()
        val updated = current.copy(
            date = newDate,
            time = cleanTime,
            modified = now.coerceAtLeast(current.created),
            reminderVersion = UUID.randomUUID().toString().take(12),
            syncState = SyncState.PENDING,
            updatedByUid = actorUid,
            version = current.version + 1
        )
        db.withTransaction {
            jobDao.upsertJob(updated)
            if (updated.companyId.isNotBlank()) {
                enqueueSyncInternal(updated.companyId, "job", jobId, "upsert")
                recordAuditInternal(
                    updated.companyId, actorUid, actorName, actorRole,
                    "JOB_RESCHEDULED", "job", jobId,
                    "Rescheduled ${updated.snapshotName} to $newDate ${cleanTime ?: ""}".trim()
                )
            }
        }
        return Result.success(updated)
    }

    suspend fun setJobReminderEnabled(jobId: String, enabled: Boolean): Result<JobEntity> {
        val current = jobDao.getJobWithItems(jobId)?.job
            ?: return Result.failure(IllegalArgumentException("Job not found"))
        val now = System.currentTimeMillis()
        val updated = current.copy(
            reminderEnabled = enabled,
            modified = now.coerceAtLeast(current.created),
            reminderVersion = UUID.randomUUID().toString().take(12),
            syncState = SyncState.PENDING,
            version = current.version + 1
        )
        jobDao.upsertJob(updated)
        return Result.success(updated)
    }

    suspend fun reportIssue(
        jobId: String,
        issueType: String,
        note: String,
        keepOpen: Boolean,
        actualQuantitiesByItemId: Map<String, String>,
        actorUid: String = "",
        actorName: String = "",
        actorRole: String = EmployeeRole.OWNER
    ): Result<JobEntity> {
        if (!RolePermissions.canUpdateAssignedJobs(actorRole)) {
            return Result.failure(SecurityException("Permission denied."))
        }
        if (issueType !in JobIssue.ALL) {
            return Result.failure(IllegalArgumentException("Invalid issue type."))
        }
        val current = jobDao.getJobWithItems(jobId)
            ?: return Result.failure(IllegalArgumentException("Job not found"))

        val updatedItems = current.sortedItems.map { item ->
            if (issueType == JobIssue.PARTIAL) {
                val raw = actualQuantitiesByItemId[item.id] ?: "0"
                val norm = BusinessRules.normalizeQuantity(raw, allowZero = true)
                    ?: return Result.failure(IllegalArgumentException("Invalid actual quantity for ${item.name}."))
                item.copy(actual = norm)
            } else {
                item.copy(actual = null)
            }
        }
        val now = System.currentTimeMillis()
        val newStatus = if (keepOpen) {
            if (current.job.status in JobStatus.ACTIVE) current.job.status else JobStatus.PROGRESS
        } else {
            JobStatus.COMPLETED
        }
        val updatedJob = current.job.copy(
            status = newStatus,
            issue = issueType,
            issueNote = note.trim(),
            modified = now.coerceAtLeast(current.job.created),
            completed = if (newStatus == JobStatus.COMPLETED) now else null,
            syncState = SyncState.PENDING,
            updatedByUid = actorUid,
            version = current.job.version + 1
        )

        db.withTransaction {
            jobDao.upsertJob(updatedJob)
            jobDao.upsertItems(updatedItems)
            if (updatedJob.companyId.isNotBlank()) {
                enqueueSyncInternal(updatedJob.companyId, "job", jobId, "upsert")
                recordAuditInternal(
                    updatedJob.companyId, actorUid, actorName, actorRole,
                    "JOB_ISSUE_${issueType.uppercase()}", "job", jobId,
                    "Reported issue ($issueType) on ${updatedJob.snapshotName}: ${note.trim().take(80)}"
                )
            }
        }
        return Result.success(updatedJob)
    }

    // --- Invoices & Electronic Signatures (Immutable once signed) ---

    suspend fun createInvoice(
        companyId: String,
        jobId: String?,
        storeId: String,
        storeName: String,
        invoiceNumber: String,
        notes: String,
        pages: List<InvoicePageEntity>,
        actorUid: String,
        actorName: String,
        actorRole: String,
        supersedesInvoiceId: String? = null
    ): Result<InvoiceWithPages> {
        if (!RolePermissions.canUploadAndSignInvoices(actorRole)) {
            return Result.failure(SecurityException("Your role cannot upload invoices."))
        }
        val trimmedNumber = invoiceNumber.trim()
        if (trimmedNumber.isEmpty()) {
            return Result.failure(IllegalArgumentException("Invoice number is required."))
        }
        if (pages.isEmpty()) {
            return Result.failure(IllegalArgumentException("Capture or import at least one invoice page."))
        }
        val now = System.currentTimeMillis()
        val invoiceId = pages.first().invoiceId.ifBlank { UUID.randomUUID().toString() }
        val prior = supersedesInvoiceId?.let { invoiceDao.getInvoiceWithPages(it)?.invoice }
        val nextVersion = (prior?.version ?: 0) + 1
        val totalBytes = pages.sumOf { it.byteSize }
        val linkedJob = jobId?.ifBlank { null }?.let { jobDao.getJobWithItems(it)?.job }
        val effectiveDriverUid = linkedJob?.assignedDriverUid
            ?: prior?.assignedDriverUid
            ?: if (actorRole == EmployeeRole.DRIVER) actorUid.ifBlank { null } else null

        val invoice = InvoiceEntity(
            id = invoiceId,
            companyId = companyId,
            jobId = jobId?.ifBlank { null },
            storeId = storeId,
            storeName = storeName,
            invoiceNumber = trimmedNumber,
            notes = notes.trim(),
            status = InvoiceStatus.NOT_SIGNED,
            version = nextVersion,
            supersedesInvoiceId = prior?.id,
            createdByUid = actorUid,
            createdByName = actorName,
            createdAt = now,
            modifiedAt = now,
            signed = false,
            totalBytes = totalBytes,
            syncState = SyncState.PENDING,
            assignedDriverUid = effectiveDriverUid
        )
        val normalizedPages = pages.mapIndexed { idx, p ->
            p.copy(invoiceId = invoiceId, pageIndex = idx)
        }

        db.withTransaction {
            invoiceDao.upsertInvoice(invoice)
            invoiceDao.deletePagesForInvoice(invoiceId)
            invoiceDao.upsertPages(normalizedPages)
            if (companyId.isNotBlank()) {
                val sumBytes = invoiceDao.sumStorageBytes(companyId)
                companyDao.updateStorageUsed(companyId, sumBytes, now)
                enqueueSyncInternal(companyId, "invoice_file", invoiceId, "upload_file")
                enqueueSyncInternal(companyId, "invoice", invoiceId, "upsert")
                recordAuditInternal(
                    companyId, actorUid, actorName, actorRole,
                    if (prior != null) "INVOICE_CORRECTED_VERSION" else "INVOICE_UPLOADED",
                    "invoice", invoiceId,
                    "Saved invoice #$trimmedNumber (v$nextVersion, ${normalizedPages.size} page(s)) for $storeName"
                )
            }
        }
        return Result.success(InvoiceWithPages(invoice, normalizedPages))
    }

    /**
     * Records a customer electronic signature on an unsigned invoice.
     * Once signed, an invoice is immutable and cannot be silently modified or overwritten.
     */
    suspend fun signInvoice(
        invoiceId: String,
        recipientName: String,
        recipientTitle: String,
        acknowledgmentConfirmed: Boolean,
        signatureLocalPath: String,
        signaturePageIndex: Int,
        signaturePosX: Float,
        signaturePosY: Float,
        signedPdfLocalPath: String,
        signedPdfSha256: String,
        signedPdfBytes: Long,
        actorUid: String,
        actorName: String,
        actorRole: String
    ): Result<InvoiceEntity> {
        if (!RolePermissions.canUploadAndSignInvoices(actorRole)) {
            return Result.failure(SecurityException("Your role cannot collect signatures."))
        }
        val existing = invoiceDao.getInvoiceWithPages(invoiceId)?.invoice
            ?: return Result.failure(IllegalArgumentException("Invoice not found."))
        if (existing.signed) {
            return Result.failure(
                IllegalStateException("Signed invoices are immutable. Create a corrected version instead.")
            )
        }
        if (existing.status == InvoiceStatus.VOIDED) {
            return Result.failure(IllegalStateException("Voided invoices cannot be signed."))
        }
        val cleanRecipient = recipientName.trim()
        if (cleanRecipient.length < 2) {
            return Result.failure(IllegalArgumentException("Recipient's full name is required."))
        }
        if (!acknowledgmentConfirmed) {
            return Result.failure(IllegalArgumentException("Recipient must acknowledge receiving the delivery."))
        }

        val now = System.currentTimeMillis()
        val updated = existing.copy(
            status = if (existing.companyId.isNotBlank()) InvoiceStatus.PENDING_UPLOAD else InvoiceStatus.SIGNED,
            signed = true,
            recipientName = cleanRecipient,
            recipientTitle = recipientTitle.trim(),
            acknowledgmentConfirmed = true,
            signatureLocalPath = signatureLocalPath,
            signaturePageIndex = signaturePageIndex,
            signaturePosX = signaturePosX.coerceIn(0.05f, 0.95f),
            signaturePosY = signaturePosY.coerceIn(0.05f, 0.95f),
            signedLocalAt = now,
            signedPdfLocalPath = signedPdfLocalPath,
            signedPdfSha256 = signedPdfSha256,
            totalBytes = existing.totalBytes + signedPdfBytes,
            modifiedAt = now,
            syncState = SyncState.PENDING
        )

        db.withTransaction {
            invoiceDao.upsertInvoice(updated)
            if (updated.companyId.isNotBlank()) {
                val sumBytes = invoiceDao.sumStorageBytes(updated.companyId)
                companyDao.updateStorageUsed(updated.companyId, sumBytes, now)
                enqueueSyncInternal(updated.companyId, "invoice_file", invoiceId, "upload_file")
                enqueueSyncInternal(updated.companyId, "invoice", invoiceId, "upsert")
                recordAuditInternal(
                    updated.companyId, actorUid, actorName, actorRole,
                    "INVOICE_SIGNED", "invoice", invoiceId,
                    "Invoice #${updated.invoiceNumber} signed by $cleanRecipient (local capture; awaiting server timestamp)"
                )
            }
        }
        return Result.success(updated)
    }

    /**
     * Marks an invoice voided with a mandatory audit reason, preserving the original signed document.
     */
    suspend fun voidInvoice(
        invoiceId: String,
        reason: String,
        actorUid: String,
        actorName: String,
        actorRole: String
    ): Result<InvoiceEntity> {
        if (!RolePermissions.canVoidOrCorrectInvoices(actorRole)) {
            return Result.failure(SecurityException("Only Owners and Managers can void an invoice."))
        }
        val cleanReason = reason.trim()
        if (cleanReason.length < 3) {
            return Result.failure(IllegalArgumentException("Provide a reason for voiding this invoice."))
        }
        val existing = invoiceDao.getInvoiceWithPages(invoiceId)?.invoice
            ?: return Result.failure(IllegalArgumentException("Invoice not found."))
        val now = System.currentTimeMillis()
        val updated = existing.copy(
            status = InvoiceStatus.VOIDED,
            voidedReason = cleanReason,
            modifiedAt = now,
            syncState = SyncState.PENDING
        )
        db.withTransaction {
            invoiceDao.upsertInvoice(updated)
            if (updated.companyId.isNotBlank()) {
                enqueueSyncInternal(updated.companyId, "invoice", invoiceId, "upsert")
                recordAuditInternal(
                    updated.companyId, actorUid, actorName, actorRole,
                    "INVOICE_VOIDED", "invoice", invoiceId,
                    "Voided invoice #${updated.invoiceNumber} (v${updated.version}): $cleanReason"
                )
            }
        }
        return Result.success(updated)
    }

    /**
     * Creates a new unsigned version superseding an existing signed invoice so corrections preserve full history.
     */
    suspend fun createCorrectedInvoiceVersion(
        originalInvoiceId: String,
        correctionNote: String,
        actorUid: String,
        actorName: String,
        actorRole: String
    ): Result<InvoiceWithPages> {
        if (!RolePermissions.canVoidOrCorrectInvoices(actorRole)) {
            return Result.failure(SecurityException("Only Owners and Managers can create corrected invoice versions."))
        }
        val original = invoiceDao.getInvoiceWithPages(originalInvoiceId)
            ?: return Result.failure(IllegalArgumentException("Original invoice not found."))
        val newInvoiceId = UUID.randomUUID().toString()
        val clonedPages = original.sortedPages.mapIndexed { idx, p ->
            p.copy(
                id = UUID.randomUUID().toString(),
                invoiceId = newInvoiceId,
                pageIndex = idx
            )
        }
        val combinedNotes = buildString {
            if (original.invoice.notes.isNotBlank()) append(original.invoice.notes).append("\n")
            append("Correction of v${original.invoice.version}: ${correctionNote.trim()}")
        }
        return createInvoice(
            companyId = original.invoice.companyId,
            jobId = original.invoice.jobId,
            storeId = original.invoice.storeId,
            storeName = original.invoice.storeName,
            invoiceNumber = original.invoice.invoiceNumber,
            notes = combinedNotes,
            pages = clonedPages,
            actorUid = actorUid,
            actorName = actorName,
            actorRole = actorRole,
            supersedesInvoiceId = original.invoice.id
        )
    }

    // --- Audit & Sync Queue helpers ---

    private suspend fun recordAuditInternal(
        companyId: String,
        actorUid: String,
        actorName: String,
        actorRole: String,
        action: String,
        targetType: String,
        targetId: String,
        summary: String
    ) {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        auditLogDao.insert(
            AuditLogEntity(
                id = id,
                companyId = companyId,
                actorUid = actorUid,
                actorName = actorName.ifBlank { "User" },
                actorRole = actorRole,
                action = action,
                targetType = targetType,
                targetId = targetId,
                summary = summary,
                createdAt = now,
                syncState = SyncState.PENDING
            )
        )
        if (companyId.isNotBlank()) {
            enqueueSyncInternal(companyId, "audit", id, "upsert")
        }
    }

    suspend fun enqueueSyncInternal(
        companyId: String,
        entityType: String,
        entityId: String,
        operation: String,
        payloadJson: String = "{}"
    ) {
        if (companyId.isBlank()) return
        val existing = syncQueueDao.findExistingInCompany(companyId, entityType, entityId, operation)
        val now = System.currentTimeMillis()
        val entry = SyncQueueEntity(
            id = existing?.id ?: UUID.randomUUID().toString(),
            companyId = companyId,
            entityType = entityType,
            entityId = entityId,
            operation = operation,
            payloadJson = payloadJson,
            retryCount = 0,
            status = SyncState.PENDING,
            lastError = null,
            createdAt = existing?.createdAt ?: now,
            nextAttemptAt = now
        )
        syncQueueDao.upsert(entry)
    }

    suspend fun retryFailedSyncItems(companyId: String = "") {
        if (companyId.isBlank()) syncQueueDao.resetFailedForRetry()
        else syncQueueDao.resetFailedForCompany(companyId)
    }

    // --- Search Cache & Notification Receipts ---

    suspend fun getCachedSearch(key: String, maxAgeMs: Long): String? {
        val entry = searchCacheDao.get(key) ?: return null
        return if (System.currentTimeMillis() - entry.cachedAt <= maxAgeMs) entry.responseJson else null
    }

    suspend fun putCachedSearch(key: String, json: String) {
        val now = System.currentTimeMillis()
        searchCacheDao.upsert(SearchCacheEntity(key, json, now))
        searchCacheDao.pruneOlderThan(now - 7L * 24 * 60 * 60 * 1000)
    }

    suspend fun hasReceipt(key: String): Boolean = receiptDao.exists(key)

    suspend fun recordReceipt(key: String) {
        val now = System.currentTimeMillis()
        receiptDao.insert(ReceiptEntity(key, now))
        receiptDao.pruneOlderThan(now - 30L * 24 * 60 * 60 * 1000)
    }

    companion object {
        fun encodeDraftItems(items: List<DraftItemInput>): String {
            val arr = JSONArray()
            items.forEach { item ->
                arr.put(
                    JSONObject().apply {
                        put("id", item.id)
                        put("name", item.name)
                        put("planned", item.planned)
                        put("unit", item.unit)
                    }
                )
            }
            return arr.toString()
        }

        fun decodeDraftItems(json: String): List<DraftItemInput> {
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    DraftItemInput(
                        id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
                        name = obj.optString("name", ""),
                        planned = obj.optString("planned", ""),
                        unit = obj.optString("unit", "cs")
                    )
                }
            } catch (_: Exception) {
                listOf(DraftItemInput())
            }
        }
    }
}
