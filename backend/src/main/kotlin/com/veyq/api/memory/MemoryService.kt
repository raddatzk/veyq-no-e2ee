package com.veyq.api.memory

import com.veyq.api.auth.BadRequestException
import com.veyq.api.auth.ConflictException
import com.veyq.api.auth.NotFoundException
import com.veyq.api.auth.filterchain.RequestContext
import com.veyq.api.domain.*
import com.veyq.api.generated.model.CreateMemoryRequest
import com.veyq.api.generated.model.MemoryScope
import com.veyq.api.generated.model.MemoryScopeRequest
import com.veyq.api.generated.model.MemorySummary
import com.veyq.api.generated.model.UpdateMemoryRequest
import com.veyq.api.library.LibraryScopeKey
import com.veyq.api.library.LibraryScopes
import com.veyq.api.library.LibraryService
import com.veyq.api.library.ResolvedMemoryLocation
import com.veyq.api.library.media.MediaConfig
import jakarta.inject.Inject
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Comparator
import jakarta.ws.rs.core.Response

@Singleton
class MemoryService @Inject constructor(
    private val memoryRepo: MemoryRepo,
    private val mediaRepo: MediaRepo,
    private val folderRepo: TenantFolderRepo,
    private val groupRepo: TenantGroupRepo,
    private val memoryGroupMemberRepo: TenantGroupMemberRepo,
    private val mediaConfig: MediaConfig,
    requestContext: RequestContext,
) : LibraryService(folderRepo, groupRepo, memoryGroupMemberRepo, requestContext) {

    fun listRootMemories(): List<MemorySummary> =
        memoryRepo.listRoots(tenant())
            .filter(::isMemoryVisible)
            .map(::toSummary)

    fun listMemoriesByFolderPath(folderPath: String?): List<MemorySummary> {
        val folderTarget = resolveFolderTarget(folderPath)
        val memories = memoryRepo.listByFolder(tenant(), folderTarget.parentFolder)
        return memories
            .filter(::isMemoryVisible)
            .filter { it.scope().sameAs(folderTarget.scope) }
            .map(::toSummary)
    }

    fun getMemory(publicId: String): MemorySummary =
        toSummary(resolveVisibleMemory(publicId))

    fun getMemoryByPath(path: String?): MemorySummary =
        toSummary(resolveVisibleMemoryByPath(path))

    fun listChildren(publicId: String): List<MemorySummary> =
        memoryRepo.listChildren(tenant(), resolveVisibleMemory(publicId))
            .filter(::isMemoryVisible)
            .map(::toSummary)

    @Transactional
    fun createMemory(request: CreateMemoryRequest): MemorySummary {
        val parent = request.parentId?.trim()?.takeIf { it.isNotBlank() }?.let(::resolveVisibleMemory)
        val folderTarget = if (parent == null) request.folderPath
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != "/" }
            ?.let(::resolveFolderTarget)
        else null
        val parentFolder = folderTarget?.parentFolder
        val scope = parent?.scope() ?: folderTarget?.scope ?: resolveRequestedRootScope(request.scope)
        val name = normalizedName(request.name)
        validateNoCollision(parent, parentFolder, scope, name)
        val memory = Memory().also {
            it.tenant = tenant()
            it.parentMemory = parent
            it.parentFolder = parentFolder
            it.owner = user()
            LibraryScopes.apply(it, scope)
            it.name = name
            it.displayName = requiredDisplayName(request.displayName)
            memoryRepo.persist(it)
        }
        memoryRepo.flush()
        return toSummary(memory)
    }

    @Transactional
    fun updateMemory(publicId: String, request: UpdateMemoryRequest): MemorySummary {
        val memory = resolveVisibleMemory(publicId)
        request.parentId?.trim()?.takeIf { it.isNotBlank() }?.let { parentId ->
            val parent = resolveVisibleMemory(parentId)
            if (parent.id == memory.id || isDescendantOrSame(parent, memory)) {
                throw BadRequestException("Cannot move memory into itself")
            }
            if (!parent.scope().sameAs(memory.scope())) {
                throw BadRequestException("Cannot move memory between scopes")
            }
            memory.parentMemory = parent
            memory.parentFolder = null
        }
        request.name?.let {
            val name = normalizedName(it)
            if (name != memory.name) validateNoCollision(memory.parentMemory, memory.parentFolder, memory.scope(), name)
            memory.name = name
        }
        request.displayName?.let { memory.displayName = requiredDisplayName(it) }
        return toSummary(memory)
    }

    @Transactional
    fun deleteMemory(publicId: String) {
        val memory = resolveVisibleMemory(publicId)
        val memories = descendantsAndSelf(memory)
        val media = memories.flatMap { mediaRepo.listByMemory(it) }
        val mediaDirectories = media.map { mediaDirectory(it) }.distinct()
        val coverDirectories = memories.mapNotNull { it.coverStorageKey?.let(::coverDirectory) }.distinct()
        memories.forEach { candidate ->
            if (candidate.coverMedia?.let { cover -> media.any { it.id == cover.id } } == true) {
                candidate.coverMedia = null
            }
        }
        memoryRepo.update("coverMedia = null where coverMedia in ?1", media)
        memoryRepo.flush()
        media.forEach { it.delete() }
        mediaRepo.flush()
        mediaDirectories.forEach(::deleteRecursively)
        coverDirectories.forEach(::deleteRecursively)
        memory.delete()
    }

    @Transactional
    fun setCover(publicId: String, uploadPath: Path, originalFilename: String?): MemorySummary {
        val memory = resolveVisibleMemory(publicId)
        if (Files.size(uploadPath) <= 0) throw BadRequestException("file must not be empty")
        val mimeType = detectCoverMimeType(uploadPath)
        if (!mimeType.startsWith("image/")) throw BadRequestException("Only image covers are supported")

        memory.coverStorageKey?.let { deleteRecursively(coverDirectory(it)) }
        val storageKey = "tenant-${tenant().id}/memory-covers/${memory.publicId}/cover"
        writeFile(uploadPath, storageKey)
        memory.coverStorageKey = storageKey
        memory.coverMimeType = mimeType
        memory.coverOriginalFilename = originalFilename?.trim()?.takeIf { it.isNotBlank() } ?: "cover"
        return toSummary(memory)
    }

    fun coverContent(publicId: String): Response {
        val memory = resolveVisibleMemory(publicId)
        val storageKey = memory.coverStorageKey ?: throw NotFoundException("Cover not found")
        val path = storagePath(storageKey)
        if (!Files.isRegularFile(path)) throw NotFoundException("Cover not found")
        return Response.ok(path.toFile(), memory.coverMimeType ?: "application/octet-stream")
            .header("Cache-Control", "private, max-age=31536000, immutable")
            .header("Content-Length", Files.size(path))
            .build()
    }

    @Transactional
    fun clearCover(publicId: String): MemorySummary {
        val memory = resolveVisibleMemory(publicId)
        memory.coverStorageKey?.let { deleteRecursively(coverDirectory(it)) }
        memory.coverStorageKey = null
        memory.coverMimeType = null
        memory.coverOriginalFilename = null
        memory.coverMedia = null
        return toSummary(memory)
    }

    fun resolveVisibleMemory(publicId: String): Memory {
        val memory = memoryRepo.findByTenantAndPublicId(tenant(), publicId)
            ?: throw NotFoundException("Memory not found")
        if (!isMemoryVisible(memory)) throw NotFoundException("Memory not found")
        return memory
    }

    fun isMemoryVisible(memory: Memory): Boolean =
        memory.tenant.id == tenant().id && isScopeVisible(memory.scope())

    private fun validateNoCollision(parentMemory: Memory?, parentFolder: TenantFolder?, scope: LibraryScopeKey, name: String) {
        val memoryCollision = (parentMemory?.let { memoryRepo.listChildren(tenant(), it) }
            ?: memoryRepo.listByFolder(tenant(), parentFolder))
            .any { it.name == name && it.scope().sameAs(scope) }
        val folderCollision = parentMemory == null && folderRepo.listChildren(tenant(), parentFolder)
            .any { it.name == name && it.hasScope(scope) }
        if (memoryCollision || folderCollision) throw ConflictException("Path name already exists")
    }

    private fun resolveRequestedRootScope(scope: MemoryScopeRequest?): LibraryScopeKey =
        when (scope?.type?.trim()?.uppercase() ?: "TENANT") {
            "TENANT" -> LibraryScopeKey.Tenant
            "GROUP" -> {
                val groupName = scope?.groupName?.trim()?.takeIf { it.isNotBlank() }
                    ?: throw BadRequestException("groupName is required")
                LibraryScopeKey.Group(resolveGroup(groupName))
            }
            "USER" -> LibraryScopeKey.User(user())
            else -> throw BadRequestException("Invalid scope type")
        }.also {
            if (!isScopeVisible(it)) throw NotFoundException("Scope not found")
        }

    private fun resolveGroup(name: String): TenantGroup =
        groupRepo.findByTenantAndName(tenant(), name) ?: throw NotFoundException("Group not found")

    private fun requiredDisplayName(value: String?): String =
        value?.trim()?.takeIf { it.isNotBlank() } ?: throw BadRequestException("displayName is required")

    private fun toSummary(memory: Memory): MemorySummary =
        MemorySummary(
            id = memory.publicId,
            parentId = memory.parentMemory?.publicId,
            name = memory.name,
            displayName = memory.displayName,
            path = memory.path(),
            coverMediaId = memory.coverMedia?.publicId,
            coverUrl = memory.coverStorageKey?.let { "/api/${tenant().name}/memories/${memory.publicId}/cover/content" },
            childCount = memoryRepo.listChildren(tenant(), memory).count { isMemoryVisible(it) },
            mediaCount = mediaRepo.listByMemory(memory).size,
            scope = memory.scope().toApiScope(),
        )

    private fun Memory.path(): String =
        (parentMemory?.path()?.trimEnd('/')
            ?: parentFolder?.path()?.trimEnd('/')
            ?: LibraryScopes.pathPrefix(scope()).trimEnd('/')) + "/" + name

    private fun LibraryScopeKey.toApiScope(): MemoryScope =
        when (this) {
            LibraryScopeKey.Tenant -> MemoryScope(MemoryScope.Type.TENANT, null)
            is LibraryScopeKey.Group -> MemoryScope(MemoryScope.Type.GROUP, group.name)
            is LibraryScopeKey.User -> MemoryScope(MemoryScope.Type.USER, null)
        }

    private fun descendantsAndSelf(root: Memory): List<Memory> =
        listOf(root) + memoryRepo.list("tenant", tenant())
            .filter { candidate -> generateSequence(candidate.parentMemory) { it.parentMemory }.any { it.id == root.id } }

    private fun isDescendantOrSame(candidate: Memory, ancestor: Memory): Boolean =
        generateSequence(candidate) { it.parentMemory }.any { it.id == ancestor.id }

    private data class MemoryFolderTarget(
        val parentFolder: TenantFolder?,
        val scope: LibraryScopeKey,
    )

    private fun resolveFolderTarget(path: String?): MemoryFolderTarget =
        when (val context = resolveVisibleContext(path)) {
            ResolvedMemoryLocation.Tenant -> MemoryFolderTarget(null, LibraryScopeKey.Tenant)
            is ResolvedMemoryLocation.Group -> MemoryFolderTarget(null, context.scope)
            is ResolvedMemoryLocation.User -> MemoryFolderTarget(null, context.scope)
            is ResolvedMemoryLocation.Folder -> MemoryFolderTarget(context.folder, context.folder.scope())
        }

    private fun resolveVisibleMemoryByPath(path: String?): Memory {
        val segments = pathSegments(path)
        if (segments.isEmpty()) throw NotFoundException("Memory not found")

        segments.indices.reversed().forEach { memoryIndex ->
            val folderPath = segments.take(memoryIndex).toPathOrNull()
            val folderTarget = try {
                resolveFolderTarget(folderPath)
            } catch (_: NotFoundException) {
                return@forEach
            }
            var memory = memoryRepo.listByFolder(tenant(), folderTarget.parentFolder)
                .firstOrNull { it.name == segments[memoryIndex] && it.scope().sameAs(folderTarget.scope) }
                ?: return@forEach
            for (childName in segments.drop(memoryIndex + 1)) {
                memory = memoryRepo.listChildren(tenant(), memory).firstOrNull { it.name == childName }
                    ?: return@forEach
            }
            if (isMemoryVisible(memory)) return memory
        }

        throw NotFoundException("Memory not found")
    }

    private fun pathSegments(path: String?): List<String> =
        path?.trim()?.trim('/')?.split('/')?.filter { it.isNotBlank() } ?: emptyList()

    private fun List<String>.toPathOrNull(): String? =
        if (isEmpty()) null else "/" + joinToString("/")

    private fun mediaDirectory(media: Media): Path {
        val originalPath = storagePath(media.storageKey)
        return originalPath.parent ?: storagePath("tenant-${media.tenant.id}/${media.publicId}")
    }

    private fun coverDirectory(storageKey: String): Path {
        val coverPath = storagePath(storageKey)
        return coverPath.parent ?: coverPath
    }

    private fun writeFile(source: Path, storageKey: String) {
        val target = storagePath(storageKey)
        Files.createDirectories(target.parent)
        val temp = Files.createTempFile(target.parent, "cover-", ".tmp")
        try {
            Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING)
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Files.deleteIfExists(temp)
            throw e
        }
    }

    private fun detectCoverMimeType(path: Path): String {
        val header = ByteArray(16)
        val read = Files.newInputStream(path).use { input -> input.read(header) }
        if (read >= 3 &&
            header[0] == 0xFF.toByte() &&
            header[1] == 0xD8.toByte() &&
            header[2] == 0xFF.toByte()
        ) {
            return "image/jpeg"
        }
        if (read >= 8 &&
            header[0] == 0x89.toByte() &&
            header[1] == 'P'.code.toByte() &&
            header[2] == 'N'.code.toByte() &&
            header[3] == 'G'.code.toByte()
        ) {
            return "image/png"
        }
        return Files.probeContentType(path) ?: ""
    }

    private fun storagePath(storageKey: String): Path {
        val root = mediaConfig.storageDir.toAbsolutePath().normalize()
        val path = root.resolve(storageKey).normalize()
        if (!path.startsWith(root)) throw BadRequestException("Invalid media storage key")
        return path
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
