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
    implementation("io.quarkus:quarkus-micrometer-registry-prometheus") // /q/metrics: JVM/GC/pool/HTTP gauges (counts+timings only, operator-blind) — keep on the internal network
    implementation("io.quarkus:quarkus-hibernate-orm-panache-kotlin")
    implementation("io.quarkus:quarkus-jdbc-h2") // dev/slice store (DB_KIND=h2, the default)
    implementation("io.quarkus:quarkus-jdbc-mariadb") // production store (DB_KIND=mariadb, see infra/docker-compose.yml)
    implementation("io.quarkus:quarkus-flyway")
    implementation("org.flywaydb:flyway-mysql") // Flyway 12 keeps MariaDB/MySQL support out of flyway-core — required whenever DB_KIND=mariadb (dev profile + the docker-compose deployment), else Flyway can't identify the DB at migrate-at-start
    implementation("io.quarkus:quarkus-scheduler") // periodic GC of expired sessions
    implementation("io.quarkus:quarkus-mailer") // outbound mail: email invites (the operator supplies SMTP)
    implementation("io.quarkus:quarkus-websockets-next") // operator-blind relay for live-journal CRDT (ciphertext only)
    implementation("org.bouncycastle:bcprov-jdk18on:1.79") // Argon2id auth-proof verification only (never content keys)
    implementation("io.minio:minio:8.5.17") // optional S3-compatible blob backend (AWS S3 / bundled MinIO / Garage); ciphertext only

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
