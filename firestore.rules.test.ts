/**
 * DeliFar 2.0 Firestore & Cloud Storage Security Rules Emulator Test Suite
 *
 * Run with Firebase Emulator Suite:
 *   firebase emulators:exec --only firestore,storage "npx ts-node firestore.rules.test.ts"
 */
import * as fs from "fs";
import * as path from "path";
import {
  initializeTestEnvironment,
  assertSucceeds,
  assertFails,
  RulesTestEnvironment,
} from "@firebase/rules-unit-testing";
import {
  doc,
  getDoc,
  setDoc,
  updateDoc,
  deleteDoc,
  collection,
  query,
  where,
  getDocs,
  serverTimestamp,
} from "firebase/firestore";
import { ref, uploadBytes, getBytes } from "firebase/storage";

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
    await setDoc(doc(db, `companies/${companyId}/stores/store1`), {
      id: "store1",
      companyId,
      name: "Ararat Fish Market",
      address: "1100 S Brand Blvd, Glendale, CA 91204",
      lat: 34.1329,
      lon: -118.2551,
      archived: false,
      version: 1,
    });
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
      assignedDriverUid: `${companyId}_drv1`,
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
    await setDoc(doc(db, `companies/${companyId}/invoices/inv_assigned_drv1`), {
      id: "inv_assigned_drv1",
      companyId,
      storeId: "store1",
      invoiceNumber: "INV-1002",
      status: "not_signed",
      version: 1,
      createdByUid: `${companyId}_mgr`,
      assignedDriverUid: `${companyId}_drv1`,
      signed: false,
      recipientName: "",
      recipientTitle: "",
      acknowledgmentConfirmed: false,
      pages: [],
    });

    const storage = ctx.storage();
    await uploadBytes(
      ref(storage, `companies/${companyId}/invoices/inv_signed/signed_v1.pdf`),
      new Uint8Array([0x25, 0x50, 0x44, 0x46]),
      { contentType: "application/pdf" }
    );
    await uploadBytes(
      ref(storage, `companies/${companyId}/invoices/inv_assigned_drv1/edit_p1.jpg`),
      new Uint8Array([0xff, 0xd8, 0xff, 0xe0]),
      { contentType: "image/jpeg" }
    );
  });
}

async function runTests() {
  testEnv = await initializeTestEnvironment({
    projectId: "delifar-rules-test",
    firestore: {
      host: "127.0.0.1",
      port: 8088,
      rules: fs.readFileSync(path.resolve(__dirname, "firestore.rules"), "utf8"),
    },
    storage: {
      host: "127.0.0.1",
      port: 9199,
      rules: fs.readFileSync(path.resolve(__dirname, "storage.rules"), "utf8"),
    },
  });

  await testEnv.clearFirestore();
  await testEnv.clearStorage();
  await seedCompany("compA", "compA_owner");
  await seedCompany("compB", "compB_owner");

  const anonDb = testEnv.unauthenticatedContext().firestore();
  const ownerADb = testEnv.authenticatedContext("compA_owner").firestore();
  const mgrADb = testEnv.authenticatedContext("compA_mgr").firestore();
  const drv1Db = testEnv.authenticatedContext("compA_drv1").firestore();
  const drv2Db = testEnv.authenticatedContext("compA_drv2").firestore();
  const acctDb = testEnv.authenticatedContext("compA_acct").firestore();
  const inactiveDb = testEnv.authenticatedContext("compA_inactive").firestore();

  // 1. Unauthenticated caller denied
  await assertFails(getDoc(doc(anonDb, "companies/compA/jobs/job_drv1")));

  // 2. Cross-tenant isolation: Owner of compA cannot read compB
  await assertFails(getDoc(doc(ownerADb, "companies/compB/invoices/inv_signed")));
  await assertSucceeds(getDoc(doc(ownerADb, "companies/compA/invoices/inv_signed")));

  // 3. Deactivated employee denied
  await assertFails(getDoc(doc(inactiveDb, "companies/compA/jobs/job_drv1")));

  // 4. Driver privilege escalation & Owner self-demotion denied
  await assertFails(
    updateDoc(doc(drv1Db, "companies/compA/employees/compA_drv1"), { role: "owner" })
  );
  await assertFails(
    updateDoc(doc(ownerADb, "companies/compA/employees/compA_owner"), { active: false })
  );

  // 5. Store coordinate validation: Null Island (0, 0) rejected; Glendale accepted
  await assertFails(
    setDoc(doc(mgrADb, "companies/compA/stores/store_null_island"), {
      id: "store_null_island",
      companyId: "compA",
      name: "Broken Store",
      address: "Unknown",
      lat: 0,
      lon: 0,
      archived: false,
      version: 1,
    })
  );
  await assertSucceeds(
    setDoc(doc(mgrADb, "companies/compA/stores/store_glendale"), {
      id: "store_glendale",
      companyId: "compA",
      name: "Custom Bakery Glendale",
      address: "200 N Brand Blvd, Glendale, CA 91203",
      lat: 34.1489,
      lon: -118.2551,
      archived: false,
      version: 1,
    })
  );
  // Drivers cannot create stores
  await assertFails(
    setDoc(doc(drv1Db, "companies/compA/stores/store_by_driver"), {
      id: "store_by_driver",
      companyId: "compA",
      name: "Unauthorized",
      address: "123 Main St",
      lat: 34.14,
      lon: -118.25,
      archived: false,
      version: 1,
    })
  );

  // 6. Driver job isolation: Driver 1 updates assigned job; Driver 2 denied reading it
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
  await assertFails(getDoc(doc(drv2Db, "companies/compA/jobs/job_drv1")));

  // 7. Driver invoice ownership & assignment isolation
  // Driver 1 can read own invoice & manager-created invoice assigned to Driver 1
  await assertSucceeds(getDoc(doc(drv1Db, "companies/compA/invoices/inv_signed")));
  await assertSucceeds(getDoc(doc(drv1Db, "companies/compA/invoices/inv_assigned_drv1")));
  // Driver 1's scoped list queries matching CloudSyncManager succeed
  await assertSucceeds(
    getDocs(
      query(
        collection(drv1Db, "companies/compA/invoices"),
        where("createdByUid", "==", "compA_drv1")
      )
    )
  );
  await assertSucceeds(
    getDocs(
      query(
        collection(drv1Db, "companies/compA/invoices"),
        where("assignedDriverUid", "==", "compA_drv1")
      )
    )
  );
  // Driver 1 CANNOT run an unscoped list of all company invoices
  await assertFails(getDocs(collection(drv1Db, "companies/compA/invoices")));
  // Driver 2 CANNOT read or sign Driver 1's invoices
  await assertFails(getDoc(doc(drv2Db, "companies/compA/invoices/inv_signed")));
  await assertFails(getDoc(doc(drv2Db, "companies/compA/invoices/inv_assigned_drv1")));
  await assertFails(
    updateDoc(doc(drv2Db, "companies/compA/invoices/inv_assigned_drv1"), {
      signed: true,
      recipientName: "Spoofed",
      acknowledgmentConfirmed: true,
    })
  );
  // Driver 1 CAN sign their assigned unsigned invoice when acknowledgmentConfirmed == true
  await assertSucceeds(
    updateDoc(doc(drv1Db, "companies/compA/invoices/inv_assigned_drv1"), {
      signed: true,
      status: "synchronized",
      recipientName: "Maria Gomez",
      acknowledgmentConfirmed: true,
      modifiedAt: Date.now(),
    })
  );

  // 8. Accountant can read signed invoices but cannot mutate jobs or stores
  await assertSucceeds(getDoc(doc(acctDb, "companies/compA/invoices/inv_signed")));
  await assertFails(
    updateDoc(doc(acctDb, "companies/compA/jobs/job_drv1"), { status: "completed" })
  );

  // 9. Signed invoice strict immutability: cannot alter recipientName, notes, revert signed=false, or un-void
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
  // Voiding with extra unapproved field rejected
  await assertFails(
    updateDoc(doc(ownerADb, "companies/compA/invoices/inv_signed"), {
      status: "voided",
      voidedReason: "Duplicate",
      notes: "sneaky edit",
    })
  );
  // Owner CAN void signed invoice with only approved fields
  await assertSucceeds(
    updateDoc(doc(ownerADb, "companies/compA/invoices/inv_signed"), {
      status: "voided",
      voidedReason: "Duplicate invoice photo",
      modifiedAt: Date.now(),
    })
  );
  // Once voided, CANNOT un-void back to synchronized
  await assertFails(
    updateDoc(doc(ownerADb, "companies/compA/invoices/inv_signed"), {
      status: "synchronized",
    })
  );

  // 10. Audit logs: append-only & actorUid cannot be forged
  await assertFails(deleteDoc(doc(ownerADb, "companies/compA/invoices/inv_signed")));
  await assertFails(
    setDoc(doc(drv1Db, "companies/compA/auditLogs/forged_log"), {
      id: "forged_log",
      companyId: "compA",
      actorUid: "compA_owner",
      action: "FORGED_ACTION",
      serverTimestamp: serverTimestamp(),
    })
  );
  await assertSucceeds(
    setDoc(doc(ownerADb, "companies/compA/auditLogs/log1"), {
      id: "log1",
      companyId: "compA",
      actorUid: "compA_owner",
      action: "INVOICE_VOIDED",
      serverTimestamp: serverTimestamp(),
    })
  );
  await assertFails(
    updateDoc(doc(ownerADb, "companies/compA/auditLogs/log1"), { summary: "edited" })
  );

  // 11. Cloud Storage rules: company isolation, driver invoice isolation, signed immutability
  const ownerAStorage = testEnv.authenticatedContext("compA_owner").storage();
  const acctAStorage = testEnv.authenticatedContext("compA_acct").storage();
  const drv1Storage = testEnv.authenticatedContext("compA_drv1").storage();
  const drv2Storage = testEnv.authenticatedContext("compA_drv2").storage();

  // Owner, Accountant, and Driver 1 can read Driver 1's signed PDF
  await assertSucceeds(
    getBytes(ref(ownerAStorage, "companies/compA/invoices/inv_signed/signed_v1.pdf"))
  );
  await assertSucceeds(
    getBytes(ref(acctAStorage, "companies/compA/invoices/inv_signed/signed_v1.pdf"))
  );
  await assertSucceeds(
    getBytes(ref(drv1Storage, "companies/compA/invoices/inv_signed/signed_v1.pdf"))
  );
  // Driver 2 CANNOT read Driver 1's signed PDF, and Owner A CANNOT read Company B's PDF
  await assertFails(
    getBytes(ref(drv2Storage, "companies/compA/invoices/inv_signed/signed_v1.pdf"))
  );
  await assertFails(
    getBytes(ref(ownerAStorage, "companies/compB/invoices/inv_signed/signed_v1.pdf"))
  );
  // Nobody can overwrite or add files under an already-signed invoice
  await assertFails(
    uploadBytes(
      ref(ownerAStorage, "companies/compA/invoices/inv_signed/tamper.jpg"),
      new Uint8Array([0xff, 0xd8, 0xff]),
      { contentType: "image/jpeg" }
    )
  );
  // Driver 1 CAN upload valid allowlisted files for a brand-new offline invoice before the Firestore doc is written
  await assertSucceeds(
    uploadBytes(
      ref(drv1Storage, "companies/compA/invoices/inv_new_offline/orig_p1.jpg"),
      new Uint8Array([0xff, 0xd8, 0xff]),
      { contentType: "image/jpeg" }
    )
  );
  await assertSucceeds(
    uploadBytes(
      ref(drv1Storage, "companies/compA/invoices/inv_new_offline/signature.png"),
      new Uint8Array([0x89, 0x50, 0x4e, 0x47]),
      { contentType: "image/png" }
    )
  );
  await assertSucceeds(
    uploadBytes(
      ref(drv1Storage, "companies/compA/invoices/inv_new_offline/signed_v1.pdf"),
      new Uint8Array([0x25, 0x50, 0x44, 0x46]),
      { contentType: "application/pdf" }
    )
  );
  // Strict filename allowlist rejects arbitrary/unexpected filenames even on new invoices
  await assertFails(
    uploadBytes(
      ref(drv1Storage, "companies/compA/invoices/inv_new_offline/random_file.jpg"),
      new Uint8Array([0xff, 0xd8, 0xff]),
      { contentType: "image/jpeg" }
    )
  );
  // Immutable files (orig_*, signature.png, signed_v*.pdf) CANNOT be overwritten once uploaded
  await assertFails(
    uploadBytes(
      ref(drv1Storage, "companies/compA/invoices/inv_new_offline/signed_v1.pdf"),
      new Uint8Array([0x25, 0x50, 0x44, 0x46, 0x2d]),
      { contentType: "application/pdf" }
    )
  );
  await assertFails(
    uploadBytes(
      ref(drv1Storage, "companies/compA/invoices/inv_new_offline/signature.png"),
      new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d]),
      { contentType: "image/png" }
    )
  );
  await assertFails(
    uploadBytes(
      ref(drv1Storage, "companies/compA/invoices/inv_new_offline/orig_p1.jpg"),
      new Uint8Array([0xff, 0xd8, 0xff, 0xe0]),
      { contentType: "image/jpeg" }
    )
  );
  // Mismatched content-type vs filename extension is rejected
  await assertFails(
    uploadBytes(
      ref(drv1Storage, "companies/compA/invoices/inv_new_offline/signed_v2.pdf"),
      new Uint8Array([0xff, 0xd8, 0xff]),
      { contentType: "image/jpeg" }
    )
  );
  // Editable page (edit_*) CAN be created and re-uploaded while the invoice is not yet signed
  await assertSucceeds(
    uploadBytes(
      ref(drv1Storage, "companies/compA/invoices/inv_new_offline/edit_p1.jpg"),
      new Uint8Array([0xff, 0xd8, 0xff]),
      { contentType: "image/jpeg" }
    )
  );
  await assertSucceeds(
    uploadBytes(
      ref(drv1Storage, "companies/compA/invoices/inv_new_offline/edit_p1.jpg"),
      new Uint8Array([0xff, 0xd8, 0xff, 0xe1]),
      { contentType: "image/jpeg" }
    )
  );
  // Accountant CANNOT upload invoice files
  await assertFails(
    uploadBytes(
      ref(acctAStorage, "companies/compA/invoices/inv_new_acct/orig_p1.jpg"),
      new Uint8Array([0xff, 0xd8, 0xff]),
      { contentType: "image/jpeg" }
    )
  );

  await testEnv.cleanup();
  console.log("All DeliFar 2.1 Firestore & Cloud Storage security rule tests passed!");
}

runTests().catch((err) => {
  console.error(err);
  process.exit(1);
});
