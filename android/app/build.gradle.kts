// veyq `:app` — the thin Android (Jetpack Compose) UI head over `:client` (cr-0016). All orchestration,
// crypto and DTOs live below in `:client` / `:crypto`; this module is screens only. Material 3 themed to
// the veyq design tokens (ui-android-styleguide.md), WindowSizeClass-responsive (phone fills, tablet caps
// + centres). Requires the Android SDK (~/Library/Android/sdk); `:crypto`/`:client` do not.
plugins {
    // versions pinned in the root build script (shared classloader). AGP 9 supplies the Kotlin
    // compilation (built-in Kotlin, KGP 2.2.10); only the Compose compiler plugin is applied explicitly.
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.veyq.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.veyq.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Route test output files through the TestStorage service so AGP copies the round-trip screenshots
        // back to the host (the app is uninstalled after the run, which wipes its own external files dir).
        testInstrumentationRunnerArguments["useTestStorageService"] = "true"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

// AGP 9's built-in Kotlin is configured via the top-level `kotlin { }` extension (not android.kotlinOptions).
// Match jvmTarget to the JVM 21 of compileOptions and of the `:crypto`/`:client` toolchains.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(project(":client"))
    implementation(project(":crypto"))

    // The latest AndroidX wave (2026) compiles only against android-37, which is not yet available as an
    // SDK platform via the command line (only the API-37 emulator image is). Pin the newest 36-wave
    // (late-2025) releases so the app builds against the installed compileSdk 36; they still run on the
    // API-37 emulator. Revisit when the android-37 platform ships to the stable channel.
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.biometric:biometric:1.1.0") // BiometricPrompt + CryptoObject (cr-0022 L2)
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0") // LocalLifecycleOwner for the camera scanner
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Live QR scan for the Emergency-Kit import — CameraX preview/analysis + bundled ML Kit barcode model
    // (no Google Play Services / Code Scanner dependency). Camera runtime permission requested on demand.
    implementation("androidx.camera:camera-core:1.5.3")
    implementation("androidx.camera:camera-camera2:1.5.3")
    implementation("androidx.camera:camera-lifecycle:1.5.3")
    implementation("androidx.camera:camera-view:1.5.3")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    val composeBom = platform("androidx.compose:compose-bom:2025.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended") // folder/photo/person/settings for the Memory shell (cr-0039)
    debugImplementation("androidx.compose.ui:ui-tooling")

    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    // 2025 test libs — older espresso reflects on InputManager.getInstance(), removed in API 37.
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestUtil("androidx.test.services:test-services:1.6.0") // copies TestStorage output back to host
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
