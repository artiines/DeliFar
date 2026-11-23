package com.delifar.app

import android.app.Application
import com.delifar.app.cloud.FirebaseServices
import com.delifar.app.reminders.ReminderScheduler
import org.maplibre.android.MapLibre

class DeliFarApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize MapLibre Native Android SDK (renders free OpenFreeMap vector tiles with no API key)
        runCatching {
            MapLibre.getInstance(this)
        }
        // Safe Firebase initialization (Auth, Firestore offline cache, App Check, Crashlytics)
        FirebaseServices.initializeIfConfigured(this)
        ReminderScheduler.ensureChannel(this)
    }
}
