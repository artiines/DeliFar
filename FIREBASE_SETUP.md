# DeliFar 2.0 — Firebase & Google Maps Setup Guide

DeliFar 2.0 runs fully offline with Room/SQLite out of the box and automatically activates real-time cloud sync, multi-user authentication, private invoice storage, and push notifications as soon as your Firebase project configuration is connected.

## 1. Connect Your Firebase Project
1. Open the [Firebase Console](https://console.firebase.google.com/) and create (or select) your project.
2. Add an Android app with package name **`com.delifar.app`**.
3. Download **`google-services.json`** and place it at:
   ```
   app/google-services.json
   ```
   (`app/build.gradle.kts` automatically detects and applies `com.google.gms.google-services` whenever `app/google-services.json` is present.)

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
> **Important**: Review `firestore.rules` and `storage.rules` before broad production rollout, and run `firestore.rules.test.ts` against the Firebase Emulator Suite to verify all role and multi-tenant isolation boundaries.

## 4. Google Maps Platform API Key & Restrictions
DeliFar uses Google Maps Platform exclusively (**Maps SDK for Android**, **Places API (New)**, and **Routes API**).
1. Ensure `GOOGLE_MAPS_API_KEY` is configured via `local.properties` or environment variable (see `.env.example`).
2. In Google Cloud Console -> **APIs & Services -> Credentials**, restrict your Android API key by:
   - **Application restriction**: Android apps (`com.delifar.app` + your release SHA-1 fingerprint).
   - **API restrictions**: Maps SDK for Android, Places API (New), Routes API.
