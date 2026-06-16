plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.allopen") version "2.3.21"
    id("io.quarkus") version "3.36.0"
}

apply(from = "gradle/openapi.gradle.kts")

repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    implementation(enforcedPlatform("io.quarkus.platform:quarkus-bom:3.36.0"))
    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-kotlin")
    implementation("io.quarkus:quarkus-rest")
    implementation("io.quarkus:quarkus-rest-jackson")
    implementation("io.quarkus:quarkus-hibernate-validator")
    implementation("io.quarkus:quarkus-smallrye-health")
    implementation("io.quarkus:quarkus-hibernate-orm-panache-kotlin")
    implementation("io.quarkus:quarkus-jdbc-h2") // dev/slice store; MariaDB swapped in for deployment
    implementation("io.quarkus:quarkus-flyway")
    implementation("io.quarkus:quarkus-scheduler") // periodic GC of expired sessions
    implementation("io.quarkus:quarkus-mailer") // outbound mail: email invites (the operator supplies SMTP)
    implementation("io.quarkus:quarkus-websockets-next") // operator-blind relay for live-journal CRDT (ciphertext only)
    implementation("org.bouncycastle:bcprov-jdk18on:1.79") // Argon2id auth-proof verification only (never content keys)

    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation(kotlin("test"))
}

// Hibernate needs entity classes to be open + have a no-arg constructor.
allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
}

kotlin {
    compilerOptions {
        javaParameters = true
    }
}
