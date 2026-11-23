# DeliFar 2.0 — Firebase & Free Mapping Setup Guide

DeliFar 2.0 runs fully offline with Room/SQLite out of the box and automatically activates real-time cloud sync, multi-user authentication, private invoice storage, and push notifications as soon as your Firebase project configuration is connected.

## 1. Connect Your Firebase Project
1. Open the [Firebase Console](https://console.firebase.google.com/) and create (or select) your project.
2. Add an Android app with package name **`com.delifar.app`**.
3. Download **`google-services.json`** and place it at:
   ```
   app/google-services.json
   ```
   (`app/build.gradle.kts` automatically detects and applies `com.google.gms.google-services` whenever `app/google-services.json` is present. `google-services.json` is gitignored and must never be committed.)

## 2. Enable Required Firebase Services
In the Firebase Console, enable:
- **Authentication**: Enable **Email/Password** sign-in provider.
- **Cloud Firestore**: Create a database in production mode.
- **Firebase Cloud Storage**: Create the default storage bucket.
- **Firebase Cloud Messaging (FCM)**: Enabled automatically with the project.
- **Firebase App Check**: Register the Android app with **Play Integrity** (release builds) and add your debug token for local development.
- **Firebase Crashlytics**: Enable Crashlytics in the console.

## 3. Deploy Security Rules, Indexes & Cloud Functions
From the project root using Firebase CLI (`firebase login` and `firebase use <your-project-id>`):
```bash
firebase deploy --only firestore:rules,firestore:indexes,storage
cd functions && npm install && npm run build && cd ..
firebase deploy --only functions
```
> **Important**: Run `firestore.rules.test.ts` against the Firebase Emulator Suite before production rollout to verify company isolation, role enforcement, driver invoice isolation, signed-invoice immutability, and Storage rules.

## 4. Free Mapping, Geocoding & Routing (No Paid API Keys Required)
DeliFar 2.0 uses a 100% free, zero-API-key mapping stack:
- **Interactive Native Map**: MapLibre Native Android (`org.maplibre.gl:android-sdk`) rendering **OpenFreeMap** vector tiles (`https://tiles.openfreemap.org/styles/liberty` and `positron`).
- **Address Geocoding**: Android built-in `Geocoder` first, with a policy-compliant, rate-limited (1.15s min interval, 7-day Room cache) OpenStreetMap Nominatim fallback.
- **Route Ordering & Street Routing**: Built-in offline nearest-neighbor ordering by default, plus an optional configurable OSRM HTTPS endpoint for turn-by-turn street geometry.
- **External Turn-by-Turn Navigation**: Hands off validated real store coordinates directly to the installed Google Maps app (or any geo-handler) via Android `Intent` — no Google Maps SDK or paid API keys required.

### Production Business Traffic Recommendation
Public demonstration services (such as public Nominatim or public OSRM) enforce strict usage policies and rate limits. For high-volume commercial dispatch, configure your own HTTPS endpoints in **Settings -> Mapping & Routing Services** (e.g., self-hosted OpenFreeMap/Protomaps tiles, self-hosted Nominatim/Pelias geocoder, and self-hosted OSRM routing server) without rebuilding the APK.
