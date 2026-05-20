package com.veyq.api.library

import com.veyq.api.auth.BadRequestException
import com.veyq.api.domain.AlbumMediaRepo
import com.veyq.api.domain.LibraryScopeType
import com.veyq.api.domain.Media
import com.veyq.api.domain.MediaRepo
import com.veyq.api.domain.Tenant
import com.veyq.api.domain.TenantAlbum
import com.veyq.api.domain.TenantAlbumRepo
import com.veyq.api.domain.TenantFolder
import com.veyq.api.domain.TenantFolderRepo
import com.veyq.api.domain.TenantGroup
import com.veyq.api.domain.TenantGroupMemberRepo
import com.veyq.api.domain.TenantGroupRepo
import com.veyq.api.domain.TenantMemberRepo
import com.veyq.api.domain.TenantRepo
import com.veyq.api.library.media.MediaConfig
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
    private val albumRepo: TenantAlbumRepo,
    private val albumMediaRepo: AlbumMediaRepo,
    private val mediaRepo: MediaRepo,
    private val mediaConfig: MediaConfig,
) {
    fun deleteTenant(tenant: Tenant) {
        val media = mediaRepo.list("tenant", tenant)
        deleteMedia(media)
        tenantMemberRepo.delete("tenant", tenant)
        groupRepo.listByTenant(tenant).forEach { group -> groupMemberRepo.delete("group", group) }
        albumRepo.delete("tenant", tenant)
        folderRepo.delete("tenant", tenant)
        groupRepo.delete("tenant", tenant)
        tenantRepo.delete(tenant)
        tenantRepo.flush()
        deleteRecursively(storagePath("tenant-${tenant.id}"))
    }

    fun deleteGroup(group: TenantGroup) {
        val media = mediaRepo.list(
            "tenant = ?1 and scopeType = ?2 and scopeGroup = ?3",
            group.tenant,
            LibraryScopeType.Group,
            group,
        )
        deleteMedia(media)
        groupMemberRepo.delete("group", group)
        albumRepo.delete("tenant = ?1 and scopeType = ?2 and scopeGroup = ?3", group.tenant, LibraryScopeType.Group, group)
        folderRepo.delete("tenant = ?1 and scopeType = ?2 and scopeGroup = ?3", group.tenant, LibraryScopeType.Group, group)
        group.delete()
    }

    fun deleteFolder(folder: TenantFolder) {
        val folders = descendants(folder)
        val albums = albumRepo.listByTenant(folder.tenant)
            .filter { album -> album.parentFolder != null && folders.any { it.id == album.parentFolder!!.id } }
        deleteAlbumMedia(albums)
        folder.delete()
    }

    fun deleteAlbum(album: TenantAlbum) {
        deleteAlbumMedia(listOf(album))
        album.delete()
    }

    private fun deleteAlbumMedia(albums: Collection<TenantAlbum>) {
        val media = albums
            .flatMap { album -> albumMediaRepo.list("album", album).map { it.media } }
            .distinctBy { it.id }
        deleteMedia(media)
    }

    private fun deleteMedia(media: Collection<Media>) {
        val distinctMedia = media.distinctBy { it.id }
        if (distinctMedia.isEmpty()) return

        albumRepo.update("coverMedia = null where coverMedia in ?1", distinctMedia)
        distinctMedia.forEach { media ->
            albumMediaRepo.list("media", media).forEach { it.delete() }
        }
        albumMediaRepo.flush()

        val mediaDirectories = distinctMedia.map { mediaDirectory(it) }.distinct()
        distinctMedia.forEach { it.delete() }
        mediaRepo.flush()
        mediaDirectories.forEach(::deleteRecursively)
    }

    private fun descendants(root: TenantFolder): List<TenantFolder> =
        folderRepo.listByTenant(root.tenant).filter { folder ->
            generateSequence(folder) { it.parentFolder }.any { it.id == root.id }
        }

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
}
