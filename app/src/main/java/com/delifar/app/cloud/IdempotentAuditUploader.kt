package com.delifar.app.cloud

import com.delifar.app.data.AuditLogEntity
import com.google.firebase.firestore.FirebaseFirestoreException

/**
 * Snapshot representation of a remote `/companies/{companyId}/auditLogs/{logId}` document
 * used to verify identity and immutable fields on idempotent audit retries.
 */
data class RemoteAuditDocument(
    val documentId: String,
    val id: String,
    val companyId: String,
    val actorUid: String,
    val actorName: String,
    val actorRole: String,
    val action: String,
    val targetType: String,
    val targetId: String,
    val summary: String,
    val createdAt: Long?,
    val serverTimestampMillis: Long?
)

interface AuditRemoteGateway {
    /**
     * Creates a brand-new append-only audit document without merge/update semantics.
     */
    suspend fun createAudit(companyId: String, logId: String, payload: Map<String, Any?>)

    /**
     * Reads the authoritative server copy of `/companies/{companyId}/auditLogs/{logId}` if it exists.
     */
    suspend fun fetchAuditFromServer(companyId: String, logId: String): RemoteAuditDocument?
}

enum class AuditUploadResolution {
    CREATED,
    ALREADY_EXISTS_VERIFIED
}

data class AuditUploadResult(
    val resolution: AuditUploadResolution,
    val serverTimestampMillis: Long
)

/**
 * Idempotent uploader for append-only audit records.
 *
 * Firestore security rules strictly forbid `update` and `delete` on `/auditLogs/{logId}`.
 * When a retry occurs after a network interruption, dropped acknowledgment, or partial success,
 * attempting to create an already-existing audit document returns `PERMISSION_DENIED` (because
 * Firestore evaluates `allow update` on an existing document).
 *
 * This uploader:
 *  1. Uses create-only writes (never `SetOptions.merge()`).
 *  2. On retries (or when `create` returns `PERMISSION_DENIED` / `ALREADY_EXISTS`), reads the
 *     authoritative server document and verifies its identity and every immutable content field.
 *  3. Treats the retry as success ONLY when the remote document exists and matches every
 *     immutable field; rejects tampered/mismatched records and rethrows genuine create denials.
 */
object IdempotentAuditUploader {

    fun buildAuditPayload(
        entry: AuditLogEntity,
        actorUidToSend: String,
        serverTimestampValue: Any
    ): Map<String, Any?> = mapOf(
        "id" to entry.id,
        "companyId" to entry.companyId,
        "actorUid" to actorUidToSend,
        "actorName" to entry.actorName,
        "actorRole" to entry.actorRole,
        "action" to entry.action,
        "targetType" to entry.targetType,
        "targetId" to entry.targetId,
        "summary" to entry.summary,
        "createdAt" to entry.createdAt,
        "serverTimestamp" to serverTimestampValue
    )

    fun matchesImmutableAudit(
        entry: AuditLogEntity,
        expectedActorUid: String,
        remote: RemoteAuditDocument
    ): Boolean {
        val actorMatches = remote.actorUid == expectedActorUid ||
            (entry.actorUid.isNotBlank() && remote.actorUid == entry.actorUid)
        return remote.documentId == entry.id &&
            remote.id == entry.id &&
            remote.companyId == entry.companyId &&
            actorMatches &&
            remote.actorName == entry.actorName &&
            remote.actorRole == entry.actorRole &&
            remote.action == entry.action &&
            remote.targetType == entry.targetType &&
            remote.targetId == entry.targetId &&
            remote.summary == entry.summary &&
            remote.createdAt == entry.createdAt
    }

    fun isPermissionDeniedOrAlreadyExists(error: Throwable): Boolean {
        val codeName = if (error is FirebaseFirestoreException) error.code.name else null
        if (codeName == "PERMISSION_DENIED" || codeName == "ALREADY_EXISTS") return true
        val msg = error.message.orEmpty()
        return msg.contains("PERMISSION_DENIED", ignoreCase = true) ||
            msg.contains("ALREADY_EXISTS", ignoreCase = true)
    }

    suspend fun uploadIdempotently(
        entry: AuditLogEntity,
        actorUidToSend: String,
        preferVerifyFirst: Boolean,
        gateway: AuditRemoteGateway,
        serverTimestampValue: Any,
        nowMillis: () -> Long = { System.currentTimeMillis() }
    ): AuditUploadResult {
        if (preferVerifyFirst) {
            val existing = runCatching { gateway.fetchAuditFromServer(entry.companyId, entry.id) }.getOrNull()
            if (existing != null) {
                if (matchesImmutableAudit(entry, actorUidToSend, existing)) {
                    return AuditUploadResult(
                        resolution = AuditUploadResolution.ALREADY_EXISTS_VERIFIED,
                        serverTimestampMillis = existing.serverTimestampMillis ?: nowMillis()
                    )
                }
                throw IllegalStateException(
                    "Audit log '${entry.id}' already exists on server with mismatched immutable contents."
                )
            }
        }

        val payload = buildAuditPayload(entry, actorUidToSend, serverTimestampValue)
        return try {
            gateway.createAudit(entry.companyId, entry.id, payload)
            AuditUploadResult(
                resolution = AuditUploadResolution.CREATED,
                serverTimestampMillis = nowMillis()
            )
        } catch (createErr: Exception) {
            if (isPermissionDeniedOrAlreadyExists(createErr)) {
                val existing = runCatching {
                    gateway.fetchAuditFromServer(entry.companyId, entry.id)
                }.getOrNull()
                if (existing != null) {
                    if (matchesImmutableAudit(entry, actorUidToSend, existing)) {
                        return AuditUploadResult(
                            resolution = AuditUploadResolution.ALREADY_EXISTS_VERIFIED,
                            serverTimestampMillis = existing.serverTimestampMillis ?: nowMillis()
                        )
                    }
                    throw IllegalStateException(
                        "Audit log '${entry.id}' already exists on server with mismatched immutable contents."
                    )
                }
            }
            throw createErr
        }
    }
}
