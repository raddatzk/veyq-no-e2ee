package com.lunva.api.library

import com.lunva.api.auth.BadRequestException
import com.lunva.api.auth.ConflictException
import com.lunva.api.auth.NotFoundException
import com.lunva.api.domain.Tenant
import com.lunva.api.domain.TenantAlbum
import com.lunva.api.domain.TenantAlbumGroupShareRepo
import com.lunva.api.domain.TenantAlbumRepo
import com.lunva.api.domain.TenantAlbumUserShareRepo
import com.lunva.api.domain.TenantFolder
import com.lunva.api.domain.TenantFolderGroupShareRepo
import com.lunva.api.domain.TenantFolderRepo
import com.lunva.api.domain.TenantFolderUserShareRepo
import com.lunva.api.domain.TenantGroup
import com.lunva.api.domain.TenantGroupMemberRepo
import com.lunva.api.domain.User
import com.lunva.api.library.explorer.AlbumSummary
import com.lunva.api.library.explorer.ExplorerBreadcrumb
import com.lunva.api.library.explorer.ExplorerBreadcrumbType
import com.lunva.api.library.explorer.ExplorerContext
import com.lunva.api.library.explorer.FolderSummary
import com.lunva.api.security.RequestContext

abstract class LibraryService (
    private val folderRepo: TenantFolderRepo,
    private val albumRepo: TenantAlbumRepo,
    private val folderUserShareRepo: TenantFolderUserShareRepo,
    private val folderGroupShareRepo: TenantFolderGroupShareRepo,
    private val albumUserShareRepo: TenantAlbumUserShareRepo,
    private val albumGroupShareRepo: TenantAlbumGroupShareRepo,
    private val groupMemberRepo: TenantGroupMemberRepo,
    private val requestContext: RequestContext,
){
    private val validName = Regex("^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$")

    fun tenant(): Tenant = requestContext.tenant!!
    fun user(): User = requestContext.user!!

    fun breadcrumbs(folder: TenantFolder): List<ExplorerBreadcrumb> {
        val folders = generateSequence(folder) { it.parentFolder }.toList().asReversed()
        return folders.map { ExplorerBreadcrumb(ExplorerBreadcrumbType.Folder, it.name, it.displayName, it.path()) }
    }

    fun breadcrumbs(album: TenantAlbum): List<ExplorerBreadcrumb> =
        album.parentFolder?.let { breadcrumbs(it) }.orEmpty() +
                ExplorerBreadcrumb(ExplorerBreadcrumbType.Album, album.name, album.displayName, album.path())

    fun Tenant.path(): String =
        "/$name"

    fun TenantFolder.path(): String =
        (parentFolder?.path()?.trimEnd('/') ?: "") + "/" + name

    fun TenantAlbum.path(): String =
        (parentFolder?.path()?.trimEnd('/') ?: "") + "/" + name

    fun TenantFolder.toSummary(): FolderSummary =
        FolderSummary(publicId, name, displayName, path())

    fun TenantAlbum.toSummary(): AlbumSummary =
        AlbumSummary(publicId, name, displayName, path(), coverMedia?.publicId)

    fun resolveVisibleContext(path: String?): ExplorerContext {
        val context = resolveContext(path)
        when (context) {
            ExplorerContext.Root -> Unit
            is ExplorerContext.Folder -> requireVisible(context.folder)
            is ExplorerContext.Album -> requireVisible(context.album)
        }
        return context
    }

    fun resolveContext(path: String?): ExplorerContext {
        val segments = pathSegments(path)
        if (segments.isEmpty()) return ExplorerContext.Root
        var parent: TenantFolder? = null
        segments.forEachIndexed { index, segment ->
            val folder = folderRepo.listChildren(tenant(), parent).firstOrNull { it.name == segment }
            val album = albumRepo.listChildren(tenant(), parent).firstOrNull { it.name == segment }
            if (folder != null && album != null) throw ConflictException("Ambiguous path")
            if (index == segments.lastIndex) {
                if (folder != null) return ExplorerContext.Folder(folder)
                if (album != null) return ExplorerContext.Album(album)
            } else {
                parent = folder ?: throw NotFoundException("Path not found")
            }
        }
        throw NotFoundException("Path not found")
    }

    fun isFolderVisible(folder: TenantFolder): Boolean =
        isFolderDirectlyVisible(folder) || folder.parentFolder?.let { isFolderVisible(it) } == true

    fun isAlbumVisible(album: TenantAlbum): Boolean =
        isAlbumDirectlyVisible(album) || album.parentFolder?.let { isFolderVisible(it) } == true

    fun isFolderDirectlyVisible(folder: TenantFolder): Boolean =
        folder.owner.id == user().id ||
                folderUserShareRepo.find("folder = ?1 and user = ?2", folder, user()).firstResult() != null ||
                folderGroupShareRepo.list("folder", folder).any { isUserInGroup(it.group) }

    fun isAlbumDirectlyVisible(album: TenantAlbum): Boolean =
        album.owner.id == user().id ||
                albumUserShareRepo.find("album = ?1 and user = ?2", album, user()).firstResult() != null ||
                albumGroupShareRepo.list("album", album).any { isUserInGroup(it.group) }

    fun isFolderSharedWithCurrentUser(folder: TenantFolder): Boolean =
        folderUserShareRepo.find("folder = ?1 and user = ?2", folder, user()).firstResult() != null ||
                folderGroupShareRepo.list("folder", folder).any { isUserInGroup(it.group) }

    fun isAlbumSharedWithCurrentUser(album: TenantAlbum): Boolean =
        albumUserShareRepo.find("album = ?1 and user = ?2", album, user()).firstResult() != null ||
                albumGroupShareRepo.list("album", album).any { isUserInGroup(it.group) }

    fun resolveParent(path: String?): TenantFolder? {
        return when (val context = resolveVisibleContext(path)) {
            ExplorerContext.Root -> null
            is ExplorerContext.Folder -> context.folder
            is ExplorerContext.Album -> throw BadRequestException("Album cannot be parent")
        }
    }

    fun validateNoCollision(parent: TenantFolder?, name: String) {
        val folderCollision = folderRepo.listChildren(tenant(), parent).any { it.name == name }
        val albumCollision = albumRepo.listChildren(tenant(), parent).any { it.name == name }
        if (folderCollision || albumCollision) throw ConflictException("Path name already exists")
    }

    fun normalizedName(value: String?): String {
        val name = value?.trim()?.lowercase() ?: throw BadRequestException("name is required")
        if (name.length > 120 || !name.matches(validName)) throw BadRequestException("Invalid name")
        return name
    }

    fun requireVisible(folder: TenantFolder) {
        if (!isFolderVisible(folder)) throw NotFoundException("Folder not found")
    }

    fun requireVisible(album: TenantAlbum) {
        if (!isAlbumVisible(album)) throw NotFoundException("Album not found")
    }

    private fun isUserInGroup(group: TenantGroup): Boolean =
        group.tenant.id == tenant().id && groupMemberRepo.findByGroupAndUser(group, user()) != null

    private fun pathSegments(path: String?): List<String> =
        path?.trim()?.trim('/')?.split('/')?.filter { it.isNotBlank() } ?: emptyList()
}
