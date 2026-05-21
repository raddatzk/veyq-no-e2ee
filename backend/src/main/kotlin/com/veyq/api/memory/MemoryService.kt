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
import com.veyq.api.generated.model.SetMemoryCoverRequest
import com.veyq.api.generated.model.UpdateMemoryRequest
import com.veyq.api.library.LibraryScopeKey
import com.veyq.api.library.LibraryScopes
import com.veyq.api.library.media.MediaConfig
import jakarta.inject.Inject
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

@Singleton
class MemoryService @Inject constructor(
    private val memoryRepo: MemoryRepo,
    private val mediaRepo: MediaRepo,
    private val groupRepo: TenantGroupRepo,
    private val groupMemberRepo: TenantGroupMemberRepo,
    private val mediaConfig: MediaConfig,
    private val requestContext: RequestContext,
) {
    private val validName = Regex("^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$")
    private val reservedNames = setOf("settings")

    fun listRootMemories(): List<MemorySummary> =
        memoryRepo.listRoots(tenant())
            .filter(::isMemoryVisible)
            .map(::toSummary)

    fun getMemory(publicId: String): MemorySummary =
        toSummary(resolveVisibleMemory(publicId))

    fun listChildren(publicId: String): List<MemorySummary> =
        memoryRepo.listChildren(tenant(), resolveVisibleMemory(publicId))
            .filter(::isMemoryVisible)
            .map(::toSummary)

    @Transactional
    fun createMemory(request: CreateMemoryRequest): MemorySummary {
        val parent = request.parentId?.trim()?.takeIf { it.isNotBlank() }?.let(::resolveVisibleMemory)
        val scope = parent?.scope() ?: resolveRequestedRootScope(request.scope)
        val name = normalizedName(request.name)
        validateNoCollision(parent, scope, name)
        val memory = Memory().also {
            it.tenant = tenant()
            it.parentMemory = parent
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
        }
        request.name?.let {
            val name = normalizedName(it)
            if (name != memory.name) validateNoCollision(memory.parentMemory, memory.scope(), name)
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
        memory.delete()
    }

    @Transactional
    fun setCover(publicId: String, request: SetMemoryCoverRequest): MemorySummary {
        val memory = resolveVisibleMemory(publicId)
        val media = mediaRepo.findByTenantAndPublicId(tenant(), request.mediaId)
            ?: throw NotFoundException("Media not found")
        if (media.memory?.id != memory.id) throw NotFoundException("Media not found")
        memory.coverMedia = media
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

    private fun validateNoCollision(parent: Memory?, scope: LibraryScopeKey, name: String) {
        val collision = (parent?.let { memoryRepo.listChildren(tenant(), it) } ?: memoryRepo.listRoots(tenant()))
            .any { it.name == name && it.scope().sameAs(scope) }
        if (collision) throw ConflictException("Memory name already exists")
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

    private fun isScopeVisible(scope: LibraryScopeKey): Boolean =
        when (scope) {
            LibraryScopeKey.Tenant -> true
            is LibraryScopeKey.Group -> scope.group.tenant.id == tenant().id && groupMemberRepo.findByGroupAndUser(scope.group, user()) != null
            is LibraryScopeKey.User -> scope.user.id == user().id
        }

    private fun resolveGroup(name: String): TenantGroup =
        groupRepo.findByTenantAndName(tenant(), name) ?: throw NotFoundException("Group not found")

    private fun normalizedName(value: String?): String {
        val name = value?.trim()?.lowercase() ?: throw BadRequestException("name is required")
        if (name.length > 120 || !name.matches(validName) || name in reservedNames) throw BadRequestException("Invalid name")
        return name
    }

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
            childCount = memoryRepo.listChildren(tenant(), memory).count { isMemoryVisible(it) },
            mediaCount = mediaRepo.listByMemory(memory).size,
            scope = memory.scope().toApiScope(),
        )

    private fun Memory.path(): String =
        (parentMemory?.path()?.trimEnd('/') ?: LibraryScopes.pathPrefix(scope()).trimEnd('/')) + "/" + name

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

    private fun mediaDirectory(media: Media): Path {
        val originalPath = storagePath(media.storageKey)
        return originalPath.parent ?: storagePath("tenant-${media.tenant.id}/${media.publicId}")
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

    private fun tenant(): Tenant = requestContext.tenant!!
    private fun user(): User = requestContext.user!!

    private fun Memory.scope(): LibraryScopeKey =
        LibraryScopes.scope(this)

    private fun LibraryScopeKey.sameAs(other: LibraryScopeKey): Boolean =
        LibraryScopes.sameAs(this, other)
}
