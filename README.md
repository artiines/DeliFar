# DeliFar

Native single-user Android delivery/pickup organizer. Kotlin, Jetpack Compose, Material 3, Android Navigation, and Room/SQLite. Package `com.delifar.app`. Minimum Android 8.0 (API 26); target API 36; compile API 37.

The source is in `app/src/main`. The requested debug APK is `output/DeliFar.apk`; the original Gradle artifact remains at `app/build/outputs/apk/debug/app-debug.apk`.

## Build on this computer

The existing tools were reused: Android Studio's bundled JBR, SDK platform 37, build tools 36.0.0, AGP 9.4.1, and Gradle 9.6.0. No Android Studio, SDK, JDK, or hypervisor installation is performed by this project.

From PowerShell in the project directory:

```powershell
.\build.ps1
```

This builds, runs Android lint and JVM tests, and copies the APK to the requested output path. `-SkipTests` skips only the JVM tests. Equivalent commands:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
Copy-Item app\build\outputs\apk\debug\app-debug.apk output\DeliFar.apk
```

On another computer, set `JAVA_HOME` to a compatible JDK and create an untracked `local.properties` with that computer's SDK path, or use `ANDROID_HOME`. The wrapper downloads Gradle only if the pinned distribution is absent. First builds need network access for Maven dependencies. All three languages are packaged in the APK.

The existing Android debug signing key is used. Preserve `%USERPROFILE%\.android\debug.keystore` for compatible personal-test updates. Do not uninstall to update: install the new APK over the old one with the same package/signing identity. This project does not create release signing material. Increasing `versionCode`, adding explicit Room migrations, and retaining signing identity are required for subsequent releases.

## Install and first use

Copy `output/DeliFar.apk` to an Android 8.0+ phone, open it, and allow installation from that file manager if Android asks. Alternatively, with an authorized USB device:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r output\DeliFar.apk
```

1. Open DeliFar. Home shows today's work in Los Angeles business time.
2. Open **Settings** to choose English, Russian, Armenian, or the device language. Enable notification permission and review reminder settings.
3. **Add Store**: choose Los Angeles or Sacramento, search a public business name/address, select the correct branch, check its address/map, and optionally enter its phone and receiving hours. Save it.
4. **New Delivery / Pickup**: choose a saved store, a date and optional time, and at least one product line. Quantities accept the interface locale's decimal separator. Review and save.
5. Open a job to complete, reopen, reschedule, cancel, or report a partial/unsuccessful outcome. Reporting an issue defaults to keeping work open.
6. Export business data regularly to a safe external location. App removal or phone loss can destroy local records. JSON backups are not encrypted and contain business data.

## Maps and search

* Search: [Photon](https://github.com/komoot/photon#demo-server), using OpenStreetMap data. No key or account is required. Searches happen only on a Search button press, are serialized with at least 1.1 seconds between requests, and cached for seven days. A 12-result maximum and an LA/Sacramento bounding box keep requests small. Store IDs use stable OSM element type/id rather than ephemeral result positions. Coverage depends on OpenStreetMap; address search can also return non-grocery businesses at a shared address.
* Map: osmdroid 6.1.20 with [OpenStreetMap standard tiles](https://operations.osmfoundation.org/policies/tiles/). Visible attribution, identifying User-Agent, seven-day tile cache, no bulk downloads/prefetch feature, and screen-space marker clustering. Lists remain available if tiles fail.
* Photon and OSM's public tile servers have no availability SLA. This build is for low-volume personal use, not deployment to a large fleet or public user base. Queries and visible map areas go to the respective provider. Business jobs/items are never uploaded to them. Do not submit confidential addresses to public search.
* Both compatible HTTPS endpoints can be changed under **Settings → Map and search services** without rebuilding. `mapping.example.properties` documents defaults; it is an example, not a credential loader. No mapping secrets are in the repository/APK. A future switch to a credentialed SDK would require a code/configuration change and a rebuilt APK; server secrets must remain outside the app.
* OSM-derived search results use [ODbL](https://www.openstreetmap.org/copyright). Attribution appears at search/map views and About. For larger deployments, choose a hosted service with appropriate terms or self-host Photon/tiles. osmdroid is a mature but archived upstream library; replacing it should be evaluated before wider distribution.

## Data and recovery

Room schema version 4 uses separate `stores`, `hours`, `jobs`, `items`, `preferences`, `drafts`, and `receipts` tables. Foreign keys and indexes protect relationships and support date/store queries. Schema exports are checked in under `app/schemas`. Explicit `1→2→3→4` migrations exist; destructive fallback is deliberately absent. These early versions are development schemas, not legacy applications.

Jobs use ISO calendar dates and optional local times in `America/Los_Angeles`, with decimal quantities stored as strings and validated through `BigDecimal`. Changing the phone's time zone does not rewrite scheduled dates. Completed/canceled jobs retain store name, address, and coordinate snapshots. Normal lists query counts and job metadata; product details are loaded only on relevant detail/edit/map-sheet views.

Job drafts use a stable job ID and are written to Room as fields change. Keeping a draft explicitly flushes it. The job, items, and draft removal are committed together. An interrupted transaction cannot leave a job without its product lines. Edits that reach the database survive process death; there is necessarily a small interval before an asynchronous keystroke write has committed.

See [BACKUP_FORMAT.md](BACKUP_FORMAT.md) for import validation, conflict handling, and the format. Provider settings, credentials, cached tiles/searches, transient drafts, and notification receipts are not exported. Android automatic cloud backup/device transfer is disabled; the explicit file-picker export is the supported recovery path.

## Reminders

Defaults: summary at 07:00 Los Angeles time; timed-job reminders 60 minutes in advance. Each timed job has an individual reminder switch on its detail screen. Untimed jobs appear only in the summary. Completed/canceled jobs do not trigger reminders. Rescheduling/reopening or changing an individual reminder switch changes an alert version, and a durable receipt prevents repeated delivery of the same alert. A future job already inside its reminder window gets one immediate eligible alert.

The app maintains one next `AlarmManager.setAndAllowWhileIdle` alarm and rebuilds it from Room after saves and boot/package/time changes. No exact-alarm elevated permission is requested. Android battery optimization and manufacturer restrictions can delay notification delivery; force-stop prevents alarms until the app is opened again. Expired job alerts are not replayed, and summaries delayed beyond six hours are skipped. No internet is needed for local reminders.

## Verification

See [ACCEPTANCE.md](ACCEPTANCE.md) for the final requirement checklist, exact test results, and distinctions between JVM simulation, emulator/device testing, and live provider checks. A successful APK build alone is not evidence that every device workflow is verified.
