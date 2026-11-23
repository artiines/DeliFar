package com.delifar.app

import android.app.Application
import com.delifar.app.cloud.FirebaseServices
import com.delifar.app.reminders.ReminderScheduler
import com.google.android.gms.maps.MapsApiSettings
import com.google.android.gms.maps.MapsInitializer

class DeliFarApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Mandatory Google Maps Platform usage attribution ID for AI Studio
        runCatching {
            MapsApiSettings.addInternalUsageAttributionId(this, "gmp_mcp_codeassist_v1_aistudio")
            MapsInitializer.initialize(this, MapsInitializer.Renderer.LATEST, null)
        }
        // Safe Firebase initialization (Auth, Firestore offline cache, App Check, Crashlytics)
        FirebaseServices.initializeIfConfigured(this)
        ReminderScheduler.ensureChannel(this)
    }
}
