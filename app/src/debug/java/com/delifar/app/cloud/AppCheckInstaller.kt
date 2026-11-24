package com.delifar.app.cloud

import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * Debug build-type App Check installer.
 * Only included in debug builds; production release builds compile the Play Integrity variant instead.
 */
internal object AppCheckInstaller {
    const val providerName: String = "Debug"

    fun install(appCheck: FirebaseAppCheck) {
        appCheck.installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
    }
}
