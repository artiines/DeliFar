# DeliFar 2.1 — Firebase, Security & Mapping Setup Guide

DeliFar 2.1 runs fully offline with Room/SQLite out of the box and automatically activates real-time cloud sync, multi-user authentication, private invoice storage, App Check, and push notifications when your Firebase configuration is attached.

## 1. Connect Your Firebase Project
1. Open the [Firebase Console](https://console.firebase.google.com/) and create or select your project.
2. Add an Android app with package name **`com.delifar.app`**.
3. Register the SHA-1 and SHA-256 fingerprints of your signing certificate (`debug.keystore` for test builds, or your release keystore for Play Store builds).
4. Download **`google-services.json`** and place it at:
   ```
   app/google-services.json
   ```
   `app/build.gradle.kts` automatically detects `app/google-services.json` and applies `com.google.gms.google-services`. Never commit `google-services.json` to Git.

## 2. Enable Required Firebase Services
- **Authentication**: Enable **Email/Password** sign-in and send verification emails for invited employees (see Section 3 — `acceptEmployeeInvitation` requires `email_verified === true` and an exact case-insensitive email match).
- **Cloud Firestore**: Create a database in production mode.
- **Firebase Cloud Storage**: Create the default storage bucket.
- **Firebase Cloud Messaging (FCM)**: Enabled automatically.
- **Firebase App Check**: Register Android with **Play Integrity** (release builds) and add your debug token in Firebase Console for `DEBUG` builds.
- **Firebase Crashlytics**: Enable Crashlytics (enabled automatically on release builds; disabled on debug builds).

## 3. Deploy Security Rules, Indexes & Cloud Functions
From the project root using Firebase CLI (`firebase login && firebase use <your-project-id>`):
```bash
firebase deploy --only firestore:rules,firestore:indexes,storage
cd functions && npm install && npm run build && cd ..
firebase deploy --only functions
```

### Key Security Guarantees Enforced
- **Strict Tenant Isolation**: All Firestore documents and Storage objects are scoped under `companies/{companyId}/...`. Cross-company reads/writes are denied.
- **Verified Employee Invitations (`acceptEmployeeInvitation`)**: Requires authenticated caller with `email_verified === true`, valid non-empty `invitedEmail` and `callerEmail`, exact case-insensitive email match, and transactional single-use / expiration enforcement.
- **Owner Protection (`updateEmployeeAccess`)**: Owners cannot demote or deactivate themselves; only Owners can change employee roles or active state.
- **Driver Isolation**: Drivers can only read/update jobs assigned to them and invoices created by or assigned to them.
- **Signed-Invoice Immutability & Upload Ordering**:
  - `storage.rules` enforces a strict filename allowlist (`orig_<id>.(jpg|jpeg|png)`, `edit_<id>.(jpg|jpeg|png)`, `signature.png`, `signed_v<n>.pdf`), max 15 MiB size, content-type validation, and blocks overwriting immutable `orig_*`, `signature.png`, or `signed_v*.pdf` objects.
  - `CloudSyncManager` uploads binary attachments (`invoice_file`) and persists Storage paths **before** setting Firestore `signed = true` (`invoice`), so legitimate final uploads are never blocked by immutability rules and partially uploaded invoices are never marked `SYNCHRONIZED`.

### Running Security Rules Tests Locally
```bash
firebase emulators:exec --only firestore,storage --project delifar-rules-test "npx tsx firestore.rules.test.ts"
```

## 4. Mapping, Geocoding & Routing Policy
- **Interactive Native Map**: MapLibre Native Android (`11.8.0`) + **OpenFreeMap** vector tiles (`liberty` / `positron`) — no paid API keys required.
- **Personal / Device Geocoding**: Uses Android's built-in `Geocoder` first plus interactive manual map pin confirmation.
- **Commercial Fleet Geocoding & Routing**: Do **not** rely on public OSM Nominatim or public OSRM demo servers for commercial fleet traffic. Configure your own self-hosted or commercial HTTPS endpoints in **Settings -> Map, Geocoding & Routing Services** (only public HTTPS URLs are accepted; loopback/private IPs are blocked).
- **Offline Stop Ordering vs Street Routing**: Without an OSRM HTTPS endpoint configured, DeliFar computes an approximate straight-line nearest-neighbor stop order and explicitly labels it as non-street routing. Turn-by-turn navigation hands off validated, user-confirmed store coordinates to Google Maps via Android `Intent`.
