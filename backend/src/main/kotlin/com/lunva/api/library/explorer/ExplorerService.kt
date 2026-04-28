package com.lunva.api.library.explorer

import com.lunva.api.domain.*
import com.lunva.api.library.LibraryService
import com.lunva.api.security.RequestContext
import jakarta.inject.Inject
import jakarta.inject.Singleton

@Singleton
class ExplorerService @Inject constructor(
    private val folderRepo: TenantFolderRepo,
    private val albumRepo: TenantAlbumRepo,
    folderUserShareRepo: TenantFolderUserShareRepo,
    folderGroupShareRepo: TenantFolderGroupShareRepo,
    albumUserShareRepo: TenantAlbumUserShareRepo,
    albumGroupShareRepo: TenantAlbumGroupShareRepo,
    groupMemberRepo: TenantGroupMemberRepo,
    requestContext: RequestContext,
) : LibraryService(folderRepo, albumRepo, folderUserShareRepo, folderGroupShareRepo, albumUserShareRepo, albumGroupShareRepo, groupMemberRepo, requestContext) {

    fun explorer(path: String?): ExplorerSummary {
        val context = resolveVisibleContext(path)
        val tenant = tenant()
        return when (context) {
            ExplorerContext.Root -> ExplorerSummary(
                context = ExplorerContextSummary(ExplorerBreadcrumbType.Tenant, "", tenant.displayName),
                breadcrumbs = emptyList(),
                folders = rootFolders().map { it.toSummary() },
                albums = rootAlbums().map { it.toSummary() },
            )

            is ExplorerContext.Folder -> ExplorerSummary(
                context = ExplorerContextSummary(ExplorerBreadcrumbType.Folder, context.folder.path(), context.folder.displayName),
                breadcrumbs = breadcrumbs(context.folder),
                folders = folderRepo.listChildren(tenant, context.folder)
                    .filter { isFolderVisible(it) }
                    .map { it.toSummary() },
                albums = albumRepo.listChildren(tenant, context.folder)
                    .filter { isAlbumVisible(it) }
                    .map { it.toSummary() },
            )

            is ExplorerContext.Album -> ExplorerSummary(
                context = ExplorerContextSummary(ExplorerBreadcrumbType.Album, context.album.path(), context.album.displayName),
                breadcrumbs = breadcrumbs(context.album),
                folders = emptyList(),
                albums = emptyList(),
            )
        }
    }

    private fun rootFolders(): List<TenantFolder> {
        val visibleRoot = folderRepo.listChildren(tenant(), null).filter { isFolderVisible(it) }
        val directShared = folderRepo.listByTenant(tenant()).filter { it.parentFolder != null && isFolderSharedWithCurrentUser(it) }
        return (visibleRoot + directShared).distinctBy { it.id }.sortedBy { it.displayName }
    }

    private fun rootAlbums(): List<TenantAlbum> {
        val visibleRoot = albumRepo.listChildren(tenant(), null).filter { isAlbumVisible(it) }
        val directShared = albumRepo.listByTenant(tenant()).filter { it.parentFolder != null && isAlbumSharedWithCurrentUser(it) }
        return (visibleRoot + directShared).distinctBy { it.id }.sortedBy { it.displayName }
    }
}
