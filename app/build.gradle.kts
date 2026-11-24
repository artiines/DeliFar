plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Activate Google Services and Crashlytics plugins automatically whenever app/google-services.json is present.
// Crashlytics Gradle plugin generates the required `com.google.firebase.crashlytics.mapping_file_id` resource
// so CrashlyticsRegistrar does not throw IllegalStateException at app startup.
val hasGoogleServicesConfig = file("google-services.json").exists()
if (hasGoogleServicesConfig) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
}

android {
    namespace = "com.delifar.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.delifar.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "2.1.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("debugConfig") {
            val ks = file("${rootDir}/debug.keystore")
            if (ks.exists()) {
                storeFile = ks
                storePassword = System.getenv("DELIFAR_DEBUG_STORE_PASSWORD")?.ifBlank { null } ?: "android"
                keyAlias = System.getenv("DELIFAR_DEBUG_KEY_ALIAS")?.ifBlank { null } ?: "androiddebugkey"
                keyPassword = System.getenv("DELIFAR_DEBUG_KEY_PASSWORD")?.ifBlank { null } ?: "android"
            }
        }
        create("releaseConfig") {
            val relPath = System.getenv("DELIFAR_RELEASE_KEYSTORE_PATH")?.ifBlank { null }
            val relFile = relPath?.let { file(it) } ?: file("${rootDir}/release.keystore")
            if (relFile.exists()) {
                storeFile = relFile
                storePassword = System.getenv("DELIFAR_RELEASE_STORE_PASSWORD")?.ifBlank { null }
                keyAlias = System.getenv("DELIFAR_RELEASE_KEY_ALIAS")?.ifBlank { null }
                keyPassword = System.getenv("DELIFAR_RELEASE_KEY_PASSWORD")?.ifBlank { null }
            }
        }
    }

    buildTypes {
        debug {
            if (file("${rootDir}/debug.keystore").exists()) {
                signingConfig = signingConfigs.getByName("debugConfig")
            }
            if (hasGoogleServicesConfig) {
                extensions.findByName("firebaseCrashlytics")?.let { ext ->
                    // Generate the Crashlytics build/mapping ID resource without attempting network symbol upload on debug builds
                    ext.javaClass.methods
                        .firstOrNull { it.name == "setMappingFileUploadEnabled" && it.parameterCount == 1 }
                        ?.invoke(ext, false)
                }
            }
        }
        release {
            isMinifyEnabled = false
            val relSigning = signingConfigs.getByName("releaseConfig")
            if (relSigning.storeFile != null && relSigning.storeFile!!.exists()) {
                signingConfig = relSigning
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.1")

    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.9")

    // Room offline database
    implementation("androidx.room:room-runtime:2.7.0")
    implementation("androidx.room:room-ktx:2.7.0")
    ksp("androidx.room:room-compiler:2.7.0")

    // WorkManager for reliable background sync & uploads
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Coroutines & Play Services Tasks bridge (for Firebase Tasks)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")

    // Free Native Vector Mapping: MapLibre Android SDK (renders OpenFreeMap tiles, zero API key)
    implementation("org.maplibre.gl:android-sdk:11.8.0")

    // Firebase SDK (BoM 34.17.0 — Auth, Firestore, Storage, Messaging, Functions, App Check, Crashlytics)
    implementation(platform("com.google.firebase:firebase-bom:34.17.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-storage")
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.google.firebase:firebase-functions")
    implementation("com.google.firebase:firebase-appcheck")
    debugImplementation("com.google.firebase:firebase-appcheck-debug")
    releaseImplementation("com.google.firebase:firebase-appcheck-playintegrity")
    implementation("com.google.firebase:firebase-crashlytics")

    // Automated unit & Room migration / Robolectric tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.room:room-testing:2.7.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")

    // Real Android Emulator / device instrumentation startup smoke tests
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Reproducibly prefetch Robolectric 4.14.1 Android instrumented runtime JARs via Gradle/Maven Central
// so unit tests run deterministically offline without invoking Robolectric's runtime MavenArtifactFetcher.
// Separate configurations prevent Gradle from resolving a version conflict between two versions of the same
// org.robolectric:android-all-instrumented coordinate:
//   - SDK 34 satisfies @Config(sdk = [34])
//   - SDK 35 (Robolectric 4.14.1 max SDK) satisfies app resource loading when compileSdk/targetSdk >= 35
val robolectricSdk34 by configurations.creating {
    isTransitive = false
}
val robolectricSdk35 by configurations.creating {
    isTransitive = false
}

dependencies {
    robolectricSdk34("org.robolectric:android-all-instrumented:14-robolectric-10818077-i7")
    robolectricSdk35("org.robolectric:android-all-instrumented:15-robolectric-12650502-i7")
}

val robolectricDepsDir = layout.buildDirectory.dir("robolectric-deps")

val syncRobolectricSdkJars by tasks.registering(Sync::class) {
    from(robolectricSdk34)
    from(robolectricSdk35)
    into(robolectricDepsDir)
}

tasks.withType<Test>().configureEach {
    dependsOn(syncRobolectricSdkJars)
    doFirst {
        val dir = robolectricDepsDir.get().asFile
        systemProperty("robolectric.dependency.dir", dir.absolutePath)
        systemProperty("robolectric.offline", "true")
    }
}

