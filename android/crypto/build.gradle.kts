// veyq `v1` crypto — Android (Kotlin/JVM). Pure JVM so the conformance test runs without the Android SDK.
// BouncyCastle provides Argon2id + Ed25519 + HKDF + X25519 + ML-KEM-768; AES-GCM uses the JDK's JCE.
// Byte-identical to the shared vectors (contracts/crypto/vectors/v1.json, ADR 0009). BC ≥1.79 is required
// for FIPS-203 ML-KEM (`org.bouncycastle.pqc.crypto.mlkem`); 1.78.1 only had the older round-3 Kyber.
plugins {
    kotlin("jvm") // version pinned in the root build script (KGP 2.2.10, shared classloader)
}

dependencies {
    implementation("org.bouncycastle:bcprov-jdk18on:1.81")
    testImplementation(kotlin("test"))
    testImplementation("org.json:json:20240303") // parse the shared conformance-vector JSON
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}
