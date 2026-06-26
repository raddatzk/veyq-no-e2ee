// Root build script. Declares every plugin (with version, `apply false`) so they all load in ONE shared
// classloader, then each module applies them by id without a version. This is required for AGP 9's
// built-in Kotlin: if the Android and Kotlin Gradle plugins resolve in separate per-module classloader
// scopes, AGP's built-in-Kotlin integration fails to link KGP's KotlinAndroidTarget (NoClassDefFound on
// the removed `com.android.build.gradle.api.BaseVariant`). One classloader avoids that.
//
// All modules share KGP 2.2.10 (= AGP 9.2's built-in Kotlin); see android/CLAUDE.md.
plugins {
    id("com.android.application") version "9.2.1" apply false
    id("org.jetbrains.kotlin.jvm") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("org.openapi.generator") version "7.11.0" apply false
}
