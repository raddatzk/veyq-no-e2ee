plugins {
    kotlin("jvm") version "2.3.21"
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
    implementation("org.json:json:20240303") // platform-provided on Android; explicit for this JVM core
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
