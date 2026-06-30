package com.veyq.api.library

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
import com.veyq.api.domain.TenantRegistrationInviteRepo
import com.veyq.api.domain.TenantRepo
import com.veyq.api.library.media.MediaService
import com.veyq.api.library.media.MediaStorageService
import jakarta.inject.Inject
import jakarta.inject.Singleton

@Singleton
class LibraryDeletionService @Inject constructor(
    private val tenantRepo: TenantRepo,
    private val tenantMemberRepo: TenantMemberRepo,
    private val tenantRegistrationInviteRepo: TenantRegistrationInviteRepo,
    private val groupRepo: TenantGroupRepo,
    private val groupMemberRepo: TenantGroupMemberRepo,
    private val folderRepo: TenantFolderRepo,
    private val memoryRepo: MemoryRepo,
    private val mediaRepo: MediaRepo,
    private val mediaService: MediaService,
    private val mediaStorageService: MediaStorageService,
) {
    fun deleteTenant(tenant: Tenant) {
        deleteMedia(mediaRepo.list("tenant", tenant))
        tenantRegistrationInviteRepo.deleteByTenant(tenant)
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
        val folders = folderRepo.listDescendantsAndSelf(folder.tenant, folder)
        val memories = memoryRepo.listByParentFolders(folder.tenant, folders)
        deleteMemories(memories)
        folders.sortedByDescending(::folderDepth).forEach { it.delete() }
    }

    private fun deleteMemories(memories: Collection<Memory>) {
        val expanded = memories
            .flatMap { root -> memoryRepo.listDescendantsAndSelf(root.tenant, root) }
            .distinctBy { it.id }
        deleteMedia(mediaRepo.listByMemoriesOrdered(expanded))
        expanded.sortedByDescending(::memoryDepth).forEach { it.delete() }
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

    private fun folderDepth(folder: TenantFolder): Int =
        generateSequence(folder.parentFolder) { it.parentFolder }.count()

    private fun memoryDepth(memory: Memory): Int =
        generateSequence(memory.parentMemory) { it.parentMemory }.count()

    private fun storagePath(storageKey: String) =
        mediaStorageService.path(storageKey)

    private fun deleteRecursively(path: java.nio.file.Path) =
        mediaStorageService.deleteRecursively(path)
}
