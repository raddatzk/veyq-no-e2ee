plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.allopen") version "2.3.21"
    id("io.quarkus") version "3.36.0"
}

apply(from = "gradle/openapi.gradle.kts")

repositories {
    mavenCentral()
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
    implementation("io.quarkus:quarkus-security") // central auth: SecurityIdentity + @RolesAllowed (cr-0012)
    implementation("io.quarkus:quarkus-hibernate-orm-panache-kotlin")
    implementation("io.quarkus:quarkus-scheduler") // @Scheduled cleanup jobs (first use: tenant-invite purge)
    implementation("io.quarkus:quarkus-jdbc-h2") // dev/test store (the default)
    implementation("io.quarkus:quarkus-jdbc-mariadb") // production store
    implementation("io.quarkus:quarkus-flyway")
    implementation("org.flywaydb:flyway-mysql") // Flyway 12 keeps MariaDB/MySQL support out of flyway-core
    implementation("software.amazon.awssdk:s3:2.31.78") // S3 blob store: vendor-neutral S3 client + presigner (ADR 0013)

    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation(kotlin("test"))
}

// Hibernate needs entity classes to be open + have a no-arg constructor.
allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.enterprise.context.ApplicationScoped")
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
