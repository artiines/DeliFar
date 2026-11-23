# DeliFar 2.1

Native Android multi-user delivery, pickup, and signed-invoice management app built with Kotlin, Jetpack Compose, Material 3, Room/SQLite (schema v7), WorkManager, MapLibre Native Android, and optional Firebase Cloud Sync.

- **Package**: `com.delifar.app`
- **Minimum SDK**: Android 8.0 (API 26)
- **Target / Compile SDK**: API 35
- **Source**: `app/src/main`
- **Artifact**: `output/DeliFar.apk` (copied from `app/build/outputs/apk/debug/app-debug.apk`)

## Build, Test & CI

### Local Build & Verification
```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk output/DeliFar.apk
```
Robolectric 4.14.1 Android SDK 34 runtime JAR (`org.robolectric:android-all-instrumented:14-robolectric-10818077-i7`) is resolved reproducibly through Gradle (`robolectricSdk` configuration + `syncRobolectricSdkJars` task) and run offline (`robolectric.offline=true`) so tests never fail on ad-hoc runtime Maven HTTP downloads.

### GitHub Actions CI & Signing Identity
`.github/workflows/android-build.yml` runs on pushes to `main`, pull requests, and `v*` tags:
1. Executes all unit & Robolectric tests (`:app:testDebugUnitTest`) and Android Lint (`:app:lintDebug`) without skipping or `continue-on-error`.
2. Restores the preserved project signing keystore from the **`DELIFAR_KEYSTORE_BASE64`** GitHub Actions secret (`debug.keystore` is gitignored and never committed).
3. On distributable builds (`push` to `main` and `v*` tags), the workflow **fails explicitly** if `DELIFAR_KEYSTORE_BASE64` is missing rather than silently signing with a mismatched ephemeral certificate that would break in-place Android upgrades.
4. Verifies the APK signature with `apksigner verify --verbose --print-certs`, generates `DeliFar.apk.sha256`, uploads the `DeliFar-signed-apk` artifact, and publishes a GitHub Release on `v*` tags.

## Core Features (DeliFar 2.1)

- **Multi-User & Role-Based Access Control**: Owner, Manager, Driver, and Accountant roles scoped strictly by `companyId` in Room, Firestore Rules, Cloud Storage Rules, and Cloud Functions.
- **Manual Store Creation & Pin Confirmation**: Custom store names are preserved verbatim. Addresses can be geocoded via Android `Geocoder` (first), pasted `lat, lon`, full Google Maps links, or optional configurable OSM Nominatim — then adjusted on an interactive **MapLibre + OpenFreeMap** map and explicitly confirmed before saving. No silent default Los Angeles coordinates are ever injected.
- **Legacy Pin Review Protection**: Room migration `6 -> 7` preserves all existing stores and flags any legacy store whose coordinates match the old downtown Los Angeles fallback (`34.0522, -118.2437`) or `(0.0, 0.0)` with `locationConfirmed = false`, surfacing a clear **Review Pin** badge and blocking external turn-by-turn navigation until confirmed.
- **Reliable Invoice Capture, Electronic Signatures & Sync**:
  - Multi-page scans, signature overlay, and flattened signed PDFs with SHA-256 checksums.
  - Strict pre-upload validation (`InvoiceSyncValidator`): blocks uploads on missing, unreadable, empty, >15 MiB, magic-byte-mismatched, or checksum-mismatched files.
  - Dependency ordering (`invoice_file` before `invoice`) ensures partially uploaded invoices are never marked `SYNCHRONIZED`.
  - Signed invoices are immutable; corrections create versioned superseding invoices (`v2`, `v3`, ...) while preserving history.
- **Encrypted Full Business Backups (`.delifarbak`)**: AES-256-GCM + PBKDF2-HMAC-SHA256 encrypted ZIP archives containing company operational settings, stores + hours, jobs + items, invoices + pages, original/edited photos, signature PNGs, and signed PDFs — with SHA-256 manifest verification, ZipSlip protection, cross-company guard, transactional rollback, and full backward compatibility with legacy v1 JSON backups. See [BACKUP_FORMAT.md](BACKUP_FORMAT.md).
- **Offline-First & Optional Firebase Cloud Sync**: Runs fully offline on Room/SQLite out of the box; adding `app/google-services.json` enables Firebase Auth, Firestore real-time sync, private Cloud Storage, App Check, Crashlytics, and Cloud Functions. See [FIREBASE_SETUP.md](FIREBASE_SETUP.md).

## Maps, Geocoding & Routing Policy

- **Interactive Map**: MapLibre Native Android (`11.8.0`) with **OpenFreeMap** vector styles (`liberty` / `positron`) — no paid API keys required.
- **Personal / Device Mode**: Android built-in `Geocoder` + manual map pin works directly on Android devices.
- **Commercial Fleet Mode**: Public OSM Nominatim and public OSRM demo servers are **not** intended for commercial fleet volume. Configure dedicated self-hosted or commercial HTTPS endpoints under **Settings -> Map, Geocoding & Routing Services** (HTTPS public hosts only; private/loopback URLs are rejected).
- **Routing Transparency**: When no OSRM routing endpoint is configured, DeliFar computes an **approximate straight-line nearest-neighbor stop order** and explicitly labels it as offline straight-line ordering (not street routing). External turn-by-turn navigation hands off validated coordinates to Google Maps (or another installed maps app) via Android `Intent`.

## Database & Migrations

Room schema version **7** with checked-in schema exports (`app/schemas`) and explicit non-destructive migrations `1->2->3->4->5->6->7` (destructive fallback is deliberately absent).
