buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.openapitools:openapi-generator:7.21.0")
    }
}

data class OpenApiSpec(
    val domain: String,
    val specPath: String,
    val generateApi: Boolean = true,
    val generateModels: Boolean = true,
    val extraApiConfigOptions: Map<String, String> = emptyMap(),
)

val generatedApiDir = layout.buildDirectory.dir("generated/openapi-api")
val generatedModelsDir = layout.buildDirectory.dir("generated/openapi-models")
val bundledSpecDir = layout.buildDirectory.dir("generated/openapi-bundled")

val apiPackageName = "com.veyq.api.generated.api"
val modelPackageName = "com.veyq.api.generated.model"
val invokerPackageName = "com.veyq.api.generated"

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

val openApiApiOnlyGlobalProperties = mapOf(
    "apis" to "",
    "supportingFiles" to "false",
)

val openApiModelOnlyGlobalProperties = mapOf(
    "models" to "",
    "supportingFiles" to "false",
)

val sharedSchemaMappings = mapOf(
    "ServerRole" to "$modelPackageName.ServerRole",
    "ErrorResponse" to "$modelPackageName.ErrorResponse",
    "LoginSession" to "$modelPackageName.LoginSession",
    "UserSummary" to "$modelPackageName.UserSummary",
    "PublicUserSummary" to "$modelPackageName.PublicUserSummary",
)

val sharedPrimitiveTypeMappings = mapOf(
    "EmailAddress" to "kotlin.String",
    "DisplayName" to "kotlin.String",
    "Name" to "kotlin.String",
    "Password" to "kotlin.String",
)

val openApiSpecs = listOf(
    OpenApiSpec("common", "../docs/api/common.openapi.yaml", generateApi = false),
    OpenApiSpec("settings", "../docs/api/settings.openapi.yaml"),
    OpenApiSpec("auth", "../docs/api/auth.openapi.yaml", extraApiConfigOptions = mapOf("returnResponse" to "true")),
    OpenApiSpec("setup", "../docs/api/setup.openapi.yaml", extraApiConfigOptions = mapOf("returnResponse" to "true")),
    OpenApiSpec("users", "../docs/api/users.openapi.yaml", extraApiConfigOptions = mapOf("returnResponse" to "true")),
    OpenApiSpec("tenants", "../docs/api/tenants.openapi.yaml"),
    OpenApiSpec("groups", "../docs/api/groups.openapi.yaml"),
    OpenApiSpec("memories", "../docs/api/memories.openapi.yaml", extraApiConfigOptions = mapOf("returnResponse" to "true")),
    OpenApiSpec("shares", "../docs/api/shares.openapi.yaml", extraApiConfigOptions = mapOf("returnResponse" to "true")),
    OpenApiSpec("server", "../docs/api/server.openapi.yaml"),
    OpenApiSpec("frontend", "../docs/api/frontend.openapi.yaml"),
)

val yamlMapper = com.fasterxml.jackson.databind.ObjectMapper(
    com.fasterxml.jackson.dataformat.yaml.YAMLFactory()
)

@Suppress("UNCHECKED_CAST")
fun readYamlMap(specPath: String): MutableMap<String, Any?> =
    yamlMapper.readValue(
        layout.projectDirectory.file(specPath).asFile,
        MutableMap::class.java,
    ) as MutableMap<String, Any?>

@Suppress("UNCHECKED_CAST")
fun MutableMap<String, Any?>.mutableMapAt(key: String): MutableMap<String, Any?> =
    getOrPut(key) { linkedMapOf<String, Any?>() } as MutableMap<String, Any?>

@Suppress("UNCHECKED_CAST")
fun rewriteExternalComponentRefs(value: Any?): Any? =
    when (value) {
        is String -> value
            .replace("./common.openapi.yaml#/components/schemas/", "#/components/schemas/")
            .replace("./common.openapi.yaml#/components/responses/", "#/components/responses/")
            .replace("./common.openapi.yaml#/components/parameters/", "#/components/parameters/")
            .replace("./tenants.openapi.yaml#/components/schemas/", "#/components/schemas/")
            .replace("./tenants.openapi.yaml#/components/responses/", "#/components/responses/")
            .replace("./tenants.openapi.yaml#/components/parameters/", "#/components/parameters/")
            .replace("./users.openapi.yaml#/components/schemas/", "#/components/schemas/")
            .replace("./users.openapi.yaml#/components/responses/", "#/components/responses/")
            .replace("./users.openapi.yaml#/components/parameters/", "#/components/parameters/")
            .replace("./memories.openapi.yaml#/components/schemas/", "#/components/schemas/")
            .replace("./memories.openapi.yaml#/components/responses/", "#/components/responses/")
            .replace("./memories.openapi.yaml#/components/requestBodies/", "#/components/requestBodies/")
            .replace("./memories.openapi.yaml#/components/parameters/", "#/components/parameters/")
        is MutableMap<*, *> -> {
            val rewritten = linkedMapOf<String, Any?>()
            value.forEach { (entryKey, entryValue) ->
                rewritten[entryKey.toString()] = rewriteExternalComponentRefs(entryValue)
            }
            rewritten
        }
        is Map<*, *> -> {
            val rewritten = linkedMapOf<String, Any?>()
            value.forEach { (entryKey, entryValue) ->
                rewritten[entryKey.toString()] = rewriteExternalComponentRefs(entryValue)
            }
            rewritten
        }
        is List<*> -> value.map(::rewriteExternalComponentRefs)
        else -> value
    }

@Suppress("UNCHECKED_CAST")
fun bundleOpenApiSpec(spec: OpenApiSpec): String {
    if (spec.domain == "common") {
        return layout.projectDirectory.file(spec.specPath).asFile.absolutePath
    }

    val sharedComponentSources = listOf(
        readYamlMap("../docs/api/common.openapi.yaml"),
        readYamlMap("../docs/api/tenants.openapi.yaml"),
        readYamlMap("../docs/api/users.openapi.yaml"),
        readYamlMap("../docs/api/memories.openapi.yaml"),
    )
    val bundled = rewriteExternalComponentRefs(readYamlMap(spec.specPath)) as MutableMap<String, Any?>
    val bundledComponents = bundled.mutableMapAt("components")

    listOf("schemas", "responses", "parameters", "requestBodies").forEach { componentKey ->
        val targetComponents = bundledComponents.mutableMapAt(componentKey)
        sharedComponentSources.forEach { source ->
            val sourceComponents = source.mutableMapAt("components")[componentKey] as? Map<String, Any?> ?: emptyMap()
            sourceComponents.forEach { (name, component) ->
                targetComponents.putIfAbsent(name, rewriteExternalComponentRefs(component))
            }
        }
    }

    val bundledFile = bundledSpecDir.get().file("${spec.domain}.openapi.yaml").asFile
    bundledFile.parentFile.mkdirs()
    yamlMapper.writeValue(bundledFile, bundled)
    return bundledFile.absolutePath
}

fun runOpenApiGenerator(
    generatorName: String,
    inputSpecPath: String,
    outputDirectory: Provider<Directory>,
    globalProperties: Map<String, String>,
    configOptions: Map<String, String>,
    library: String? = null,
    apiPackage: String? = null,
    modelPackage: String? = null,
    invokerPackage: String? = null,
    packageName: String? = null,
    useSharedSchemaMappings: Boolean = true,
) {
    val configurator = org.openapitools.codegen.config.CodegenConfigurator()
        .setGeneratorName(generatorName)
        .setInputSpec(inputSpecPath)
        .setOutputDir(outputDirectory.get().asFile.absolutePath)
        .setGlobalProperties(globalProperties)
        .setAdditionalProperties(configOptions.mapValues { it.value })

    library?.let(configurator::setLibrary)
    apiPackage?.let(configurator::setApiPackage)
    modelPackage?.let(configurator::setModelPackage)
    invokerPackage?.let(configurator::setInvokerPackage)
    packageName?.let(configurator::setPackageName)
    if (useSharedSchemaMappings) {
        configurator.setImportMappings(sharedSchemaMappings)
        configurator.setSchemaMappings(sharedSchemaMappings)
        configurator.setTypeMappings(sharedPrimitiveTypeMappings)
    }

    org.openapitools.codegen.DefaultGenerator()
        .opts(configurator.toClientOptInput())
        .generate()
}

tasks.register("openApiGenerateSpecs") {
    group = "openapi tools"
    description = "Generates every backend OpenAPI interface and Kotlin model from docs/api specs."
    inputs.files(openApiSpecs.map { layout.projectDirectory.file(it.specPath) })
    outputs.dirs(generatedApiDir, generatedModelsDir, bundledSpecDir)
    doFirst {
        delete(generatedApiDir, generatedModelsDir, bundledSpecDir)
    }
    doLast {
        val bundledSpecs = openApiSpecs.associateWith(::bundleOpenApiSpec)
        openApiSpecs.filter { it.generateModels }.forEach { spec ->
            runOpenApiGenerator(
                "kotlin",
                bundledSpecs.getValue(spec),
                generatedModelsDir,
                openApiModelOnlyGlobalProperties,
                mapOf(
                    "serializationLibrary" to "jackson",
                    "dateLibrary" to "java8",
                    "enumPropertyNaming" to "original",
                    "collectionType" to "list",
                ),
                packageName = invokerPackageName,
                modelPackage = modelPackageName,
                useSharedSchemaMappings = spec.domain != "common",
            )
        }
        openApiSpecs.filter { it.generateApi }.forEach { spec ->
            runOpenApiGenerator(
                "jaxrs-spec",
                bundledSpecs.getValue(spec),
                generatedApiDir,
                openApiApiOnlyGlobalProperties,
                openApiGeneratorOptions + spec.extraApiConfigOptions,
                library = "quarkus",
                apiPackage = apiPackageName,
                modelPackage = modelPackageName,
                invokerPackage = invokerPackageName,
            )
        }
    }
}

tasks.register("openApiValidateSpecs") {
    group = "openapi tools"
    description = "Validates every backend OpenAPI spec in docs/api."
    inputs.files(openApiSpecs.map { layout.projectDirectory.file(it.specPath) })
    doLast {
        openApiSpecs.forEach { spec ->
            val specFile = layout.projectDirectory.file(spec.specPath).asFile.absolutePath
            val result = io.swagger.v3.parser.OpenAPIV3Parser().readLocation(specFile, null, null)
            if (result.messages.isNotEmpty()) {
                throw GradleException(
                    "OpenAPI spec ${spec.domain} is invalid:\n${result.messages.joinToString("\n")}"
                )
            }
        }
    }
}

extensions.configure<org.gradle.api.tasks.SourceSetContainer>("sourceSets") {
    named("main") {
        java {
            srcDir(generatedApiDir.map { it.dir("src/gen/java") })
        }
    }
}

@Suppress("UNCHECKED_CAST")
val kotlinSourceSets = extensions
    .getByName("kotlin")
    .javaClass
    .methods
    .single { it.name == "getSourceSets" && it.parameterCount == 0 }
    .invoke(extensions.getByName("kotlin")) as org.gradle.api.NamedDomainObjectContainer<Any>

kotlinSourceSets.named("main") {
    val kotlinSourceDirectorySet = javaClass
        .methods
        .single { it.name == "getKotlin" && it.parameterCount == 0 }
        .invoke(this)
    val srcDir = kotlinSourceDirectorySet.javaClass
        .methods
        .first { it.name == "srcDir" && it.parameterCount == 1 }

    srcDir.invoke(kotlinSourceDirectorySet, generatedModelsDir.map { it.dir("src/main/kotlin") })
}

tasks.named("compileKotlin") {
    dependsOn(tasks.named("openApiGenerateSpecs"))
}

tasks.named("compileJava") {
    dependsOn(tasks.named("openApiGenerateSpecs"))
}

tasks.named("check") {
    dependsOn(tasks.named("openApiValidateSpecs"))
}
