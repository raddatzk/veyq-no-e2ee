// veyq Android client modules (cr-0016):
//   :crypto — pure Kotlin/JVM `v1` suite (cr-0014); the parity guarantee with web/Apple.
//   :client — pure Kotlin/JVM data layer: the contracts-generated OpenAPI clients + session/vault/
//             login/unlock orchestration. Pure JVM so it tests without the Android SDK and is reusable
//             by a headless JVM agent (ADR 0010).
//   :app    — thin Android (Compose) UI head over :client.
pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "veyq-android"
include(":crypto")
include(":client")
include(":app")
