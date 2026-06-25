pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "veyq-android"

// `:core` = pure Kotlin/JVM crypto + slice logic (BouncyCastle), testable headlessly against the live
// backend. `:app` = the Jetpack Compose / Material 3 app that depends on it. Build the app with
//   ANDROID_HOME=~/Library/Android/sdk ./gradlew :app:assembleDebug
include(":core", ":app")
