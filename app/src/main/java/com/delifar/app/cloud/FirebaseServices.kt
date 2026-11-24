package com.delifar.app.cloud

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.delifar.app.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
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

enum class BackendHealth {
    NOT_CONFIGURED,
    NOT_SIGNED_IN,
    OFFLINE,
    REACHABLE,
    ACCESS_DENIED,
    BACKEND_NOT_CONFIGURED
}

data class BackendHealthCheck(
    val health: BackendHealth,
    val detail: String? = null
)

/**
 * Central Firebase service coordinator with safe runtime detection.
 *
 * Clearly separates:
 *  - `isConfigured`: Firebase config (`app/google-services.json`) was bundled and `FirebaseApp` initialized.
 *  - `isAuthenticated`: A real Firebase Auth user is currently signed in.
 *  - Backend reachability: Verified against Firestore server via [testConnectionDetailed], never assumed
 *    from `FirebaseApp.initializeApp(...)` alone.
 *
 * Missing Firebase backend services, disabled Auth/Firestore/Storage/Functions, or offline devices
 * degrade cleanly into offline-first Room mode without crashing startup.
 */
object FirebaseServices {
    private const val TAG = "DeliFarFirebase"

    @Volatile
    var isConfigured: Boolean = false
        private set

    @Volatile
    var appCheckInstalled: Boolean = false
        private set

    @Volatile
    var appCheckProviderName: String = AppCheckInstaller.providerName
        private set

    @Volatile
    var crashlyticsReady: Boolean = false
        private set

    @Volatile
    var lastInitError: String? = null
        private set

    val isAuthenticated: Boolean
        get() = authOrNull()?.currentUser != null

    val currentAuthUid: String?
        get() = authOrNull()?.currentUser?.uid

    val currentAuthEmail: String?
        get() = authOrNull()?.currentUser?.email

    fun initializeIfConfigured(context: Context) {
        val initResult = runCatching {
            val apps = FirebaseApp.getApps(context)
            val app = if (apps.isNotEmpty()) apps.first() else FirebaseApp.initializeApp(context)
            app != null
        }
        val initialized = initResult.getOrDefault(false)
        initResult.exceptionOrNull()?.let { err ->
            lastInitError = "Firebase init failed: ${err.message ?: err.javaClass.simpleName}"
            Log.w(TAG, lastInitError, err)
        }

        isConfigured = initialized
        if (!initialized) {
            appCheckInstalled = false
            crashlyticsReady = false
            Log.i(TAG, "Firebase config (google-services.json) not present; running in offline-first Room mode.")
            return
        }
        lastInitError = null

        // Configure Firestore persistent local cache (never fatal if Firestore already started or not created)
        runCatching {
            val settings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(PersistentCacheSettings.newBuilder().build())
                .build()
            FirebaseFirestore.getInstance().firestoreSettings = settings
        }.onFailure { err ->
            Log.w(TAG, "Firestore persistent cache configuration warning: ${err.message}")
        }

        // Initialize Firebase App Check (Debug provider in debug builds, Play Integrity in release builds)
        appCheckInstalled = runCatching {
            val appCheck = FirebaseAppCheck.getInstance()
            AppCheckInstaller.install(appCheck)
            appCheckProviderName = AppCheckInstaller.providerName
            true
        }.onFailure { err ->
            lastInitError = "App Check initialization warning: ${err.message}"
            Log.w(TAG, lastInitError, err)
        }.getOrDefault(false)

        // Enable Crashlytics collection only for release builds; never fatal on startup
        crashlyticsReady = runCatching {
            FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled = !BuildConfig.DEBUG
            true
        }.onFailure { err ->
            Log.w(TAG, "Crashlytics initialization warning: ${err.message}")
        }.getOrDefault(false)
    }

    fun isNetworkAvailable(context: Context): Boolean {
        return runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return@runCatching true
            val network = cm.activeNetwork ?: return@runCatching false
            val caps = cm.getNetworkCapabilities(network) ?: return@runCatching false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.getOrDefault(true)
    }

    fun authOrNull(): FirebaseAuth? = if (isConfigured) runCatching { FirebaseAuth.getInstance() }.getOrNull() else null

    fun firestoreOrNull(): FirebaseFirestore? =
        if (isConfigured) runCatching { FirebaseFirestore.getInstance() }.getOrNull() else null

    fun storageOrNull(): FirebaseStorage? =
        if (isConfigured) runCatching { FirebaseStorage.getInstance() }.getOrNull() else null

    fun functionsOrNull(): FirebaseFunctions? =
        if (isConfigured) runCatching { FirebaseFunctions.getInstance() }.getOrNull() else null

    /**
     * Classifies a Firebase/Firestore exception into an understandable, actionable health state.
     */
    fun classifyBackendError(error: Throwable): BackendHealthCheck {
        val codeName = if (error is FirebaseFirestoreException) error.code.name else null
        val msg = error.message.orEmpty()
        return when {
            codeName == "UNAUTHENTICATED" ||
                msg.contains("UNAUTHENTICATED", ignoreCase = true) ->
                BackendHealthCheck(
                    BackendHealth.NOT_SIGNED_IN,
                    "Sign in to your Firebase account to synchronize."
                )

            codeName == "PERMISSION_DENIED" ||
                msg.contains("PERMISSION_DENIED", ignoreCase = true) ->
                BackendHealthCheck(
                    BackendHealth.ACCESS_DENIED,
                    "Access denied by Firestore rules. Verify your account belongs to this company and rules are deployed."
                )

            codeName == "NOT_FOUND" ||
                codeName == "FAILED_PRECONDITION" ||
                msg.contains("database (default) does not exist", ignoreCase = true) ||
                msg.contains("has not been used in project", ignoreCase = true) ||
                msg.contains("Cloud Firestore API", ignoreCase = true) ->
                BackendHealthCheck(
                    BackendHealth.BACKEND_NOT_CONFIGURED,
                    "Firestore database or required Firebase backend service is not configured yet in Firebase Console."
                )

            codeName == "UNAVAILABLE" ||
                codeName == "DEADLINE_EXCEEDED" ||
                msg.contains("offline", ignoreCase = true) ||
                msg.contains("UNAVAILABLE", ignoreCase = true) ||
                msg.contains("Unable to resolve host", ignoreCase = true) ||
                msg.contains("Failed to get document because the client is offline", ignoreCase = true) ->
                BackendHealthCheck(
                    BackendHealth.OFFLINE,
                    "Device is offline or Firebase servers are unreachable. Changes remain safely saved locally."
                )

            else ->
                BackendHealthCheck(
                    BackendHealth.OFFLINE,
                    msg.ifBlank { error.javaClass.simpleName }
                )
        }
    }

    /**
     * Checks whether Firebase config, authentication, and Firestore backend are actually ready.
     */
    suspend fun testConnectionDetailed(context: Context? = null, companyId: String = ""): BackendHealthCheck {
        if (!isConfigured) {
            return BackendHealthCheck(BackendHealth.NOT_CONFIGURED, "Firebase configuration is not bundled in this build.")
        }
        if (context != null && !isNetworkAvailable(context)) {
            return BackendHealthCheck(BackendHealth.OFFLINE, "No internet connection. Changes are saved locally.")
        }
        val user = authOrNull()?.currentUser
            ?: return BackendHealthCheck(BackendHealth.NOT_SIGNED_IN, "Firebase is configured, but you are not signed in.")
        val db = firestoreOrNull()
            ?: return BackendHealthCheck(BackendHealth.BACKEND_NOT_CONFIGURED, "Firestore client is unavailable.")

        return try {
            if (companyId.isNotBlank()) {
                db.collection("companies").document(companyId)
                    .collection("employees").document(user.uid)
                    .get(Source.SERVER).await()
            } else {
                db.collection("users").document(user.uid).get(Source.SERVER).await()
            }
            BackendHealthCheck(BackendHealth.REACHABLE)
        } catch (e: Exception) {
            classifyBackendError(e)
        }
    }

    suspend fun testConnection(): Boolean {
        return testConnectionDetailed().health == BackendHealth.REACHABLE
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
        if (isConfigured && crashlyticsReady) {
            runCatching {
                FirebaseCrashlytics.getInstance().log("FirestoreError[$operationType][$path]: ${error.message}")
            }
        }
        return json
    }

    internal fun resetForTest() {
        isConfigured = false
        appCheckInstalled = false
        crashlyticsReady = false
        lastInitError = null
    }
}
