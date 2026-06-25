plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") // for the spec-generated API models (kotlinx.serialization)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

// Spike: API DTOs generated from docs/api/*.openapi.yaml via scripts/gen_mobile_models.py.
// Single source of truth for the wire shape; the hand-written transport/crypto layer stays.
sourceSets.main { java.srcDir("src/generated/kotlin") }

dependencies {
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3") // runtime for generated models
    implementation("org.json:json:20240303") // platform-provided on Android; explicit for this JVM core
    // JNA backs the uniffi-generated Yrs (y-crdt) binding. compileOnly so the JVM jar doesn't leak into the
    // Android app (which supplies the JNA *aar* for the native runtime); the JVM tests add it back below.
    compileOnly("net.java.dev.jna:jna:5.14.0")
    testImplementation("net.java.dev.jna:jna:5.14.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // JVM tests load the host-built Yrs dylib; the Android app uses the cross-compiled .so in jniLibs.
    systemProperty("jna.library.path", file("native").absolutePath)
}
