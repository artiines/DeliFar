/**
 * DeliFar 2.0 Firestore Security Rules Emulator Test Suite
 *
 * Run with Firebase Emulator Suite:
 *   firebase emulators:exec "npx ts-node firestore.rules.test.ts"
 */
import * as fs from "fs";
import * as path from "path";
import {
  initializeTestEnvironment,
  assertSucceeds,
  assertFails,
  RulesTestEnvironment,
} from "@firebase/rules-unit-testing";
import { doc, getDoc, setDoc, updateDoc, deleteDoc, serverTimestamp } from "firebase/firestore";

let testEnv: RulesTestEnvironment;

async function seedCompany(companyId: string, ownerUid: string) {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await setDoc(doc(db, `companies/${companyId}`), {
      id: companyId,
      name: `Company ${companyId}`,
      ownerUid,
      timezone: "America/Los_Angeles",
      defaultArea: "LA",
      created: Date.now(),
      modified: Date.now(),
    });
    const members = [
      { uid: ownerUid, role: "owner", active: true },
      { uid: `${companyId}_mgr`, role: "manager", active: true },
      { uid: `${companyId}_drv1`, role: "driver", active: true },
      { uid: `${companyId}_drv2`, role: "driver", active: true },
      { uid: `${companyId}_acct`, role: "accountant", active: true },
      { uid: `${companyId}_inactive`, role: "driver", active: false },
    ];
    for (const m of members) {
      await setDoc(doc(db, `companies/${companyId}/employees/${m.uid}`), {
        uid: m.uid,
        companyId,
        displayName: m.uid,
        email: `${m.uid}@example.com`,
        role: m.role,
        active: m.active,
        joinedAt: Date.now(),
      });
    }
    await setDoc(doc(db, `companies/${companyId}/jobs/job_drv1`), {
      id: "job_drv1",
      companyId,
      storeId: "store1",
      type: "delivery",
      date: "2026-05-10",
      status: "pending",
      assignedDriverUid: `${companyId}_drv1`,
      items: [{ id: "i1", position: 0, name: "Water", planned: "10", unit: "cs" }],
      created: Date.now(),
      modified: Date.now(),
      version: 1,
    });
    await setDoc(doc(db, `companies/${companyId}/invoices/inv_signed`), {
      id: "inv_signed",
      companyId,
      storeId: "store1",
      invoiceNumber: "INV-1001",
      status: "synchronized",
      version: 1,
      createdByUid: `${companyId}_drv1`,
      signed: true,
      recipientName: "Alex Rivera",
      recipientTitle: "Receiving Manager",
      acknowledgmentConfirmed: true,
      signedLocalAt: 1700000000000,
      signedPdfStoragePath: `companies/${companyId}/invoices/inv_signed/signed_v1.pdf`,
      signedPdfSha256: "abc123",
      signatureStoragePath: `companies/${companyId}/invoices/inv_signed/signature.png`,
      pages: [],
    });
  });
}

async function runTests() {
  testEnv = await initializeTestEnvironment({
    projectId: "delifar-rules-test",
    firestore: {
      rules: fs.readFileSync(path.resolve(__dirname, "firestore.rules"), "utf8"),
    },
  });

  await seedCompany("compA", "compA_owner");
  await seedCompany("compB", "compB_owner");

  // 1. Unauthenticated caller denied
  const anonDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(getDoc(doc(anonDb, "companies/compA/jobs/job_drv1")));

  // 2. Cross-tenant isolation: Owner of compA cannot read compB
  const ownerADb = testEnv.authenticatedContext("compA_owner").firestore();
  await assertFails(getDoc(doc(ownerADb, "companies/compB/invoices/inv_signed")));
  await assertSucceeds(getDoc(doc(ownerADb, "companies/compA/invoices/inv_signed")));

  // 3. Deactivated employee denied
  const inactiveDb = testEnv.authenticatedContext("compA_inactive").firestore();
  await assertFails(getDoc(doc(inactiveDb, "companies/compA/jobs/job_drv1")));

  // 4. Driver privilege escalation denied
  const drv1Db = testEnv.authenticatedContext("compA_drv1").firestore();
  await assertFails(
    updateDoc(doc(drv1Db, "companies/compA/employees/compA_drv1"), { role: "owner" })
  );

  // 5. Driver can update status on assigned job, cannot reassign driver
  await assertSucceeds(
    updateDoc(doc(drv1Db, "companies/compA/jobs/job_drv1"), {
      status: "progress",
      modified: Date.now(),
      version: 2,
    })
  );
  await assertFails(
    updateDoc(doc(drv1Db, "companies/compA/jobs/job_drv1"), {
      assignedDriverUid: "compA_drv2",
    })
  );

  // 6. Driver 2 cannot read Driver 1's assigned job
  const drv2Db = testEnv.authenticatedContext("compA_drv2").firestore();
  await assertFails(getDoc(doc(drv2Db, "companies/compA/jobs/job_drv1")));

  // 7. Accountant can read signed invoices but cannot mutate jobs or stores
  const acctDb = testEnv.authenticatedContext("compA_acct").firestore();
  await assertSucceeds(getDoc(doc(acctDb, "companies/compA/invoices/inv_signed")));
  await assertFails(
    updateDoc(doc(acctDb, "companies/compA/jobs/job_drv1"), { status: "completed" })
  );

  // 8. Signed invoice immutability: cannot alter recipientName or revert signed=false
  await assertFails(
    updateDoc(doc(ownerADb, "companies/compA/invoices/inv_signed"), {
      recipientName: "Tampered Name",
    })
  );
  await assertFails(
    updateDoc(doc(ownerADb, "companies/compA/invoices/inv_signed"), {
      signed: false,
    })
  );

  // 9. Owner CAN void a signed invoice with reason without modifying immutable fields
  await assertSucceeds(
    updateDoc(doc(ownerADb, "companies/compA/invoices/inv_signed"), {
      status: "voided",
      voidedReason: "Duplicate invoice photo",
    })
  );

  // 10. Deleting invoices or updating audit logs denied
  await assertFails(deleteDoc(doc(ownerADb, "companies/compA/invoices/inv_signed")));
  await assertSucceeds(
    setDoc(doc(ownerADb, "companies/compA/auditLogs/log1"), {
      id: "log1",
      companyId: "compA",
      serverTimestamp: serverTimestamp(),
    })
  );
  await assertFails(
    updateDoc(doc(ownerADb, "companies/compA/auditLogs/log1"), { summary: "edited" })
  );

  await testEnv.cleanup();
  console.log("All DeliFar 2.0 Firestore security rule tests passed!");
}

runTests().catch((err) => {
  console.error(err);
  process.exit(1);
});
