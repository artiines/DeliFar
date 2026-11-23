# DeliFar 2.0 Security Specification

## 1. Core Data & Authorization Invariants
1. **Strict Multi-Tenant Isolation**: Every business record resides under `/companies/{companyId}/...` and `/companies/{companyId}/invoices/{invoiceId}/{fileName}` in Firebase Cloud Storage. Access requires an authenticated Firebase Auth user (`request.auth != null`) who has an `active == true` membership document at `/companies/{companyId}/employees/{request.auth.uid}`.
2. **Role-Based Access Control (RBAC)**:
   - `owner`: Full read/write to company settings, employees, invitations, stores, jobs, invoices, audit logs.
   - `manager`: Manage stores, jobs, driver assignments, invoices, void/correct invoices, read team & audit logs. Cannot alter company settings, invite employees, or deactivate/promote users.
   - `driver`: Read company stores, read/update only jobs where `assignedDriverUid == request.auth.uid` (and cannot reassign `assignedDriverUid` or delete jobs), upload unsigned invoices, and sign unsigned invoices. Cannot read other drivers' unassigned/other-assigned jobs or void invoices.
   - `accountant`: Read-only access to stores, jobs, and all company invoices & signed PDFs in Storage. Cannot mutate operational data or upload/sign/void invoices.
3. **Signed Invoice Immutability**: Once `resource.data.signed == true`, no client may modify `recipientName`, `recipientTitle`, `acknowledgmentConfirmed`, `signedLocalAt`, `signedPdfStoragePath`, `signedPdfSha256`, `signatureStoragePath`, `pages`, `invoiceNumber`, or revert `signed` to `false`. Only an `owner` or `manager` may transition a signed invoice's `status` to `"voided"` with a `voidedReason` (and create a new version document referencing `supersedesInvoiceId`).
4. **Trusted Server Operations**: Invitation redemption (`acceptEmployeeInvitation`) and employee deactivation/role changes (`updateEmployeeAccess`) run via Cloud Functions using the Admin SDK to validate invite codes across tenants, update membership atomically, and revoke refresh tokens when an employee is deactivated.
5. **Private File Storage & Size Limits**: Invoice photos, signature PNGs, and signed PDFs are stored privately under `companies/{companyId}/invoices/{invoiceId}/...` with a 15 MB per-file ceiling and strict MIME type allowlisting (`image/jpeg`, `image/png`, `application/pdf`). Signed PDFs cannot be overwritten once written.
6. **Append-Only Audit Logs**: Audit logs can only be created by active company members with `serverTimestamp == request.time`, and can never be updated or deleted by any client.

## 2. Adversarial Security Test Matrix (12 Cases)
1. **Unauthenticated Read/Write**: Anonymous caller attempts to read `/companies/compA/jobs/job1` or upload to Storage -> **DENIED**.
2. **Cross-Tenant Lateral Read**: Active `owner` of `compA` attempts to read `/companies/compB/invoices/inv1` -> **DENIED**.
3. **Deactivated Employee Access**: Former `owner` or `driver` with `active: false` attempts to read jobs or upload an invoice -> **DENIED**.
4. **Privilege Escalation (Driver -> Owner)**: `driver` attempts to update `/companies/compA/employees/{self}` setting `role: "owner"` -> **DENIED**.
5. **Driver Reassigning Job**: `driver` assigned to `job1` attempts to change `assignedDriverUid` to another driver or delete `job1` -> **DENIED**.
6. **Driver Reading Another Driver's Job**: `driverA` attempts to read or update `job2` where `assignedDriverUid == driverB` -> **DENIED**.
7. **Accountant Mutating Data**: `accountant` attempts to create a store, update a job status, or upload/sign an invoice -> **DENIED**.
8. **Silent Tampering of Signed Invoice**: `driver` or `owner` attempts to alter `recipientName`, `pages`, `signedPdfStoragePath`, or flip `signed: false` on an already-signed invoice -> **DENIED**.
9. **Deleting Signed Invoice / Signed PDF**: Any role attempts to delete `/companies/compA/invoices/signedInv1` or overwrite `signed_v1.pdf` in Storage -> **DENIED**.
10. **Audit Log Tampering**: `owner` attempts to update or delete an existing `/companies/compA/auditLogs/{logId}` entry -> **DENIED**.
11. **Oversized or Malicious File Upload**: `driver` uploads a 20 MB file or an `application/x-msdownload` executable to `companies/compA/invoices/inv1/evil.exe` -> **DENIED**.
12. **Manager Self-Creating Invitation / Modifying Company**: `manager` attempts to create an invitation or change `ownerUid` on `/companies/compA` -> **DENIED**.
