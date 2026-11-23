# DeliFar Business Backup Formats (v2 Encrypted Archive & Legacy v1 JSON)

DeliFar 2.1 supports two backup formats:
1. **Format v2 — Encrypted Full Business Archive (`.delifarbak`)** *(Recommended)*: Protects all operational business records AND binary invoice attachments (original photos, edited scans, signature PNGs, signed PDFs) with authenticated encryption.
2. **Format v1 — Legacy Plaintext JSON (`.json`)** *(Backward-compatible)*: Exports/imports stores, receiving hours, jobs, items, and 6 basic preferences without binary invoice attachments.

---

## Format v2: Encrypted Business Archive (`.delifarbak`)

### 1. Binary Envelope Layout
| Offset | Length | Description |
|---|---|---|
| `0..7` | 8 bytes | ASCII magic header `DLFBK002` (also authenticated as AES-GCM AAD) |
| `8..23` | 16 bytes | Cryptographically random PBKDF2 salt |
| `24..35` | 12 bytes | Cryptographically random AES-GCM IV |
| `36..N` | Variable | AES-256-GCM ciphertext (128-bit authentication tag) wrapping a standard ZIP archive |

- **Key Derivation**: `PBKDF2WithHmacSHA256`, 120,000 iterations, 256-bit AES key, minimum passphrase length 6 characters.
- **Tamper & Passphrase Detection**: Any modified byte or wrong passphrase fails AES-GCM authentication immediately before extraction.

### 2. ZIP Archive Contents
Only three path patterns are allowed inside the decrypted ZIP (ZipSlip path traversal, `..`, leading `/`, `\`, directory entries, duplicate entries, and uncompressed sizes over 512 MiB are rejected):
- `manifest.json`:
  - `format`: `"DeliFarArchive"`
  - `version`: `2`
  - `dbSchemaVersion`: `7` (must be `1..7`)
  - `exported`: ISO-8601 UTC timestamp
  - `companyId`, `companyName`
  - `dataSha256`: SHA-256 hex digest of `data.json`
  - `files`: map of `"files/<invoiceId>/<safeFileName>" -> "<sha256>"`
- `data.json`:
  - `company`: operational settings (`id`, `name`, `timezone`, `defaultArea`, storage metrics, timestamps)
  - `stores`: store records (`locationConfirmed`, `geocodeSource`, `version`, etc.) + `hours`
  - `jobs`: delivery/pickup jobs (`assignedDriverUid`, `assignedDriverName`, `notes`, `version`, snapshots) + `items`
  - `invoices`: invoice metadata (`version`, `supersedesInvoiceId`, `voidedReason`, signature metadata, `signedPdfSha256`) + `pages` + relative archive paths for attachments
  - `preferences`: safe operational preferences only (UI language, default area, reminders, notification toggles, map/geocoding URLs)
- `files/<invoiceId>/<safeFileName>`: binary payloads for original invoice photos, edited scans, signature PNGs, and signed PDFs.

### 3. Excluded Secrets (Never Exported)
- Firebase Auth tokens, passwords, or user email/UID session credentials
- `fcmToken` values
- Android signing keystores (`debug.keystore`) or API secrets
- Transient drafts, notification receipts, and geocoding search caches

### 4. Integrity, Cross-Company & Transactional Restore Guarantees
- Every file in `manifest.json` is verified against its SHA-256 digest before restore.
- Every attachment referenced by `data.json` (pages, signatures, signed PDFs) must exist in the verified archive; missing attachments abort the import.
- Relational integrity (jobs -> stores, invoices -> stores/jobs, unique IDs, unique company-scoped `providerId`) is verified before writing.
- **Cross-Company Protection**: If the device already has an active company that differs from the archive's `companyId`, restore is blocked unless explicitly confirmed by the user (`allowCrossCompanyRestore = true`). Clean devices restore the company automatically.
- **Transactional Rollback & Non-Destructive Merge**: Attachments are staged in a temporary directory and all Room upserts run inside a single `db.withTransaction` block. Any error or interruption rolls back both Room writes and staged files. Existing records absent from the archive are never deleted.

---

## Format v1: Legacy Plaintext JSON (`.json`, Backward-Compatible)

UTF-8 JSON, maximum 50 MiB, with top-level keys `format: "DeliFar"`, `version: 1`, `exported`, `stores`, `jobs`, and `preferences` (`language`, `area`, `daily`, `summaryMinute`, `jobReminders`, `advance`).

- Validates exact key sets, types, timestamps (`modified >= created`), weekday receiving windows, BigDecimal quantity strings, contiguous item positions, and referential integrity before preview.
- Non-destructive merge inside a single Room transaction; conflicting aggregates are preserved unless "Replace conflicts" is checked.
- Legacy JSON backups are **not encrypted** and do **not** include invoice records or binary attachments; prefer `.delifarbak` for full business disaster recovery.
