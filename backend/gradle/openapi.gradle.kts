buildscript {
    repositories { mavenCentral() }
    dependencies {
        classpath("org.openapitools:openapi-generator:7.21.0")
    }
}

// Greenfield spec-first OpenAPI generation: JAX-RS interfaces + Kotlin models from docs/api specs.
// Slice Resources implement the generated `*Api` interfaces (one tag per use-case).

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

val apiPackageName = "com.veyq.app.generated.api"
val modelPackageName = "com.veyq.app.generated.model"
val invokerPackageName = "com.veyq.app.generated"

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

val openApiApiOnlyGlobalProperties = mapOf("apis" to "", "supportingFiles" to "false")
val openApiModelOnlyGlobalProperties = mapOf("models" to "", "supportingFiles" to "false")

// Only object schemas that are actually generated as classes belong here. String-typed schemas
// (e.g. CryptoSuiteId) are inlined as kotlin.String and must NOT be mapped.
val sharedSchemaMappings = mapOf(
    "ErrorResponse" to "$modelPackageName.ErrorResponse",
    "ServerRole" to "$modelPackageName.ServerRole",
    "UserSummary" to "$modelPackageName.UserSummary",
    "IdentityPublicKey" to "$modelPackageName.IdentityPublicKey",
    "Blob" to "$modelPackageName.Blob",
    "MasterKeyWrap" to "$modelPackageName.MasterKeyWrap",
    "KemPublicKey" to "$modelPackageName.KemPublicKey",
    "AuthVerifier" to "$modelPackageName.AuthVerifier",
)

val openApiSpecs = listOf(
    OpenApiSpec("common", "../docs/api/common.openapi.yaml", generateApi = false),
    OpenApiSpec("setup", "../docs/api/setup.openapi.yaml", extraApiConfigOptions = mapOf("returnResponse" to "true")),
    OpenApiSpec("auth", "../docs/api/auth.openapi.yaml", extraApiConfigOptions = mapOf("returnResponse" to "true")),
    OpenApiSpec("tenants", "../docs/api/tenants.openapi.yaml", extraApiConfigOptions = mapOf("returnResponse" to "true")),
    OpenApiSpec("keys", "../docs/api/keys.openapi.yaml", extraApiConfigOptions = mapOf("returnResponse" to "true")),
    OpenApiSpec("content", "../docs/api/content.openapi.yaml", extraApiConfigOptions = mapOf("returnResponse" to "true")),
    OpenApiSpec("identity", "../docs/api/identity.openapi.yaml", extraApiConfigOptions = mapOf("returnResponse" to "true")),
    OpenApiSpec("spaces", "../docs/api/spaces.openapi.yaml", extraApiConfigOptions = mapOf("returnResponse" to "true")),
    OpenApiSpec("memory", "../docs/api/memory.openapi.yaml", extraApiConfigOptions = mapOf("returnResponse" to "true")),
    OpenApiSpec("media", "../docs/api/media.openapi.yaml", extraApiConfigOptions = mapOf("returnResponse" to "true")),
)

val yamlMapper = com.fasterxml.jackson.databind.ObjectMapper(
    com.fasterxml.jackson.dataformat.yaml.YAMLFactory()
)

@Suppress("UNCHECKED_CAST")
fun readYamlMap(specPath: String): MutableMap<String, Any?> =
    yamlMapper.readValue(layout.projectDirectory.file(specPath).asFile, MutableMap::class.java) as MutableMap<String, Any?>

@Suppress("UNCHECKED_CAST")
fun MutableMap<String, Any?>.mutableMapAt(key: String): MutableMap<String, Any?> =
    getOrPut(key) { linkedMapOf<String, Any?>() } as MutableMap<String, Any?>

fun rewriteExternalComponentRefs(value: Any?): Any? =
    when (value) {
        is String -> value
            .replace("./common.openapi.yaml#/components/schemas/", "#/components/schemas/")
            .replace("./common.openapi.yaml#/components/responses/", "#/components/responses/")
            .replace("./common.openapi.yaml#/components/parameters/", "#/components/parameters/")
        is Map<*, *> -> {
            val rewritten = linkedMapOf<String, Any?>()
            value.forEach { (k, v) -> rewritten[k.toString()] = rewriteExternalComponentRefs(v) }
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
    val sharedComponentSources = listOf(readYamlMap("../docs/api/common.openapi.yaml"))
    val bundled = rewriteExternalComponentRefs(readYamlMap(spec.specPath)) as MutableMap<String, Any?>
    val bundledComponents = bundled.mutableMapAt("components")
    listOf("schemas", "responses", "parameters", "requestBodies").forEach { componentKey ->
        val targetComponents = bundledComponents.mutableMapAt(componentKey)
        sharedComponentSources.forEach { source ->
            val sourceComponents = (source.mutableMapAt("components")[componentKey] as? Map<String, Any?>) ?: emptyMap()
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
    }
    org.openapitools.codegen.DefaultGenerator().opts(configurator.toClientOptInput()).generate()
}

tasks.register("openApiGenerateSpecs") {
    group = "openapi tools"
    description = "Generates backend OpenAPI interfaces + Kotlin models from docs/api specs."
    inputs.files(openApiSpecs.map { layout.projectDirectory.file(it.specPath) })
    outputs.dirs(generatedApiDir, generatedModelsDir, bundledSpecDir)
    doFirst { delete(generatedApiDir, generatedModelsDir, bundledSpecDir) }
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

extensions.configure<org.gradle.api.tasks.SourceSetContainer>("sourceSets") {
    named("main") { java { srcDir(generatedApiDir.map { it.dir("src/gen/java") }) } }
}

@Suppress("UNCHECKED_CAST")
val kotlinSourceSets = extensions.getByName("kotlin").javaClass.methods
    .single { it.name == "getSourceSets" && it.parameterCount == 0 }
    .invoke(extensions.getByName("kotlin")) as org.gradle.api.NamedDomainObjectContainer<Any>

kotlinSourceSets.named("main") {
    val kotlinSourceDirectorySet = javaClass.methods
        .single { it.name == "getKotlin" && it.parameterCount == 0 }.invoke(this)
    val srcDir = kotlinSourceDirectorySet.javaClass.methods
        .first { it.name == "srcDir" && it.parameterCount == 1 }
    srcDir.invoke(kotlinSourceDirectorySet, generatedModelsDir.map { it.dir("src/main/kotlin") })
}

tasks.named("compileKotlin") { dependsOn(tasks.named("openApiGenerateSpecs")) }
tasks.named("compileJava") { dependsOn(tasks.named("openApiGenerateSpecs")) }
