plugins {
    kotlin("jvm") version "2.3.10"
    id("io.quarkus") version "3.34.6"
    id("org.openapi.generator") version "7.21.0"
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
    implementation("io.quarkus:quarkus-rest")
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
    implementation("com.drewnoakes:metadata-extractor:2.20.0")

    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation("io.quarkus:quarkus-jdbc-h2")
    testImplementation(kotlin("test"))
}

val generatedOpenApiDir = layout.buildDirectory.dir("generated/openapi")
val generatedSettingsOpenApiDir = layout.buildDirectory.dir("generated/settings-openapi")

openApiGenerate {
    generatorName.set("jaxrs-spec")
    library.set("quarkus")
    inputSpec.set(layout.projectDirectory.file("../docs/api/explorer.openapi.yaml").asFile.absolutePath)
    outputDir.set(generatedOpenApiDir.get().asFile.absolutePath)
    apiPackage.set("com.lunva.api.generated.explorer.api")
    modelPackage.set("com.lunva.api.generated.explorer.model")
    invokerPackage.set("com.lunva.api.generated.explorer")
    configOptions.set(
        mapOf(
            "interfaceOnly" to "true",
            "useJakartaEe" to "true",
            "useSwaggerAnnotations" to "false",
            "useSwaggerV3Annotations" to "false",
            "useMicroProfileOpenAPIAnnotations" to "false",
            "openApiNullable" to "false",
            "hideGenerationTimestamp" to "true",
            "useTags" to "true",
            "useBeanValidation" to "false",
        )
    )
    globalProperties.set(
        mapOf(
            "apis" to "",
            "models" to "",
            "supportingFiles" to "false",
        )
    )
}

openApiValidate {
    inputSpec.set(layout.projectDirectory.file("../docs/api/explorer.openapi.yaml").asFile.absolutePath)
}

val openApiGenerateSettings by tasks.registering(org.openapitools.generator.gradle.plugin.tasks.GenerateTask::class) {
    generatorName.set("jaxrs-spec")
    library.set("quarkus")
    inputSpec.set(layout.projectDirectory.file("../docs/api/settings.openapi.yaml").asFile.absolutePath)
    outputDir.set(generatedSettingsOpenApiDir.get().asFile.absolutePath)
    apiPackage.set("com.lunva.api.generated.settings.api")
    modelPackage.set("com.lunva.api.generated.settings.model")
    invokerPackage.set("com.lunva.api.generated.settings")
    configOptions.set(
        mapOf(
            "interfaceOnly" to "true",
            "useJakartaEe" to "true",
            "useSwaggerAnnotations" to "false",
            "useSwaggerV3Annotations" to "false",
            "useMicroProfileOpenAPIAnnotations" to "false",
            "openApiNullable" to "false",
            "hideGenerationTimestamp" to "true",
            "useTags" to "true",
            "useBeanValidation" to "false",
        )
    )
    globalProperties.set(
        mapOf(
            "apis" to "",
            "models" to "",
            "supportingFiles" to "false",
        )
    )
    doFirst {
        delete(generatedSettingsOpenApiDir)
    }
}

val openApiValidateSettings by tasks.registering(org.openapitools.generator.gradle.plugin.tasks.ValidateTask::class) {
    inputSpec.set(layout.projectDirectory.file("../docs/api/settings.openapi.yaml").asFile.absolutePath)
}

tasks.named("openApiGenerate") {
    doFirst {
        delete(generatedOpenApiDir)
    }
}

sourceSets {
    main {
        java {
            srcDir(generatedOpenApiDir.map { it.dir("src/gen/java") })
            srcDir(generatedSettingsOpenApiDir.map { it.dir("src/gen/java") })
        }
    }
}

tasks.named("compileKotlin") {
    dependsOn(tasks.named("openApiGenerate"))
    dependsOn(openApiGenerateSettings)
}

tasks.named("compileJava") {
    dependsOn(tasks.named("openApiGenerate"))
    dependsOn(openApiGenerateSettings)
}

tasks.named("check") {
    dependsOn(tasks.named("openApiValidate"))
    dependsOn(openApiValidateSettings)
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
