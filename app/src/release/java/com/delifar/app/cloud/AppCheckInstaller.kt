package com.delifar.app.cloud

import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

/**
 * Production release build-type App Check installer using Play Integrity.
 */
internal object AppCheckInstaller {
    const val providerName: String = "PlayIntegrity"

    fun install(appCheck: FirebaseAppCheck) {
        appCheck.installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance())
    }
}
