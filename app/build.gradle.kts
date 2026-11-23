plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Activate Google Services plugin automatically whenever app/google-services.json is present
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.delifar.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.delifar.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "2.1.0"
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
    }

    buildTypes {
        debug {
            if (file("${rootDir}/debug.keystore").exists()) {
                signingConfig = signingConfigs.getByName("debugConfig")
            }
        }
        release {
            isMinifyEnabled = false
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
    implementation("com.google.firebase:firebase-appcheck-playintegrity")
    implementation("com.google.firebase:firebase-appcheck-debug")
    implementation("com.google.firebase:firebase-crashlytics")

    // Automated unit & Room migration / Robolectric tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.room:room-testing:2.7.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

// Reproducibly prefetch Robolectric 4.14.1 Android 14 (SDK 34) instrumented runtime JAR via Gradle/Maven Central
// so unit tests run deterministically offline without invoking Robolectric's runtime MavenArtifactFetcher.
val robolectricSdk by configurations.creating {
    isTransitive = false
}

dependencies {
    robolectricSdk("org.robolectric:android-all-instrumented:14-robolectric-10818077-i7")
}

val robolectricDepsDir = layout.buildDirectory.dir("robolectric-deps")

val syncRobolectricSdkJars by tasks.registering(Sync::class) {
    from(robolectricSdk)
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

