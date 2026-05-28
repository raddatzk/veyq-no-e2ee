package com.veyq.api.contract

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.veyq.api.auth.AuthResource
import com.veyq.api.generated.api.AuthApi
import com.veyq.api.generated.api.GroupsApi
import com.veyq.api.generated.api.FoldersApi
import com.veyq.api.generated.api.MemoriesApi
import com.veyq.api.generated.api.MemoryMediaApi
import com.veyq.api.generated.api.MemoryNavigationApi
import com.veyq.api.generated.api.MemoryParticipantsApi
import com.veyq.api.generated.api.MemoryPlaylistsApi
import com.veyq.api.generated.api.MemorySharesApi
import com.veyq.api.generated.api.MemoryTracksApi
import com.veyq.api.generated.api.ProfilesApi
import com.veyq.api.generated.api.PublicSharesApi
import com.veyq.api.generated.api.SearchApi
import com.veyq.api.generated.api.ServerApi
import com.veyq.api.generated.api.SettingsApi
import com.veyq.api.generated.api.SetupApi
import com.veyq.api.generated.api.TenantsApi
import com.veyq.api.generated.api.UsersApi
import com.veyq.api.library.folder.FolderResource
import com.veyq.api.memory.MemoryMediaResource
import com.veyq.api.memory.MemoryNavigationResource
import com.veyq.api.memory.MemoryParticipantResource
import com.veyq.api.memory.MemoryPlaylistResource
import com.veyq.api.memory.MemoryResource
import com.veyq.api.memory.MemoryShareResource
import com.veyq.api.memory.MemoryTrackResource
import com.veyq.api.memory.PublicShareResource
import com.veyq.api.memory.SearchResource
import com.veyq.api.settings.SettingsNavigationResource
import com.veyq.api.settings.SettingsResource
import com.veyq.api.setup.SetupResource
import com.veyq.api.tenant.TenantResource
import com.veyq.api.usermgmt.group.GroupResource
import com.veyq.api.usermgmt.user.ProfileResource
import com.veyq.api.usermgmt.user.UserResource
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
    fun `common error response exposes stable codes and fallback messages`() {
        val document = mapper.readValue(apiDirectory().resolve("common.openapi.yaml").toFile(), Map::class.java) as Map<String, Any?>
        val components = document["components"] as Map<String, Any?>
        val schemas = components["schemas"] as Map<String, Any?>
        val errorResponse = schemas["ErrorResponse"] as Map<String, Any?>
        val required = errorResponse["required"] as List<String>
        val properties = errorResponse["properties"] as Map<String, Any?>

        assertThat(required).containsExactlyInAnyOrder("code", "message")
        assertThat(properties).containsKeys("code", "message")
        assertThat(properties).doesNotContainKey("error")
        assertThat(containsKeyRecursively(document, "error"))
            .describedAs("common error examples must not use legacy error property")
            .isFalse()
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
            val handwrittenApiImport = Regex("""import\s+com\.veyq\.api\.(?!generated)[\w.]+;""")
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
                .doesNotContainPattern("""com\.veyq\.api\.generated\.[a-z]+\.model""")
        }

        generatedModelFiles.forEach { source ->
            val sourceText = Files.readString(source)
            assertThat(sourceText)
                .describedAs("${source.fileName} must not depend on domain generated model packages")
                .doesNotContainPattern("""com\.veyq\.api\.generated\.[a-z]+\.model""")
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

    private fun containsKeyRecursively(value: Any?, key: String): Boolean =
        when (value) {
            is Map<*, *> -> value.keys.any { it == key } || value.values.any { containsKeyRecursively(it, key) }
            is List<*> -> value.any { containsKeyRecursively(it, key) }
            else -> false
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
            "POST /api/auth/passkeys/login/options",
            "POST /api/auth/passkeys/login",
            "POST /api/auth/logout",
            "POST /api/auth/refresh",
            "GET /api/setup",
            "POST /api/setup/admin",
            "GET /api/users/me",
            "PUT /api/users/me",
            "GET /api/users/me/avatar",
            "PUT /api/users/me/avatar",
            "DELETE /api/users/me/avatar",
            "POST /api/users/me/email/confirm",
            "POST /api/users/me/password/change",
            "GET /api/users/me/passkeys",
            "POST /api/users/me/passkeys",
            "POST /api/users/me/passkeys/options",
            "PUT /api/users/me/passkeys/{passkeyId}",
            "DELETE /api/users/me/passkeys/{passkeyId}",
            "POST /api/users/password/forgot",
            "POST /api/users/{userId}/password/reset",
            "GET /api/users",
            "POST /api/users",
            "PUT /api/users/{userId}/role",
            "POST /api/users/{userId}/invite/accept",
            "GET /api/tenants",
            "POST /api/tenants",
            "DELETE /api/tenants/{tenantName}",
            "GET /api/tenants/{tenantName}/logo",
            "PUT /api/tenants/{tenantName}/logo",
            "DELETE /api/tenants/{tenantName}/logo",
            "GET /api/tenants/{tenantName}/settings",
            "PUT /api/tenants/{tenantName}/settings",
            "GET /api/tenants/{tenantName}/me",
            "PUT /api/tenants/{tenantName}/me",
            "GET /api/tenants/{tenantName}/users",
            "POST /api/tenants/{tenantName}/users/invite",
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
            "GET /api/settings/server/update-events",
            "GET /api/settings/server/notifications",
            "DELETE /api/settings/server/notifications/{notificationId}",
            "GET /api/notifications/capabilities",
            "DELETE /api/notifications/capabilities/{capabilityId}",
            "POST /api/internal/updater/update-completed",
            "POST /api/internal/updater/update-failed",
            "GET /api/{tenantName}/memory-navigation",
            "GET /api/{tenantName}/search",
            "POST /api/{tenantName}/folders",
            "POST /api/{tenantName}/folders/update",
            "POST /api/{tenantName}/folders/move",
            "DELETE /api/{tenantName}/folders",
            "GET /api/{tenantName}/memories",
            "GET /api/{tenantName}/memories/by-folder",
            "GET /api/{tenantName}/memories/by-path",
            "POST /api/{tenantName}/memories",
            "GET /api/{tenantName}/memories/{memoryId}",
            "PUT /api/{tenantName}/memories/{memoryId}",
            "DELETE /api/{tenantName}/memories/{memoryId}",
            "PUT /api/{tenantName}/memories/{memoryId}/favorite",
            "DELETE /api/{tenantName}/memories/{memoryId}/favorite",
            "GET /api/{tenantName}/memories/{memoryId}/children",
            "GET /api/{tenantName}/memories/{memoryId}/header",
            "GET /api/{tenantName}/memories/{memoryId}/journal",
            "PUT /api/{tenantName}/memories/{memoryId}/journal",
            "GET /api/{tenantName}/memories/{memoryId}/participants",
            "GET /api/{tenantName}/memories/{memoryId}/participant-candidates",
            "PUT /api/{tenantName}/memories/{memoryId}/participants/{userId}",
            "DELETE /api/{tenantName}/memories/{memoryId}/participants/{userId}",
            "GET /api/{tenantName}/memories/{memoryId}/media",
            "POST /api/{tenantName}/memories/{memoryId}/media",
            "DELETE /api/{tenantName}/memories/{memoryId}/media",
            "GET /api/{tenantName}/memories/{memoryId}/media-buckets",
            "POST /api/{tenantName}/memories/{memoryId}/media-buckets",
            "PUT /api/{tenantName}/memories/{memoryId}/media-buckets/{bucketId}",
            "DELETE /api/{tenantName}/memories/{memoryId}/media-buckets/{bucketId}",
            "POST /api/{tenantName}/memories/{memoryId}/media/move",
            "DELETE /api/{tenantName}/memories/{memoryId}/media/batch",
            "POST /api/{tenantName}/memories/{memoryId}/media/uploads",
            "GET /api/{tenantName}/memories/{memoryId}/media/uploads/{uploadId}",
            "DELETE /api/{tenantName}/memories/{memoryId}/media/uploads/{uploadId}",
            "PUT /api/{tenantName}/memories/{memoryId}/media/uploads/{uploadId}/chunks/{chunkIndex}",
            "POST /api/{tenantName}/memories/{memoryId}/media/uploads/{uploadId}/complete",
            "GET /api/{tenantName}/memories/{memoryId}/download",
            "GET /api/{tenantName}/memories/{memoryId}/tracks",
            "POST /api/{tenantName}/memories/{memoryId}/tracks",
            "PUT /api/{tenantName}/memories/{memoryId}/tracks/{trackId}",
            "DELETE /api/{tenantName}/memories/{memoryId}/tracks/{trackId}",
            "GET /api/{tenantName}/memories/{memoryId}/tracks/{trackId}/content",
            "GET /api/{tenantName}/memories/{memoryId}/playlists",
            "POST /api/{tenantName}/memories/{memoryId}/playlists",
            "PUT /api/{tenantName}/memories/{memoryId}/playlists/{playlistId}",
            "DELETE /api/{tenantName}/memories/{memoryId}/playlists/{playlistId}",
            "PUT /api/{tenantName}/memories/{memoryId}/cover",
            "DELETE /api/{tenantName}/memories/{memoryId}/cover",
            "GET /api/{tenantName}/memories/{memoryId}/cover/content",
            "PATCH /api/{tenantName}/memories/{memoryId}/media/{mediaId}/metadata",
            "DELETE /api/{tenantName}/memories/{memoryId}/media/{mediaId}",
            "PATCH /api/{tenantName}/memories/{memoryId}/media/metadata-batch",
            "GET /api/{tenantName}/memory-media/{mediaId}/content",
            "GET /api/{tenantName}/memory-media/{mediaId}/preview",
            "GET /api/{tenantName}/memory-media/{mediaId}/poster",
            "GET /api/{tenantName}/memory-media/{mediaId}/thumbnail",
            "GET /api/{tenantName}/profiles/{userPermalink}",
            "GET /api/{tenantName}/profiles/{userPermalink}/avatar",
            "GET /api/{tenantName}/memories/{memoryId}/shares",
            "POST /api/{tenantName}/memories/{memoryId}/shares",
            "PUT /api/{tenantName}/memory-shares/{shareId}",
            "GET /api/public-shares/{shareId}",
            "POST /api/public-shares/{shareId}/login",
            "POST /api/public-shares/{shareId}/logout",
            "POST /api/public-shares/{shareId}/refresh",
            "GET /api/public-shares/{shareId}/tree",
            "GET /api/public-shares/{shareId}/memories/by-path",
            "PUT /api/public-shares/{shareId}/memories/{memoryId}",
            "DELETE /api/public-shares/{shareId}/memories/{memoryId}",
            "GET /api/public-shares/{shareId}/memories/{memoryId}/children",
            "GET /api/public-shares/{shareId}/memories/{memoryId}/header",
            "GET /api/public-shares/{shareId}/memories/{memoryId}/journal",
            "PUT /api/public-shares/{shareId}/memories/{memoryId}/journal",
            "POST /api/public-shares/{shareId}/memories/{memoryId}/children",
            "GET /api/public-shares/{shareId}/memories/{memoryId}/media",
            "POST /api/public-shares/{shareId}/memories/{memoryId}/media",
            "GET /api/public-shares/{shareId}/memories/{memoryId}/media-buckets",
            "POST /api/public-shares/{shareId}/memories/{memoryId}/media-buckets",
            "PUT /api/public-shares/{shareId}/memories/{memoryId}/media-buckets/{bucketId}",
            "DELETE /api/public-shares/{shareId}/memories/{memoryId}/media-buckets/{bucketId}",
            "POST /api/public-shares/{shareId}/memories/{memoryId}/media/move",
            "DELETE /api/public-shares/{shareId}/memories/{memoryId}/media/batch",
            "POST /api/public-shares/{shareId}/memories/{memoryId}/media/uploads",
            "GET /api/public-shares/{shareId}/memories/{memoryId}/media/uploads/{uploadId}",
            "DELETE /api/public-shares/{shareId}/memories/{memoryId}/media/uploads/{uploadId}",
            "PUT /api/public-shares/{shareId}/memories/{memoryId}/media/uploads/{uploadId}/chunks/{chunkIndex}",
            "POST /api/public-shares/{shareId}/memories/{memoryId}/media/uploads/{uploadId}/complete",
            "GET /api/public-shares/{shareId}/memories/{memoryId}/download",
            "GET /api/public-shares/{shareId}/memories/{memoryId}/tracks",
            "POST /api/public-shares/{shareId}/memories/{memoryId}/tracks",
            "PUT /api/public-shares/{shareId}/memories/{memoryId}/tracks/{trackId}",
            "DELETE /api/public-shares/{shareId}/memories/{memoryId}/tracks/{trackId}",
            "GET /api/public-shares/{shareId}/memories/{memoryId}/tracks/{trackId}/content",
            "GET /api/public-shares/{shareId}/memories/{memoryId}/playlists",
            "POST /api/public-shares/{shareId}/memories/{memoryId}/playlists",
            "PUT /api/public-shares/{shareId}/memories/{memoryId}/playlists/{playlistId}",
            "DELETE /api/public-shares/{shareId}/memories/{memoryId}/playlists/{playlistId}",
            "PUT /api/public-shares/{shareId}/memories/{memoryId}/cover",
            "DELETE /api/public-shares/{shareId}/memories/{memoryId}/cover",
            "GET /api/public-shares/{shareId}/memories/{memoryId}/cover/content",
            "PATCH /api/public-shares/{shareId}/memories/{memoryId}/media/{mediaId}/metadata",
            "DELETE /api/public-shares/{shareId}/memories/{memoryId}/media/{mediaId}",
            "GET /api/public-shares/{shareId}/media",
            "POST /api/public-shares/{shareId}/media",
            "POST /api/public-shares/{shareId}/media/uploads",
            "GET /api/public-shares/{shareId}/media/uploads/{uploadId}",
            "DELETE /api/public-shares/{shareId}/media/uploads/{uploadId}",
            "PUT /api/public-shares/{shareId}/media/uploads/{uploadId}/chunks/{chunkIndex}",
            "POST /api/public-shares/{shareId}/media/uploads/{uploadId}/complete",
            "GET /api/public-shares/{shareId}/media/{mediaId}/content",
            "GET /api/public-shares/{shareId}/media/{mediaId}/preview",
            "GET /api/public-shares/{shareId}/media/{mediaId}/poster",
            "GET /api/public-shares/{shareId}/media/{mediaId}/thumbnail",
        )

        val generatedInterfaceBindings = mapOf(
            AuthApi::class.java to AuthResource::class.java,
            SetupApi::class.java to SetupResource::class.java,
            UsersApi::class.java to UserResource::class.java,
            ProfilesApi::class.java to ProfileResource::class.java,
            TenantsApi::class.java to TenantResource::class.java,
            GroupsApi::class.java to GroupResource::class.java,
            ServerApi::class.java to SettingsResource::class.java,
            SettingsApi::class.java to SettingsNavigationResource::class.java,
            FoldersApi::class.java to FolderResource::class.java,
            MemoryNavigationApi::class.java to MemoryNavigationResource::class.java,
            MemoriesApi::class.java to MemoryResource::class.java,
            MemoryParticipantsApi::class.java to MemoryParticipantResource::class.java,
            MemoryMediaApi::class.java to MemoryMediaResource::class.java,
            MemoryTracksApi::class.java to MemoryTrackResource::class.java,
            MemoryPlaylistsApi::class.java to MemoryPlaylistResource::class.java,
            MemorySharesApi::class.java to MemoryShareResource::class.java,
            PublicSharesApi::class.java to PublicShareResource::class.java,
            SearchApi::class.java to SearchResource::class.java,
        )

        fun generatedInterface(specFile: String, operationId: String): Class<*> =
            when (specFile) {
                "auth.openapi.yaml" -> AuthApi::class.java
                "setup.openapi.yaml" -> SetupApi::class.java
                "users.openapi.yaml" -> when (operationId) {
                    "getUserProfile", "getUserProfileAvatar" -> ProfilesApi::class.java
                    else -> UsersApi::class.java
                }
                "tenants.openapi.yaml" -> TenantsApi::class.java
                "groups.openapi.yaml" -> GroupsApi::class.java
                "memories.openapi.yaml" -> when (operationId) {
                    "listMemories", "listMemoriesByFolder", "getMemoryByPath", "createMemory", "getMemory", "getMemoryHeader", "getMemoryJournal", "updateMemoryJournal", "updateMemory", "deleteMemory", "favoriteMemory", "unfavoriteMemory", "listChildMemories", "setMemoryCover", "memoryCoverContent", "removeMemoryCover" -> MemoriesApi::class.java
                    "listMemoryParticipants", "listMemoryParticipantCandidates", "addMemoryParticipant", "removeMemoryParticipant" -> MemoryParticipantsApi::class.java
                    "listMemoryMedia", "uploadMemoryMedia", "deleteAllMemoryMedia", "listMemoryMediaBuckets", "createMemoryMediaBucket", "updateMemoryMediaBucket", "deleteMemoryMediaBucket", "moveMemoryMedia", "deleteMemoryMediaBatch", "createMemoryMediaUpload", "getMemoryMediaUpload", "abortMemoryMediaUpload", "uploadMemoryMediaChunk", "completeMemoryMediaUpload", "updateMemoryMediaMetadata", "deleteMemoryMedia", "updateMemoryMediaMetadataBatch", "memoryMediaContent", "memoryMediaPreview", "memoryMediaThumbnail" -> MemoryMediaApi::class.java
                    "listMemoryTracks", "uploadMemoryTrack", "updateMemoryTrack", "deleteMemoryTrack", "memoryTrackContent" -> MemoryTracksApi::class.java
                    "listMemoryPlaylists", "createMemoryPlaylist", "updateMemoryPlaylist", "deleteMemoryPlaylist" -> MemoryPlaylistsApi::class.java
                    "getMemoryNavigation" -> MemoryNavigationApi::class.java
                    "searchTenant" -> SearchApi::class.java
                    "createFolder", "deleteFolder", "updateFolder", "moveFolder" -> FoldersApi::class.java
                    else -> error("No generated memories interface mapping for $operationId")
                }
                "server.openapi.yaml" -> ServerApi::class.java
                "settings.openapi.yaml" -> SettingsApi::class.java
                "shares.openapi.yaml" -> when (operationId) {
                    "listMemoryShares", "createMemoryShare", "updateMemoryShare" -> MemorySharesApi::class.java
                    "getPublicShare", "loginPublicShare", "logoutPublicShare", "refreshPublicShare", "getPublicShareTree", "getPublicShareMemoryByPath", "getPublicShareMemoryHeader", "getPublicShareMemoryJournal", "updatePublicShareMemoryJournal", "updatePublicShareMemory", "deletePublicShareMemory", "listPublicShareChildMemories", "createPublicShareChildMemory", "listPublicShareMemoryMedia", "uploadPublicShareMemoryMedia", "listPublicShareMemoryMediaBuckets", "createPublicShareMemoryMediaBucket", "updatePublicShareMemoryMediaBucket", "deletePublicShareMemoryMediaBucket", "movePublicShareMemoryMedia", "deletePublicShareMemoryMediaBatch", "createPublicShareMemoryMediaUpload", "getPublicShareMemoryMediaUpload", "abortPublicShareMemoryMediaUpload", "uploadPublicShareMemoryMediaChunk", "completePublicShareMemoryMediaUpload", "listPublicShareMemoryTracks", "uploadPublicShareMemoryTrack", "updatePublicShareMemoryTrack", "deletePublicShareMemoryTrack", "publicShareMemoryTrackContent", "listPublicShareMemoryPlaylists", "createPublicShareMemoryPlaylist", "updatePublicShareMemoryPlaylist", "deletePublicShareMemoryPlaylist", "setPublicShareMemoryCover", "removePublicShareMemoryCover", "publicShareMemoryCoverContent", "updatePublicShareMediaMetadata", "deletePublicShareMedia", "listPublicShareMedia", "uploadPublicShareMedia", "createPublicShareMediaUpload", "getPublicShareMediaUpload", "abortPublicShareMediaUpload", "uploadPublicShareMediaChunk", "completePublicShareMediaUpload", "publicShareMediaContent", "publicShareMediaPreview", "publicShareMediaThumbnail" -> PublicSharesApi::class.java
                    else -> error("No generated shares interface mapping for $operationId")
                }
                else -> error("No generated interface mapping for $specFile")
            }
    }
}

private fun Class<*>.isGeneratedModel(): Boolean =
    packageName.startsWith("com.veyq.api.generated.") && packageName.endsWith(".model")

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
