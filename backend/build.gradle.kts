plugins {
    kotlin("jvm") version "2.3.10"
    id("io.quarkus") version "3.34.6"
}

repositories {
    mavenCentral()
    maven {
        url = uri("https://jitpack.io")
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    implementation(enforcedPlatform("io.quarkus.platform:quarkus-bom:3.34.6"))
    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-kotlin")
    implementation("io.quarkus:quarkus-rest-jackson")
    implementation("io.quarkus:quarkus-hibernate-orm-panache")
    implementation("io.quarkus:quarkus-hibernate-orm-panache-kotlin")
    implementation("io.quarkus:quarkus-jdbc-mariadb")
    implementation("org.flywaydb:flyway-mysql")
    implementation("io.quarkus:quarkus-flyway")
    implementation("io.quarkus:quarkus-hibernate-validator")
    implementation("io.quarkus:quarkus-smallrye-health")
    implementation("io.quarkus:quarkus-security-jpa")
    implementation("io.quarkus:quarkus-scheduler")
    implementation("com.github.wosherco:jnanoid-enhanced:9819c115e7")

    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation("io.quarkus:quarkus-jdbc-h2")
    testImplementation(kotlin("test"))
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
