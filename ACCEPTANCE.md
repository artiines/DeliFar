# DeliFar acceptance record

This record distinguishes code, automated simulation, live service checks, and device behavior. It is deliberately more conservative than a build result.

## Verification evidence

| Check | Result |
|---|---|
| Gradle debug build | Successful; final `output/DeliFar.apk` is an installable debug artifact |
| Android lint | Successful with warnings; no fatal errors |
| JVM Android simulation | 40 tests: 17 rules, 18 Room/repository/backup, 3 reminders, 2 Compose UI; all passed in the final run |
| Representative data | 500 stores, 10,000 jobs, one job with 50 lines. Combined store/schedule queries took about 22 ms in the JVM Android simulation, and 10,000 jobs exported/parsed in a roughly 6.7 MiB JSON file. This is not a phone benchmark. |
| Live Photon service | Real business-name searches returned Los Angeles Trader Joe's and Sacramento Safeway branches. A street-address query returned real matching addresses. Tested from the development computer against the same endpoint and request parameters used by the app. |
| Live map tile endpoint | One OpenStreetMap PNG request returned HTTP 200 with cache headers from the development computer. This does not verify on-device map rendering. |
| Emulator installation/launch | Blocked: the installed x86_64 Android 17 emulator reports that the Android Emulator hypervisor driver is absent. Software-emulation attempts remained offline and did not boot. No connected physical device was available. |
| Physical device | Not tested; no device was connected. |
| Real app network/maps | Implemented, but not verified in a running app because no Android runtime was available. |

The automated tests cover data integrity, decimal input, Los Angeles time and daylight-saving changes, overdue counts, weekday windows, same-store jobs, partial outcomes, archival rules, snapshots, backup round trips and invalid/conflicting backups, v1 migration, draft persistence, reminders and deduplication, activity recreation, two localized screens, and the principal job status flow. Room tests use an in-memory SQLite database. The UI tests use Robolectric at a 360dp screen size. They do not replace Android emulator/phone testing.

## Requirement checklist

Status meanings: **Verified** means implemented and exercised by the stated automated or live-service check. **Unverified** means implemented in source and build, but the full behavior was not run on an Android device. **Blocked** means an external condition prevented acceptance. **Not implemented** means deliberately outstanding.

| Requirement | Status and evidence/limit |
|---|---|
| Native app, package/name/icon, four tabs, Settings on Home | **Unverified** on device. Source/build and Robolectric Home/Settings checks pass; APK metadata confirms package and minimum API. |
| English, Russian, Armenian strings and selector | **Verified** for resource coverage and Russian/Armenian settings rendering at 360dp in Robolectric; **unverified** for every screen, larger text, and physical devices. |
| Los Angeles date/time rules, Monday–Sunday week | **Verified** in rule tests, including travel and daylight-saving cases. |
| Home counts, overdue work, product-line counts, sorting | **Verified** in rule/Room tests; **unverified** visually on device. |
| Real store name/address search, area bias, duplicate detection, cached coordinates | **Verified** for live provider responses and Room persistence/duplicate IDs; **unverified** in the Android app without a device. Photon availability and coverage depend on its public service. |
| Store phone, receiving hours, closed/unknown days, multiple windows, warnings | **Verified** in Room/rule tests; **unverified** in a running app. |
| Interactive map, clustering, filters, one pin per store, sheets | **Implemented but unverified** on device. Database filter and same-store grouping logic have tests; actual map gestures, tile rendering, marker clusters, and sheets need a device run. |
| Delivery/pickup jobs, products, decimals, edit/reschedule/status/partial outcomes | **Verified** for Room operations and main Compose UI flow in simulation; **unverified** across all device UI paths. |
| Draft recovery, atomic save, repeated Save protection | **Verified** for Room draft/transaction behavior and activity recreation in simulation; process kill mid-keystroke and rapid double-tap need device tests. |
| Schedule and store details, archive/restore, directions | **Verified** for date queries and archival rules; **unverified** for device presentation, external navigation, and dialer fallback. |
| Local reminders, permissions, reboot reconstruction | **Verified** for Robolectric alarm/notification scheduling, deduplication, completion/reopen/cancellation, denied permission, and simulated lost alarms. **Unverified** on a booted device and under manufacturer battery restrictions. Android may delay inexact alarms. |
| Room offline records and versioned migrations | **Verified** for offline Room operations and v1→v2→v3→v4 migration; no destructive fallback. **Unverified** on device restart/app update. |
| JSON export/import, complete validation, conflict preview/merge | **Verified** for parser, round trip, invalid files, stable ID conflict choices, and transaction rollback. **Unverified** for Android system picker UX. |
| 500-store/10,000-job responsiveness | **Verified** in JVM Android simulation only; **unverified** on representative hardware. |
| 320dp and larger screens, enlarged fonts, keyboard, rotation, slow network | **Not fully verified**. One 360dp UI simulation and provider HTTP calls were exercised. Device display, accessibility, slow/offline network UI, and rotation remain acceptance tests. |
| Install and launch debug APK | **Blocked** by unavailable Android runtime on this computer. Test installation on an Android 8.0+ phone or a working emulator. |
| Release signing/distribution | **Not implemented**; the requested artifact is a debug APK for personal testing. |

## Device acceptance pass when a device is available

Install over any existing DeliFar debug build with the same signing key. Launch in each language, add a real LA and Sacramento store from search, save receiving hours, create delivery and pickup jobs including a partial outcome, test Home/Map/Schedule/Stores and directions, deny/allow notifications, export to a JSON file, import into a clean test install, and verify reminders after reboot. Check at 320dp and larger widths, 1.5× system font, rotation, and offline mode. If any core flow fails, treat it as a defect rather than accepting the APK as fully verified.
