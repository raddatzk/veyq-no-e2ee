package com.lunva.api.contract

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.lunva.api.auth.AuthResource
import com.lunva.api.generated.api.AuthApi
import com.lunva.api.generated.api.ExplorerApi
import com.lunva.api.generated.api.GroupsApi
import com.lunva.api.generated.api.AlbumsApi
import com.lunva.api.generated.api.FoldersApi
import com.lunva.api.generated.api.MediaApi
import com.lunva.api.generated.api.MediaUploadsApi
import com.lunva.api.generated.api.ServerApi
import com.lunva.api.generated.api.SettingsApi
import com.lunva.api.generated.api.SetupApi
import com.lunva.api.generated.api.TenantsApi
import com.lunva.api.generated.api.UsersApi
import com.lunva.api.library.album.AlbumResource
import com.lunva.api.library.explorer.ExplorerResource
import com.lunva.api.library.explorer.SettingsExplorerResource
import com.lunva.api.library.folder.FolderResource
import com.lunva.api.library.media.MediaResource
import com.lunva.api.library.media.MediaUploadResource
import com.lunva.api.settings.SettingsResource
import com.lunva.api.setup.SetupResource
import com.lunva.api.tenant.TenantResource
import com.lunva.api.usermgmt.group.GroupResource
import com.lunva.api.usermgmt.user.UserResource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

class OpenApiContractTest {
    private val mapper = ObjectMapper(YAMLFactory())
    private val httpMethods = setOf("get", "post", "put", "patch", "delete")
    private val statuses = setOf("implemented", "partial", "planned")

    @Test
    fun `openapi operations carry descriptions examples preconditions and implementation status`() {
        operations().forEach { operation ->
            assertThat(operation.summary)
                .describedAs("${operation.method.uppercase()} ${operation.path} must have a summary")
                .isNotBlank()
            assertThat(operation.description)
                .describedAs("${operation.method.uppercase()} ${operation.path} must describe behavior and preconditions")
                .contains("Vorbedingungen")
            assertThat(operation.status)
                .describedAs("${operation.method.uppercase()} ${operation.path} must declare implementation status")
                .isIn(statuses)
            assertThat(operation.preconditions)
                .describedAs("${operation.method.uppercase()} ${operation.path} must declare x-preconditions")
                .isNotEmpty
            assertThat(operation.successResponses)
                .describedAs("${operation.method.uppercase()} ${operation.path} must declare a success response")
                .isNotEmpty

            if (operation.hasRequestBody) {
                assertThat(operation.requestHasExample)
                    .describedAs("${operation.method.uppercase()} ${operation.path} request body must include an example")
                    .isTrue()
            }

            operation.successResponses
                .filter { it.hasContent }
                .forEach {
                    assertThat(it.hasExample)
                        .describedAs("${operation.method.uppercase()} ${operation.path} ${it.code} response must include example(s)")
                        .isTrue()
                }

            if (operation.requiresAccessToken) {
                assertThat(operation.responses.keys)
                    .describedAs("${operation.method.uppercase()} ${operation.path} must document unauthenticated access")
                    .contains("401")
            }
        }
    }

    @Test
    fun `implemented backend resource endpoints are covered by openapi specs`() {
        val documented = operations()
            .filter { it.status != "planned" }
            .map { "${it.method.uppercase()} ${it.path}" }
            .toSet()

        assertThat(documented)
            .containsAll(implementedBackendEndpoints)

        val implementedInSpec = operations()
            .filter { it.status == "implemented" }
            .map { "${it.method.uppercase()} ${it.path}" }
            .toSet()

        assertThat(implementedBackendEndpoints)
            .containsAll(implementedInSpec)
    }

    @Test
    fun `backend api resources implement their generated openapi interfaces`() {
        generatedInterfaceBindings.forEach { (apiInterface, resourceClass) ->
            assertThat(apiInterface.isAssignableFrom(resourceClass))
                .describedAs("${resourceClass.simpleName} must implement ${apiInterface.simpleName}")
                .isTrue()
        }
    }

    @Test
    fun `generated interfaces use generated payload models for json contracts`() {
        generatedApiSources().forEach { source ->
            val handwrittenApiImport = Regex("""import\s+com\.lunva\.api\.(?!generated)[\w.]+;""")
                .find(Files.readString(source))
            assertThat(handwrittenApiImport)
                .describedAs("${source.fileName} must not import handwritten API payload classes")
                .isNull()
        }

        operations()
            .filter { it.status != "planned" && it.hasJsonRequestBody }
            .forEach { operation ->
                val method = operation.generatedMethod()
                assertThat(method.parameterTypes.any { it.isGeneratedModel() })
                    .describedAs("${operation.specFile} ${operation.operationId} must accept a generated model for its JSON request body")
                    .isTrue()
            }

        operations()
            .filter { it.status != "planned" && it.hasJsonSuccessResponse }
            .map { it.generatedMethod() }
            .filterNot { it.returnType == jakarta.ws.rs.core.Response::class.java }
            .forEach { method ->
                assertThat(method.returnType.isGeneratedModel())
                    .describedAs("${method.declaringClass.simpleName}.${method.name} must return a generated model or Response")
                    .isTrue()
            }
    }

    @Test
    fun `backend openapi code uses one shared generated api and model package`() {
        val generatedModelFiles = generatedModelSources()
        assertThat(generatedModelFiles.filter { it.fileName.toString() == "UserSummary.kt" })
            .describedAs("UserSummary must be generated exactly once")
            .hasSize(1)

        generatedApiSources().forEach { source ->
            val sourceText = Files.readString(source)
            assertThat(sourceText)
                .describedAs("${source.fileName} must use the shared generated model package")
                .doesNotContainPattern("""com\.lunva\.api\.generated\.[a-z]+\.model""")
        }

        generatedModelFiles.forEach { source ->
            val sourceText = Files.readString(source)
            assertThat(sourceText)
                .describedAs("${source.fileName} must not depend on domain generated model packages")
                .doesNotContainPattern("""com\.lunva\.api\.generated\.[a-z]+\.model""")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun operations(): List<Operation> {
        return Files.list(apiDirectory()).use { stream ->
            stream
                .filter { it.name.endsWith(".yaml") }
                .filter { it.name != "common.openapi.yaml" && it.name != "frontend.openapi.yaml" }
                .flatMap { file ->
                    val document = mapper.readValue(file.toFile(), Map::class.java) as Map<String, Any?>
                    val paths = document["paths"] as Map<String, Any?>
                    paths.entries.flatMap { (path, rawPathItem) ->
                        val pathItem = rawPathItem as Map<String, Any?>
                        pathItem.entries
                            .filter { (method, _) -> method in httpMethods }
                            .map { (method, rawOperation) ->
                                val operation = rawOperation as Map<String, Any?>
                                Operation(
                                    specFile = file.name,
                                    path = path,
                                    method = method,
                                    operationId = operation["operationId"] as? String ?: "",
                                    tags = operation["tags"] as? List<String> ?: emptyList(),
                                    summary = operation["summary"] as? String ?: "",
                                    description = operation["description"] as? String ?: "",
                                    status = operation["x-implementation-status"] as? String ?: "",
                                    preconditions = operation["x-preconditions"] as? List<String> ?: emptyList(),
                                    requestBody = resolveReference(document, operation["requestBody"] as? Map<String, Any?>),
                                    responses = (operation["responses"] as Map<String, Any?>).mapValues { (_, response) ->
                                        resolveReference(document, response as? Map<String, Any?>) ?: emptyMap<String, Any?>()
                                    },
                                    security = operation["security"] as? List<Any?>,
                                )
                            }
                    }.stream()
                }
                .toList()
        }
    }

    private fun apiDirectory(): Path {
        val cwd = Path.of("").toAbsolutePath()
        return if (cwd.name == "backend") cwd.resolve("../docs/api").normalize() else cwd.resolve("docs/api")
    }

    private fun buildDirectory(): Path {
        val cwd = Path.of("").toAbsolutePath()
        return if (cwd.name == "backend") cwd.resolve("build").normalize() else cwd.resolve("backend/build")
    }

    private fun generatedApiSources(): List<Path> =
        Files.walk(buildDirectory().resolve("generated/openapi-api")).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().endsWith("Api.java") }
                .toList()
        }

    private fun generatedModelSources(): List<Path> =
        Files.walk(buildDirectory().resolve("generated/openapi-models")).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .filter { it.toString().contains("/src/main/kotlin/") }
                .filter { it.fileName.toString().endsWith(".kt") }
                .toList()
        }

    @Suppress("UNCHECKED_CAST")
    private fun resolveReference(document: Map<String, Any?>, value: Map<String, Any?>?): Map<String, Any?>? {
        val ref = value?.get("\$ref") as? String ?: return value
        val target = ref.removePrefix("#/").split("/").fold(document as Any?) { current, part ->
            (current as? Map<String, Any?>)?.get(part)
        } as? Map<String, Any?> ?: return value

        return target + value.filterKeys { it != "\$ref" && value[it] !in listOf(null, emptyMap<String, Any?>()) }
    }

    private data class Operation(
        val specFile: String,
        val path: String,
        val method: String,
        val operationId: String,
        val tags: List<String>,
        val summary: String,
        val description: String,
        val status: String,
        val preconditions: List<String>,
        val requestBody: Map<String, Any?>?,
        val responses: Map<String, Any?>,
        val security: List<Any?>?,
    ) {
        val hasRequestBody: Boolean = requestBody != null
        val requestHasExample: Boolean = requestBody?.let(::contentHasExample) == true
        val hasJsonRequestBody: Boolean = requestBody.hasJsonContent()
        val successResponses: List<Response> = responses
            .filterKeys { it in setOf("200", "201", "204", "206") }
            .map { (code, rawResponse) -> Response(code, rawResponse as? Map<String, Any?> ?: emptyMap()) }
        val hasJsonSuccessResponse: Boolean = successResponses.any { it.hasJsonContent }
        val requiresAccessToken: Boolean = security != emptyList<Any?>() &&
                path !in setOf("/api/setup", "/api/setup/admin", "/api/auth/login")

        fun generatedMethod(): Method {
            val apiInterface = generatedInterface(specFile, operationId)
            return apiInterface.methods.singleOrNull { it.name == operationId }
                ?: error("${apiInterface.simpleName} is missing generated method $operationId for $method $path")
        }
    }

    private data class Response(val code: String, val raw: Map<String, Any?>) {
        val hasContent: Boolean = raw["content"] is Map<*, *>
        val hasExample: Boolean = contentHasExample(raw)
        val hasJsonContent: Boolean = raw.hasJsonContent()
    }

    private companion object {
        val implementedBackendEndpoints = setOf(
            "POST /api/auth/login",
            "POST /api/auth/logout",
            "POST /api/auth/refresh",
            "GET /api/setup",
            "POST /api/setup/admin",
            "GET /api/users/me",
            "PUT /api/users/me",
            "POST /api/users/me/email/confirm",
            "POST /api/users/me/password/change",
            "POST /api/users/password/forgot",
            "POST /api/users/{userId}/password/reset",
            "GET /api/users",
            "POST /api/users",
            "PUT /api/users/{userId}/role",
            "POST /api/users/{userId}/invite/accept",
            "GET /api/tenants",
            "POST /api/tenants",
            "DELETE /api/tenants/{tenantName}",
            "GET /api/tenants/{tenantName}/settings",
            "PUT /api/tenants/{tenantName}/settings",
            "GET /api/tenants/{tenantName}/me",
            "PUT /api/tenants/{tenantName}/me",
            "GET /api/tenants/{tenantName}/users",
            "GET /api/tenants/{tenantName}/users/search",
            "POST /api/tenants/{tenantName}/users/{userId}/invite",
            "POST /api/tenants/{tenantName}/users/{userId}/reinvite",
            "PUT /api/tenants/{tenantName}/users/{userId}/role",
            "GET /api/tenants/{tenantName}/{groupName}",
            "PUT /api/tenants/{tenantName}/{groupName}",
            "GET /api/{tenantName}/groups",
            "POST /api/{tenantName}/groups",
            "GET /api/{tenantName}/groups/{groupName}",
            "DELETE /api/{tenantName}/groups/{groupName}",
            "POST /api/{tenantName}/groups/{groupName}",
            "GET /api/settings",
            "GET /api/settings/server",
            "GET /api/{tenantName}/explorer",
            "POST /api/{tenantName}/folders",
            "POST /api/{tenantName}/folders/update",
            "POST /api/{tenantName}/folders/move",
            "DELETE /api/{tenantName}/folders",
            "POST /api/{tenantName}/albums",
            "POST /api/{tenantName}/albums/update",
            "POST /api/{tenantName}/albums/move",
            "DELETE /api/{tenantName}/albums",
            "POST /api/{tenantName}/albums/media",
            "POST /api/{tenantName}/albums/cover",
            "GET /api/{tenantName}/media",
            "GET /api/{tenantName}/media/{mediaId}/content",
            "PATCH /api/{tenantName}/media/{mediaId}/metadata",
            "POST /api/media/uploads",
            "GET /api/media/uploads",
            "GET /api/media/uploads/{uploadId}",
            "PUT /api/media/uploads/{uploadId}/chunks/{chunkIndex}",
            "POST /api/media/uploads/{uploadId}/complete",
            "DELETE /api/media/uploads/{uploadId}",
        )

        val generatedInterfaceBindings = mapOf(
            AuthApi::class.java to AuthResource::class.java,
            SetupApi::class.java to SetupResource::class.java,
            UsersApi::class.java to UserResource::class.java,
            TenantsApi::class.java to TenantResource::class.java,
            GroupsApi::class.java to GroupResource::class.java,
            ServerApi::class.java to SettingsResource::class.java,
            ExplorerApi::class.java to ExplorerResource::class.java,
            SettingsApi::class.java to SettingsExplorerResource::class.java,
            FoldersApi::class.java to FolderResource::class.java,
            AlbumsApi::class.java to AlbumResource::class.java,
            MediaApi::class.java to MediaResource::class.java,
            MediaUploadsApi::class.java to MediaUploadResource::class.java,
        )

        fun generatedInterface(specFile: String, operationId: String): Class<*> =
            when (specFile) {
                "auth.openapi.yaml" -> AuthApi::class.java
                "setup.openapi.yaml" -> SetupApi::class.java
                "users.openapi.yaml" -> UsersApi::class.java
                "tenants.openapi.yaml" -> TenantsApi::class.java
                "groups.openapi.yaml" -> GroupsApi::class.java
                "server.openapi.yaml" -> ServerApi::class.java
                "settings.openapi.yaml" -> SettingsApi::class.java
                "explorer.openapi.yaml" -> ExplorerApi::class.java
                "library.openapi.yaml" -> when (operationId) {
                    "createFolder", "deleteFolder", "updateFolder", "moveFolder" -> FoldersApi::class.java
                    "createAlbum", "deleteAlbum", "updateAlbum", "moveAlbum", "addMedia", "setCover" -> AlbumsApi::class.java
                    "media", "upload", "uploadMedia", "content", "preview", "updateMetadata" -> MediaApi::class.java
                    "createUpload", "listUploads", "getUpload", "abortUpload", "uploadChunk", "completeUpload" -> MediaUploadsApi::class.java
                    else -> error("No generated library interface mapping for $operationId")
                }
                else -> error("No generated interface mapping for $specFile")
            }
    }
}

private fun Class<*>.isGeneratedModel(): Boolean =
    packageName.startsWith("com.lunva.api.generated.") && packageName.endsWith(".model")

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>?.hasJsonContent(): Boolean {
    val content = this?.get("content") as? Map<String, Any?> ?: return false
    return content.keys.any { it == "application/json" || it.endsWith("+json") }
}

@Suppress("UNCHECKED_CAST")
private fun contentHasExample(container: Map<String, Any?>): Boolean {
    val content = container["content"] as? Map<String, Any?> ?: return false
    return content.values.any { media ->
        val mediaMap = media as? Map<String, Any?> ?: return@any false
        mediaMap.containsKey("example") || mediaMap.containsKey("examples")
    }
}
