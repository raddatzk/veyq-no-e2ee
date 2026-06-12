rootProject.name = "veyq-android"

// Greenfield Android. For now this is the pure Kotlin/JVM **crypto core** (BouncyCastle), so it is
// testable without the Android SDK. The Jetpack Compose app + Android Keystore / Credential Manager
// are added as an AGP `:app` module once an Android SDK is available; this becomes `:core`.
