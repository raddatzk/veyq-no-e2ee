plugins {
    kotlin("jvm")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
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
