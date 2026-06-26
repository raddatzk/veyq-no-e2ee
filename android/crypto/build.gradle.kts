// veyq `v1` crypto — Android (Kotlin/JVM), auth/unlock subset (cr-0014). Pure JVM so the conformance test
// runs without the Android SDK. BouncyCastle provides Argon2id + Ed25519 + HKDF; AES-GCM uses the JDK's JCE.
// Byte-identical to the shared vectors (contracts/crypto/vectors/v1.json, ADR 0009).
plugins {
    kotlin("jvm") version "2.3.21" // match the backend — supports Gradle 9.5.1 + JDK 25
}

dependencies {
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    testImplementation(kotlin("test"))
    testImplementation("org.json:json:20240303") // parse the shared conformance-vector JSON
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}
