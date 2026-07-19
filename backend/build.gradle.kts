plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.allopen") version "2.3.21"
    id("io.quarkus") version "3.36.0"
    // Mutation testing — the trust chain's assertion-strength gate on the pure policy tier (ADR 0045 §2). Deliberately
    // WITHOUT the archived `pitest/pitest-kotlin` mutators (read-only since 2023, successor is the commercial arcmutate
    // → both barred by CLAUDE.md's no-unmaintained-deps + self-hostability invariants). The spike measures whether a
    // pure policy over non-null types needs that Kotlin noise filter at all. NOT wired into `check`: a heavier, scoped,
    // opt-in `./gradlew pitest` — mirrors web's `npm run mutation` staying out of `npm run check`.
    id("info.solidsoft.pitest") version "1.19.0"
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

    // PIT's JUnit-5 runner (our tests are JUnit Platform). 1.2.3 requires PIT >= 1.19.4 — pinned together below.
    pitest("org.pitest:pitest-junit5-plugin:1.2.3")
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

// The mutate scope is read from pitest.scope.json — the SAME manifest a scripts/ meta-check consumes, so the mutator
// and its "something was mutated" guard never drift (ADR 0045 §4; mirrors web/stryker.config.mjs reading
// stryker.mutate.json). PIT class globs, curated to the pure decision tier (never the dumb stores or the slices).
@Suppress("UNCHECKED_CAST")
val pitScope = groovy.json.JsonSlurper().parse(file("pitest.scope.json")) as Map<String, Any>

pitest {
    pitestVersion.set("1.19.4") // paired with pitest-junit5-plugin 1.2.3 (its floor)
    junit5PluginVersion.set("1.2.3")
    targetClasses.set(pitScope["targetClasses"] as List<String>)
    targetTests.set(pitScope["targetTests"] as List<String>)
    // Report-only for the spike: we are MEASURING the achievable score, not gating on it yet. A break threshold
    // (calibrated = high − margin, per the web pilot — NOT 100) is the CR's job once the number is measured.
    outputFormats.set(listOf("HTML", "XML"))
    timestampedReports.set(false) // stable report path: build/reports/pitest/{index.html,mutations.xml}
    failWhenNoMutations.set(true)  // an empty scope must fail loudly — the meta-check's build-time sibling
    verbose.set(false)
}
