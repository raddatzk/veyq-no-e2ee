// veyq Android client. For now just the pure-JVM `:crypto` module (the native `v1` suite, cr-0014) —
// app/UI modules (Compose, requiring the Android SDK) arrive with the login/unlock + UI slices.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "veyq-android"
include(":crypto")
