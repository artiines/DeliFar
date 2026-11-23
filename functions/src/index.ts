import * as admin from "firebase-admin";
import { onCall, HttpsError } from "firebase-functions/v2/https";
import { onDocumentWritten } from "firebase-functions/v2/firestore";
import { FieldValue } from "firebase-admin/firestore";

admin.initializeApp();
const db = admin.firestore();

const VALID_ROLES = new Set(["owner", "manager", "driver", "accountant"]);

async function sendFcmToUsers(
  companyId: string,
  targetUids: string[],
  eventType: string,
  title: string,
  body: string,
  extraData: Record<string, string> = {}
): Promise<void> {
  if (targetUids.length === 0) return;
  const tokens: string[] = [];
  for (const uid of new Set(targetUids)) {
    const empSnap = await db.doc(`companies/${companyId}/employees/${uid}`).get();
    const emp = empSnap.data();
    if (emp && emp.active === true && typeof emp.fcmToken === "string" && emp.fcmToken.length > 10) {
      tokens.push(emp.fcmToken);
    }
  }
  if (tokens.length === 0) return;

  await admin.messaging().sendEachForMulticast({
    tokens,
    notification: { title, body },
    data: {
      eventType,
      title,
      body,
      companyId,
      ...extraData,
    },
    android: {
      priority: "high",
      notification: {
        channelId: "delifar_cloud_alerts",
      },
    },
  });
}

async function getActiveUidsByRoles(companyId: string, roles: string[]): Promise<string[]> {
  const snap = await db
    .collection(`companies/${companyId}/employees`)
    .where("active", "==", true)
    .where("role", "in", roles)
    .get();
  return snap.docs.map((d) => d.id);
}

/**
 * Trusted callable: Redeems a pending company invitation code atomically,
 * creates the employee record, sets custom auth claims, and notifies company owners.
 */
export const acceptEmployeeInvitation = onCall({ enforceAppCheck: true }, async (request) => {
  const auth = request.auth;
  if (!auth) {
    throw new HttpsError("unauthenticated", "Sign in before redeeming an invitation.");
  }

  const rawCode = typeof request.data?.inviteCode === "string" ? request.data.inviteCode.trim().toUpperCase() : "";
  if (!rawCode || rawCode.length < 6 || rawCode.length > 16) {
    throw new HttpsError("invalid-argument", "Invalid invitation code format.");
  }

  const inviteQuery = await db
    .collectionGroup("invitations")
    .where("inviteCode", "==", rawCode)
    .where("status", "==", "pending")
    .limit(1)
    .get();

  if (inviteQuery.empty) {
    throw new HttpsError("not-found", "Invitation code not found or already redeemed.");
  }

  const inviteDoc = inviteQuery.docs[0];
  const invite = inviteDoc.data();
  const nowMs = Date.now();

  if (typeof invite.expiresAt === "number" && invite.expiresAt < nowMs) {
    await inviteDoc.ref.update({ status: "expired" });
    throw new HttpsError("failed-precondition", "This invitation has expired.");
  }

  const callerEmail = (auth.token.email ?? "").toLowerCase();
  const invitedEmail = typeof invite.email === "string" ? invite.email.toLowerCase() : "";
  if (invitedEmail && callerEmail && invitedEmail !== callerEmail) {
    throw new HttpsError("permission-denied", "This invitation was issued for a different email address.");
  }

  const companyId = invite.companyId as string;
  const role = VALID_ROLES.has(invite.role) && invite.role !== "owner" ? (invite.role as string) : "driver";
  const displayName = (auth.token.name as string | undefined) || callerEmail.split("@")[0] || "Team Member";

  await db.runTransaction(async (tx) => {
    const freshInvite = await tx.get(inviteDoc.ref);
    if (!freshInvite.exists || freshInvite.data()?.status !== "pending") {
      throw new HttpsError("failed-precondition", "Invitation is no longer valid.");
    }
    tx.update(inviteDoc.ref, {
      status: "accepted",
      acceptedByUid: auth.uid,
      acceptedAt: FieldValue.serverTimestamp(),
    });
    tx.set(
      db.doc(`companies/${companyId}/employees/${auth.uid}`),
      {
        uid: auth.uid,
        companyId,
        displayName,
        email: callerEmail || invitedEmail,
        phone: "",
        role,
        active: true,
        fcmToken: "",
        invitedByUid: invite.createdByUid ?? "",
        joinedAt: nowMs,
        lastActiveAt: nowMs,
        serverUpdatedAt: FieldValue.serverTimestamp(),
      },
      { merge: true }
    );
    const auditRef = db.collection(`companies/${companyId}/auditLogs`).doc();
    tx.set(auditRef, {
      id: auditRef.id,
      companyId,
      actorUid: auth.uid,
      actorName: displayName,
      actorRole: role,
      action: "INVITE_ACCEPTED",
      targetType: "employee",
      targetId: auth.uid,
      summary: `${displayName} joined as ${role}`,
      createdAt: nowMs,
      serverTimestamp: FieldValue.serverTimestamp(),
    });
  });

  await admin.auth().setCustomUserClaims(auth.uid, { companyId, role, active: true });

  const owners = await getActiveUidsByRoles(companyId, ["owner"]);
  await sendFcmToUsers(
    companyId,
    owners,
    "employee_invite",
    "Employee Joined",
    `${displayName} accepted their ${role} invitation.`
  );

  return { companyId, role };
});

/**
 * Trusted callable: Allows an active company Owner to change an employee's role
 * or deactivate/reactivate their account, immediately revoking refresh tokens on deactivation.
 */
export const updateEmployeeAccess = onCall({ enforceAppCheck: true }, async (request) => {
  const auth = request.auth;
  if (!auth) {
    throw new HttpsError("unauthenticated", "Authentication required.");
  }

  const companyId = typeof request.data?.companyId === "string" ? request.data.companyId.trim() : "";
  const targetUid = typeof request.data?.targetUid === "string" ? request.data.targetUid.trim() : "";
  const newRole = typeof request.data?.role === "string" ? request.data.role.trim() : undefined;
  const active = typeof request.data?.active === "boolean" ? request.data.active : undefined;

  if (!companyId || !targetUid) {
    throw new HttpsError("invalid-argument", "companyId and targetUid are required.");
  }

  const callerSnap = await db.doc(`companies/${companyId}/employees/${auth.uid}`).get();
  if (!callerSnap.exists || callerSnap.data()?.active !== true || callerSnap.data()?.role !== "owner") {
    throw new HttpsError("permission-denied", "Only an active company Owner can manage employee access.");
  }

  if (targetUid === auth.uid && active === false) {
    throw new HttpsError("failed-precondition", "Owner cannot deactivate their own account.");
  }

  const targetRef = db.doc(`companies/${companyId}/employees/${targetUid}`);
  const targetSnap = await targetRef.get();
  if (!targetSnap.exists) {
    throw new HttpsError("not-found", "Employee not found in company.");
  }

  const updates: Record<string, unknown> = {
    serverUpdatedAt: FieldValue.serverTimestamp(),
  };
  let resolvedRole = targetSnap.data()?.role as string;
  let resolvedActive = targetSnap.data()?.active as boolean;

  if (newRole !== undefined) {
    if (!VALID_ROLES.has(newRole)) {
      throw new HttpsError("invalid-argument", "Unsupported employee role.");
    }
    updates.role = newRole;
    resolvedRole = newRole;
  }
  if (active !== undefined) {
    updates.active = active;
    resolvedActive = active;
  }

  await targetRef.update(updates);
  await admin.auth().setCustomUserClaims(targetUid, {
    companyId,
    role: resolvedRole,
    active: resolvedActive,
  });

  // Immediately revoke Firebase Auth refresh tokens on deactivation
  if (active === false) {
    await admin.auth().revokeRefreshTokens(targetUid);
  }

  const auditRef = db.collection(`companies/${companyId}/auditLogs`).doc();
  await auditRef.set({
    id: auditRef.id,
    companyId,
    actorUid: auth.uid,
    actorName: callerSnap.data()?.displayName ?? "Owner",
    actorRole: "owner",
    action: active === false ? "EMPLOYEE_DEACTIVATED" : "EMPLOYEE_UPDATED",
    targetType: "employee",
    targetId: targetUid,
    summary: `Updated ${targetSnap.data()?.displayName ?? targetUid}: role=${resolvedRole}, active=${resolvedActive}`,
    createdAt: Date.now(),
    serverTimestamp: FieldValue.serverTimestamp(),
  });

  return { ok: true, role: resolvedRole, active: resolvedActive };
});

/**
 * Firestore trigger: Dispatches push notifications for new assignments,
 * changed assignments, completed deliveries, and missing signature alerts.
 */
export const onJobWritten = onDocumentWritten("companies/{companyId}/jobs/{jobId}", async (event) => {
  const companyId = event.params.companyId;
  const jobId = event.params.jobId;
  const before = event.data?.before.data();
  const after = event.data?.after.data();
  if (!after) return;

  const storeName = (after.snapshotName as string) || "Store";
  const dateStr = (after.date as string) || "";
  const prevDriver = (before?.assignedDriverUid as string | null) ?? null;
  const nextDriver = (after.assignedDriverUid as string | null) ?? null;

  // 1. New or changed driver assignment
  if (nextDriver && nextDriver !== prevDriver) {
    const isReassigned = prevDriver !== null;
    await sendFcmToUsers(
      companyId,
      [nextDriver],
      isReassigned ? "changed_assignment" : "new_assignment",
      isReassigned ? "Updated Delivery Assignment" : "New Delivery Assignment",
      `${storeName} • ${dateStr}`,
      { jobId }
    );
  }

  // 2. Delivery completed -> notify owners/managers & check for missing signature
  if (before?.status !== "completed" && after.status === "completed") {
    const managers = await getActiveUidsByRoles(companyId, ["owner", "manager"]);
    await sendFcmToUsers(
      companyId,
      managers,
      "completed_delivery",
      "Delivery Completed",
      `${storeName} completed by ${after.assignedDriverName || "team member"}.`,
      { jobId }
    );

    const invSnap = await db
      .collection(`companies/${companyId}/invoices`)
      .where("jobId", "==", jobId)
      .where("signed", "==", true)
      .limit(1)
      .get();

    if (invSnap.empty) {
      await sendFcmToUsers(
        companyId,
        managers,
        "missing_signature",
        "Missing Signed Invoice",
        `${storeName} was completed without a signed invoice.`,
        { jobId }
      );
    }
  }
});

/**
 * Firestore trigger: Records authoritative server-confirmed signing timestamp,
 * updates company storage accounting, and notifies Owners, Managers, & Accountants.
 */
export const onInvoiceSigned = onDocumentWritten("companies/{companyId}/invoices/{invoiceId}", async (event) => {
  const companyId = event.params.companyId;
  const invoiceId = event.params.invoiceId;
  const before = event.data?.before.data();
  const after = event.data?.after.data();
  if (!after) return;

  const justSigned = before?.signed !== true && after.signed === true;
  if (!justSigned) return;

  // Stamp authoritative server confirmation time if not already stamped
  if (!after.signedServerAt) {
    await event.data!.after.ref.update({
      signedServerAt: FieldValue.serverTimestamp(),
      status: "synchronized",
    });
  }

  // Track company storage bytes incrementally
  const addedBytes = typeof after.totalBytes === "number" && after.totalBytes > 0 ? after.totalBytes : 0;
  if (addedBytes > 0) {
    await db.doc(`companies/${companyId}`).set(
      {
        storageUsedBytes: FieldValue.increment(addedBytes),
        serverUpdatedAt: FieldValue.serverTimestamp(),
      },
      { merge: true }
    );
  }

  const recipients = await getActiveUidsByRoles(companyId, ["owner", "manager", "accountant"]);
  await sendFcmToUsers(
    companyId,
    recipients,
    "signed_invoice",
    "Signed Invoice Received",
    `Invoice #${after.invoiceNumber || invoiceId} (${after.storeName || "Store"}) signed by ${after.recipientName || "recipient"}.`,
    { invoiceId }
  );
});
