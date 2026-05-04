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
    implementation("io.quarkus:quarkus-mailer")
    implementation("com.github.wosherco:jnanoid-enhanced:9819c115e7")
    implementation("com.drewnoakes:metadata-extractor:2.20.0")

    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation("io.quarkus:quarkus-jdbc-h2")
    testImplementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    testImplementation(kotlin("test"))
}

val generatedOpenApiDir = layout.buildDirectory.dir("generated/openapi")
val generatedSettingsOpenApiDir = layout.buildDirectory.dir("generated/settings-openapi")
val generatedAuthOpenApiDir = layout.buildDirectory.dir("generated/auth-openapi")
val generatedSetupOpenApiDir = layout.buildDirectory.dir("generated/setup-openapi")
val generatedUsersOpenApiDir = layout.buildDirectory.dir("generated/users-openapi")
val generatedTenantsOpenApiDir = layout.buildDirectory.dir("generated/tenants-openapi")
val generatedGroupsOpenApiDir = layout.buildDirectory.dir("generated/groups-openapi")
val generatedLibraryOpenApiDir = layout.buildDirectory.dir("generated/library-openapi")
val generatedServerOpenApiDir = layout.buildDirectory.dir("generated/server-openapi")
val generatedAuthModelsDir = layout.buildDirectory.dir("generated/auth-models")
val generatedSetupModelsDir = layout.buildDirectory.dir("generated/setup-models")
val generatedUsersModelsDir = layout.buildDirectory.dir("generated/users-models")
val generatedTenantsModelsDir = layout.buildDirectory.dir("generated/tenants-models")
val generatedGroupsModelsDir = layout.buildDirectory.dir("generated/groups-models")
val generatedLibraryModelsDir = layout.buildDirectory.dir("generated/library-models")
val generatedServerModelsDir = layout.buildDirectory.dir("generated/server-models")
val generatedSettingsModelsDir = layout.buildDirectory.dir("generated/settings-models")
val generatedExplorerModelsDir = layout.buildDirectory.dir("generated/explorer-models")

val openApiGeneratorOptions = mapOf(
    "interfaceOnly" to "true",
    "useJakartaEe" to "true",
    "useSwaggerAnnotations" to "false",
    "useSwaggerV3Annotations" to "false",
    "useMicroProfileOpenAPIAnnotations" to "false",
    "openApiNullable" to "false",
    "hideGenerationTimestamp" to "true",
    "useTags" to "true",
    "useBeanValidation" to "true",
)

val openApiGlobalProperties = mapOf(
    "apis" to "",
    "models" to "",
    "supportingFiles" to "false",
)

val openApiApiOnlyGlobalProperties = mapOf(
    "apis" to "",
    "supportingFiles" to "false",
)

val openApiModelOnlyGlobalProperties = mapOf(
    "models" to "",
    "supportingFiles" to "false",
)

fun registerOpenApiGenerateTask(
    taskName: String,
    specPath: String,
    outputDirectory: Provider<Directory>,
    apiPackageName: String,
    modelPackageName: String,
    mappedModels: Map<String, String> = emptyMap(),
    extraConfigOptions: Map<String, String> = emptyMap(),
    generatedGlobalProperties: Map<String, String> = openApiApiOnlyGlobalProperties,
) = tasks.registering(org.openapitools.generator.gradle.plugin.tasks.GenerateTask::class) {
    generatorName.set("jaxrs-spec")
    library.set("quarkus")
    inputSpec.set(layout.projectDirectory.file(specPath).asFile.absolutePath)
    outputDir.set(outputDirectory.get().asFile.absolutePath)
    apiPackage.set(apiPackageName)
    modelPackage.set(modelPackageName)
    invokerPackage.set(apiPackageName.removeSuffix(".api"))
    configOptions.set(openApiGeneratorOptions + extraConfigOptions)
    globalProperties.set(generatedGlobalProperties)
    importMappings.set(mappedModels)
    doFirst {
        delete(outputDirectory)
    }
}

fun registerOpenApiValidateTask(
    taskName: String,
    specPath: String,
) = tasks.registering(org.openapitools.generator.gradle.plugin.tasks.ValidateTask::class) {
    inputSpec.set(layout.projectDirectory.file(specPath).asFile.absolutePath)
}

fun registerOpenApiKotlinModelGenerateTask(
    taskName: String,
    specPath: String,
    outputDirectory: Provider<Directory>,
    packageNameValue: String,
    modelPackageName: String,
) = tasks.registering(org.openapitools.generator.gradle.plugin.tasks.GenerateTask::class) {
    generatorName.set("kotlin")
    inputSpec.set(layout.projectDirectory.file(specPath).asFile.absolutePath)
    outputDir.set(outputDirectory.get().asFile.absolutePath)
    packageName.set(packageNameValue)
    modelPackage.set(modelPackageName)
    globalProperties.set(openApiModelOnlyGlobalProperties)
    configOptions.set(
        mapOf(
            "serializationLibrary" to "jackson",
            "dateLibrary" to "java8",
            "enumPropertyNaming" to "original",
            "collectionType" to "list",
        )
    )
    doFirst {
        delete(outputDirectory)
    }
}

openApiGenerate {
    generatorName.set("jaxrs-spec")
    library.set("quarkus")
    inputSpec.set(layout.projectDirectory.file("../docs/api/explorer.openapi.yaml").asFile.absolutePath)
    outputDir.set(generatedOpenApiDir.get().asFile.absolutePath)
    apiPackage.set("com.lunva.api.generated.explorer.api")
    modelPackage.set("com.lunva.api.generated.explorer.model")
    invokerPackage.set("com.lunva.api.generated.explorer")
    configOptions.set(
        openApiGeneratorOptions
    )
    globalProperties.set(
        openApiApiOnlyGlobalProperties
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
        openApiGeneratorOptions
    )
    globalProperties.set(
        openApiApiOnlyGlobalProperties
    )
    doFirst {
        delete(generatedSettingsOpenApiDir)
    }
}

val openApiValidateSettings by tasks.registering(org.openapitools.generator.gradle.plugin.tasks.ValidateTask::class) {
    inputSpec.set(layout.projectDirectory.file("../docs/api/settings.openapi.yaml").asFile.absolutePath)
}

val openApiGenerateAuth by registerOpenApiGenerateTask(
    "openApiGenerateAuth",
    "../docs/api/auth.openapi.yaml",
    generatedAuthOpenApiDir,
    "com.lunva.api.generated.auth.api",
    "com.lunva.api.generated.auth.model",
    extraConfigOptions = mapOf("returnResponse" to "true"),
)
val openApiGenerateSetup by registerOpenApiGenerateTask(
    "openApiGenerateSetup",
    "../docs/api/setup.openapi.yaml",
    generatedSetupOpenApiDir,
    "com.lunva.api.generated.setup.api",
    "com.lunva.api.generated.setup.model",
)
val openApiGenerateUsers by registerOpenApiGenerateTask(
    "openApiGenerateUsers",
    "../docs/api/users.openapi.yaml",
    generatedUsersOpenApiDir,
    "com.lunva.api.generated.users.api",
    "com.lunva.api.generated.users.model",
    extraConfigOptions = mapOf("returnResponse" to "true"),
)
val openApiGenerateTenants by registerOpenApiGenerateTask(
    "openApiGenerateTenants",
    "../docs/api/tenants.openapi.yaml",
    generatedTenantsOpenApiDir,
    "com.lunva.api.generated.tenants.api",
    "com.lunva.api.generated.tenants.model",
)
val openApiGenerateGroups by registerOpenApiGenerateTask(
    "openApiGenerateGroups",
    "../docs/api/groups.openapi.yaml",
    generatedGroupsOpenApiDir,
    "com.lunva.api.generated.groups.api",
    "com.lunva.api.generated.groups.model",
)
val openApiGenerateLibrary by registerOpenApiGenerateTask(
    "openApiGenerateLibrary",
    "../docs/api/library.openapi.yaml",
    generatedLibraryOpenApiDir,
    "com.lunva.api.generated.library.api",
    "com.lunva.api.generated.library.model",
    mapOf(
        "ExplorerAlbum" to "com.lunva.api.generated.explorer.model.ExplorerAlbum",
        "ExplorerFolder" to "com.lunva.api.generated.explorer.model.ExplorerFolder",
    ),
    extraConfigOptions = mapOf("returnResponse" to "true"),
)
val openApiGenerateServer by registerOpenApiGenerateTask(
    "openApiGenerateServer",
    "../docs/api/server.openapi.yaml",
    generatedServerOpenApiDir,
    "com.lunva.api.generated.server.api",
    "com.lunva.api.generated.server.model",
)

val openApiGenerateAuthModels by registerOpenApiKotlinModelGenerateTask(
    "openApiGenerateAuthModels",
    "../docs/api/auth.openapi.yaml",
    generatedAuthModelsDir,
    "com.lunva.api.generated.auth",
    "com.lunva.api.generated.auth.model",
)
val openApiGenerateSetupModels by registerOpenApiKotlinModelGenerateTask(
    "openApiGenerateSetupModels",
    "../docs/api/setup.openapi.yaml",
    generatedSetupModelsDir,
    "com.lunva.api.generated.setup",
    "com.lunva.api.generated.setup.model",
)
val openApiGenerateUsersModels by registerOpenApiKotlinModelGenerateTask(
    "openApiGenerateUsersModels",
    "../docs/api/users.openapi.yaml",
    generatedUsersModelsDir,
    "com.lunva.api.generated.users",
    "com.lunva.api.generated.users.model",
)
val openApiGenerateTenantsModels by registerOpenApiKotlinModelGenerateTask(
    "openApiGenerateTenantsModels",
    "../docs/api/tenants.openapi.yaml",
    generatedTenantsModelsDir,
    "com.lunva.api.generated.tenants",
    "com.lunva.api.generated.tenants.model",
)
val openApiGenerateGroupsModels by registerOpenApiKotlinModelGenerateTask(
    "openApiGenerateGroupsModels",
    "../docs/api/groups.openapi.yaml",
    generatedGroupsModelsDir,
    "com.lunva.api.generated.groups",
    "com.lunva.api.generated.groups.model",
)
val openApiGenerateLibraryModels by registerOpenApiKotlinModelGenerateTask(
    "openApiGenerateLibraryModels",
    "../docs/api/library.openapi.yaml",
    generatedLibraryModelsDir,
    "com.lunva.api.generated.library",
    "com.lunva.api.generated.library.model",
)
val openApiGenerateServerModels by registerOpenApiKotlinModelGenerateTask(
    "openApiGenerateServerModels",
    "../docs/api/server.openapi.yaml",
    generatedServerModelsDir,
    "com.lunva.api.generated.server",
    "com.lunva.api.generated.server.model",
)
val openApiGenerateSettingsModels by registerOpenApiKotlinModelGenerateTask(
    "openApiGenerateSettingsModels",
    "../docs/api/settings.openapi.yaml",
    generatedSettingsModelsDir,
    "com.lunva.api.generated.settings",
    "com.lunva.api.generated.settings.model",
)
val openApiGenerateExplorerModels by registerOpenApiKotlinModelGenerateTask(
    "openApiGenerateExplorerModels",
    "../docs/api/explorer.openapi.yaml",
    generatedExplorerModelsDir,
    "com.lunva.api.generated.explorer",
    "com.lunva.api.generated.explorer.model",
)

val openApiValidateAuth by registerOpenApiValidateTask("openApiValidateAuth", "../docs/api/auth.openapi.yaml")
val openApiValidateSetup by registerOpenApiValidateTask("openApiValidateSetup", "../docs/api/setup.openapi.yaml")
val openApiValidateUsers by registerOpenApiValidateTask("openApiValidateUsers", "../docs/api/users.openapi.yaml")
val openApiValidateTenants by registerOpenApiValidateTask("openApiValidateTenants", "../docs/api/tenants.openapi.yaml")
val openApiValidateGroups by registerOpenApiValidateTask("openApiValidateGroups", "../docs/api/groups.openapi.yaml")
val openApiValidateLibrary by registerOpenApiValidateTask("openApiValidateLibrary", "../docs/api/library.openapi.yaml")
val openApiValidateServer by registerOpenApiValidateTask("openApiValidateServer", "../docs/api/server.openapi.yaml")

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
            srcDir(generatedAuthOpenApiDir.map { it.dir("src/gen/java") })
            srcDir(generatedSetupOpenApiDir.map { it.dir("src/gen/java") })
            srcDir(generatedUsersOpenApiDir.map { it.dir("src/gen/java") })
            srcDir(generatedTenantsOpenApiDir.map { it.dir("src/gen/java") })
            srcDir(generatedGroupsOpenApiDir.map { it.dir("src/gen/java") })
            srcDir(generatedLibraryOpenApiDir.map { it.dir("src/gen/java") })
            srcDir(generatedServerOpenApiDir.map { it.dir("src/gen/java") })
        }
    }
}

kotlin {
    sourceSets {
        main {
            kotlin.srcDir(generatedAuthModelsDir.map { it.dir("src/main/kotlin") })
            kotlin.srcDir(generatedSetupModelsDir.map { it.dir("src/main/kotlin") })
            kotlin.srcDir(generatedUsersModelsDir.map { it.dir("src/main/kotlin") })
            kotlin.srcDir(generatedTenantsModelsDir.map { it.dir("src/main/kotlin") })
            kotlin.srcDir(generatedGroupsModelsDir.map { it.dir("src/main/kotlin") })
            kotlin.srcDir(generatedLibraryModelsDir.map { it.dir("src/main/kotlin") })
            kotlin.srcDir(generatedServerModelsDir.map { it.dir("src/main/kotlin") })
            kotlin.srcDir(generatedSettingsModelsDir.map { it.dir("src/main/kotlin") })
            kotlin.srcDir(generatedExplorerModelsDir.map { it.dir("src/main/kotlin") })
        }
    }
}

tasks.named("compileKotlin") {
    dependsOn(tasks.named("openApiGenerate"))
    dependsOn(openApiGenerateSettings)
    dependsOn(openApiGenerateAuth)
    dependsOn(openApiGenerateSetup)
    dependsOn(openApiGenerateUsers)
    dependsOn(openApiGenerateTenants)
    dependsOn(openApiGenerateGroups)
    dependsOn(openApiGenerateLibrary)
    dependsOn(openApiGenerateServer)
    dependsOn(openApiGenerateAuthModels)
    dependsOn(openApiGenerateSetupModels)
    dependsOn(openApiGenerateUsersModels)
    dependsOn(openApiGenerateTenantsModels)
    dependsOn(openApiGenerateGroupsModels)
    dependsOn(openApiGenerateLibraryModels)
    dependsOn(openApiGenerateServerModels)
    dependsOn(openApiGenerateSettingsModels)
    dependsOn(openApiGenerateExplorerModels)
}

tasks.named("compileJava") {
    dependsOn(tasks.named("openApiGenerate"))
    dependsOn(openApiGenerateSettings)
    dependsOn(openApiGenerateAuth)
    dependsOn(openApiGenerateSetup)
    dependsOn(openApiGenerateUsers)
    dependsOn(openApiGenerateTenants)
    dependsOn(openApiGenerateGroups)
    dependsOn(openApiGenerateLibrary)
    dependsOn(openApiGenerateServer)
    dependsOn(openApiGenerateAuthModels)
    dependsOn(openApiGenerateSetupModels)
    dependsOn(openApiGenerateUsersModels)
    dependsOn(openApiGenerateTenantsModels)
    dependsOn(openApiGenerateGroupsModels)
    dependsOn(openApiGenerateLibraryModels)
    dependsOn(openApiGenerateServerModels)
    dependsOn(openApiGenerateSettingsModels)
    dependsOn(openApiGenerateExplorerModels)
}

tasks.named("check") {
    dependsOn(tasks.named("openApiValidate"))
    dependsOn(openApiValidateSettings)
    dependsOn(openApiValidateAuth)
    dependsOn(openApiValidateSetup)
    dependsOn(openApiValidateUsers)
    dependsOn(openApiValidateTenants)
    dependsOn(openApiValidateGroups)
    dependsOn(openApiValidateLibrary)
    dependsOn(openApiValidateServer)
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
