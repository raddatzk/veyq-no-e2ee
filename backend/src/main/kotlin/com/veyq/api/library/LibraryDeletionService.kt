package com.veyq.api.library

import com.veyq.api.auth.BadRequestException
import com.veyq.api.domain.LibraryScopeType
import com.veyq.api.domain.Media
import com.veyq.api.domain.MediaRepo
import com.veyq.api.domain.Memory
import com.veyq.api.domain.MemoryRepo
import com.veyq.api.domain.Tenant
import com.veyq.api.domain.TenantFolder
import com.veyq.api.domain.TenantFolderRepo
import com.veyq.api.domain.TenantGroup
import com.veyq.api.domain.TenantGroupMemberRepo
import com.veyq.api.domain.TenantGroupRepo
import com.veyq.api.domain.TenantMemberRepo
import com.veyq.api.domain.TenantRepo
import com.veyq.api.library.media.MediaConfig
import com.veyq.api.library.media.MediaService
import jakarta.inject.Inject
import jakarta.inject.Singleton
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

@Singleton
class LibraryDeletionService @Inject constructor(
    private val tenantRepo: TenantRepo,
    private val tenantMemberRepo: TenantMemberRepo,
    private val groupRepo: TenantGroupRepo,
    private val groupMemberRepo: TenantGroupMemberRepo,
    private val folderRepo: TenantFolderRepo,
    private val memoryRepo: MemoryRepo,
    private val mediaRepo: MediaRepo,
    private val mediaService: MediaService,
    private val mediaConfig: MediaConfig,
) {
    fun deleteTenant(tenant: Tenant) {
        deleteMedia(mediaRepo.list("tenant", tenant))
        tenantMemberRepo.delete("tenant", tenant)
        groupRepo.listByTenant(tenant).forEach { group -> groupMemberRepo.delete("group", group) }
        memoryRepo.delete("tenant", tenant)
        folderRepo.delete("tenant", tenant)
        groupRepo.delete("tenant", tenant)
        tenantRepo.delete(tenant)
        tenantRepo.flush()
        deleteRecursively(storagePath("tenant-${tenant.id}"))
        deleteRecursively(storagePath("tenants/${tenant.id}"))
        mediaService.deleteUploadSessionsForTenant(tenant.id)
    }

    fun deleteGroup(group: TenantGroup) {
        deleteMedia(
            mediaRepo.list(
                "tenant = ?1 and scopeType = ?2 and scopeGroup = ?3",
                group.tenant,
                LibraryScopeType.Group,
                group,
            )
        )
        groupMemberRepo.delete("group", group)
        memoryRepo.delete("tenant = ?1 and scopeType = ?2 and scopeGroup = ?3", group.tenant, LibraryScopeType.Group, group)
        folderRepo.delete("tenant = ?1 and scopeType = ?2 and scopeGroup = ?3", group.tenant, LibraryScopeType.Group, group)
        group.delete()
    }

    fun deleteFolder(folder: TenantFolder) {
        val folders = descendants(folder)
        val memories = memoryRepo.list("tenant", folder.tenant)
            .filter { memory -> memory.parentFolder != null && folders.any { it.id == memory.parentFolder!!.id } }
        deleteMemories(memories)
        folder.delete()
    }

    private fun deleteMemories(memories: Collection<Memory>) {
        val expanded = memories
            .flatMap { root ->
                memoryRepo.list("tenant", root.tenant)
                    .filter { candidate -> generateSequence(candidate.parentMemory) { it.parentMemory }.any { it.id == root.id } } + root
            }
            .distinctBy { it.id }
        deleteMedia(expanded.flatMap { mediaRepo.listByMemory(it) })
        expanded.forEach { it.delete() }
    }

    private fun deleteMedia(media: Collection<Media>) {
        val distinctMedia = media.distinctBy { it.id }
        if (distinctMedia.isEmpty()) return

        memoryRepo.list("coverMedia in ?1", distinctMedia).forEach { it.coverMedia = null }
        val blobs = distinctMedia.map { it.blob }.distinctBy { it.id }
        distinctMedia.forEach { it.delete() }
        mediaRepo.flush()
        blobs.forEach(mediaService::deleteBlobIfUnreferenced)
    }

    private fun descendants(root: TenantFolder): List<TenantFolder> =
        folderRepo.listByTenant(root.tenant).filter { folder ->
            generateSequence(folder) { it.parentFolder }.any { it.id == root.id }
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
