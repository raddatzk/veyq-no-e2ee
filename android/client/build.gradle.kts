// veyq `:client` — pure Kotlin/JVM data layer (cr-0016). The contracts-generated OpenAPI clients
// (setup/auth/account) + the session/vault/login/unlock orchestration. Pure JVM (no Android SDK) so it
// tests via `:client:test` and is reusable by a future headless JVM agent (ADR 0010).
//
// API-first: the three Kotlin clients are GENERATED from contracts/api/{setup,auth,account}.openapi.yaml
// (never hand-written DTOs). The specs have colliding component names (MasterKeyWrap, ErrorResponse), so
// each spec generates into its OWN package (com.veyq.api.{setup,auth,account}) — the Android twin of
// Apple's three modules.
import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

plugins {
    // versions pinned in the root build script (shared classloader)
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.openapi.generator")
}

// The contract specs live at the repo root (android/ is the Gradle root, so walk up one).
val contractsDir = rootDir.parentFile.resolve("contracts/api")
val specs = listOf("setup", "auth", "account", "scopes", "content")
val generatedRoot = layout.buildDirectory.dir("generated/openapi")
val sanitizedSpecsDir = layout.buildDirectory.dir("sanitized-specs")

// The specs carry a backend-only `x-field-extra-annotation` vendor extension (Quarkus bean-validation +
// trimming-deserializer hints). The Kotlin client generator honours it and would emit
// `@field:jakarta.validation...` annotations into the DTOs — jakarta has no place in a pure-JVM client.
// Strip those (single-line) entries into a sanitized copy; the shared contract itself is never edited.
val sanitizeSpecs = tasks.register<Copy>("sanitizeSpecs") {
    from(contractsDir) { include(specs.map { "$it.openapi.yaml" }) }
    into(sanitizedSpecsDir)
    filteringCharset = "UTF-8"
    filter { line -> if (line.trimStart().startsWith("x-field-extra-annotation:")) null else line }
}

// One GenerateTask per spec → its own package, its own output dir.
val generateTasks = specs.map { name ->
    tasks.register<GenerateTask>("generate${name.replaceFirstChar { it.uppercase() }}Client") {
        group = "openapi"
        dependsOn(sanitizeSpecs)
        generatorName.set("kotlin")
        library.set("jvm-okhttp4")
        inputSpec.set(sanitizedSpecsDir.get().file("$name.openapi.yaml").asFile.path)
        outputDir.set(generatedRoot.get().dir(name).asFile.path)
        packageName.set("com.veyq.api.$name")
        apiPackage.set("com.veyq.api.$name.api")
        modelPackage.set("com.veyq.api.$name.model")
        configOptions.set(
            mapOf(
                "serializationLibrary" to "kotlinx_serialization",
                "dateLibrary" to "string",
                "omitGradleWrapper" to "true",
            ),
        )
    }
}

// Compile the generated Kotlin alongside our own sources; (re)generate before compiling.
sourceSets.main {
    specs.forEach { name ->
        kotlin.srcDir(generatedRoot.get().dir(name).dir("src/main/kotlin"))
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(generateTasks)
}

dependencies {
    implementation(project(":crypto"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // The generated okhttp4 Kotlin client is suspend-based (its ApiClient uses suspendCancellableCoroutine),
    // so the orchestration is suspend too — the Android twin of Apple's async data layer.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    testImplementation(kotlin("test"))
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}
