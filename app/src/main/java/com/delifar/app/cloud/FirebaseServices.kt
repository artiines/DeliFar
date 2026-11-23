package com.delifar.app.cloud

import android.content.Context
import android.util.Log
import com.delifar.app.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import com.google.firebase.firestore.Source
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

enum class OperationType {
    CREATE, UPDATE, DELETE, LIST, GET, WRITE
}

/**
 * Central Firebase service coordinator with safe runtime detection.
 * When `app/google-services.json` is configured, activates Auth, Firestore (persistent offline cache),
 * Storage, Functions, App Check (Debug/PlayIntegrity), and Crashlytics.
 * When not configured yet, degrades cleanly into full offline Room mode without crashing.
 */
object FirebaseServices {
    private const val TAG = "DeliFarFirebase"

    @Volatile
    var isConfigured: Boolean = false
        private set

    fun initializeIfConfigured(context: Context) {
        val initialized = runCatching {
            val apps = FirebaseApp.getApps(context)
            val app = if (apps.isNotEmpty()) apps.first() else FirebaseApp.initializeApp(context)
            app != null
        }.getOrDefault(false)

        isConfigured = initialized
        if (!initialized) {
            Log.i(TAG, "Firebase config (google-services.json) not present; running in offline-first Room mode.")
            return
        }

        // Configure Firestore persistent local cache
        runCatching {
            val settings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(PersistentCacheSettings.newBuilder().build())
                .build()
            FirebaseFirestore.getInstance().firestoreSettings = settings
        }

        // Initialize Firebase App Check (Debug provider in debug builds, Play Integrity in release)
        runCatching {
            val appCheck = FirebaseAppCheck.getInstance()
            if (BuildConfig.DEBUG) {
                appCheck.installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
            } else {
                appCheck.installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance())
            }
        }

        // Enable Crashlytics collection for release builds
        runCatching {
            FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled = !BuildConfig.DEBUG
        }
    }

    fun authOrNull(): FirebaseAuth? = if (isConfigured) runCatching { FirebaseAuth.getInstance() }.getOrNull() else null

    fun firestoreOrNull(): FirebaseFirestore? =
        if (isConfigured) runCatching { FirebaseFirestore.getInstance() }.getOrNull() else null

    fun storageOrNull(): FirebaseStorage? =
        if (isConfigured) runCatching { FirebaseStorage.getInstance() }.getOrNull() else null

    fun functionsOrNull(): FirebaseFunctions? =
        if (isConfigured) runCatching { FirebaseFunctions.getInstance() }.getOrNull() else null

    /**
     * Startup Firestore connectivity test (per Firebase integration guidelines).
     */
    suspend fun testConnection(): Boolean {
        val db = firestoreOrNull() ?: return false
        return try {
            db.collection("test").document("connection").get(Source.SERVER).await()
            true
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            if (msg.contains("offline", ignoreCase = true) || msg.contains("UNAVAILABLE", ignoreCase = true)) {
                Log.w(TAG, "Firestore unreachable / offline: $msg")
                false
            } else {
                // Permission-denied on test doc still proves live connectivity to Firestore server
                true
            }
        }
    }

    /**
     * Structured Firestore error diagnostic wrapper for debugging Security Rules and auth context.
     */
    fun handleFirestoreError(
        error: Throwable,
        operationType: OperationType,
        path: String?
    ): String {
        val user = authOrNull()?.currentUser
        val info = JSONObject().apply {
            put("error", error.message ?: error.javaClass.simpleName)
            put("operationType", operationType.name.lowercase())
            put("path", path ?: JSONObject.NULL)
            put("authInfo", JSONObject().apply {
                put("userId", user?.uid ?: JSONObject.NULL)
                put("email", user?.email ?: JSONObject.NULL)
                put("emailVerified", user?.isEmailVerified ?: false)
                put("isAnonymous", user?.isAnonymous ?: false)
            })
        }
        val json = info.toString()
        Log.e(TAG, "Firestore Error: $json")
        if (isConfigured) {
            runCatching {
                FirebaseCrashlytics.getInstance().log("FirestoreError[$operationType][$path]: ${error.message}")
            }
        }
        return json
    }
}
