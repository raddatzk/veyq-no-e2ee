package com.lunva.api.library.explorer

import com.lunva.api.auth.filterchain.RequestContext
import com.lunva.api.generated.explorer.model.ExplorerBreadcrumb
import com.lunva.api.generated.explorer.model.ExplorerLocation
import com.lunva.api.generated.explorer.model.ExplorerResponse
import com.lunva.api.generated.explorer.model.ExplorerScopeRoot
import com.lunva.api.generated.explorer.model.LocationKind
import com.lunva.api.domain.TenantAlbum
import com.lunva.api.domain.TenantAlbumRepo
import com.lunva.api.domain.TenantFolder
import com.lunva.api.domain.TenantFolderRepo
import com.lunva.api.domain.TenantGroupMemberRepo
import com.lunva.api.domain.TenantGroupRepo
import com.lunva.api.library.LibraryScopeKey
import com.lunva.api.library.LibraryScopes
import com.lunva.api.library.LibraryService
import jakarta.inject.Inject
import jakarta.inject.Singleton

@Singleton
class ExplorerService @Inject constructor(
    private val folderRepo: TenantFolderRepo,
    private val albumRepo: TenantAlbumRepo,
    private val groupRepo: TenantGroupRepo,
    private val groupMemberRepo: TenantGroupMemberRepo,
    requestContext: RequestContext,
) : LibraryService(folderRepo, albumRepo, groupRepo, groupMemberRepo, requestContext) {

    fun explorer(path: String?): ExplorerResponse {
        val context = resolveVisibleContext(path)
        val tenant = tenant()
        return when (context) {
            is ResolvedExplorerLocation.Tenant -> ExplorerResponse(
                ExplorerLocation(LocationKind.TENANT, "/", tenant.displayName),
                listOf(ExplorerBreadcrumb(LocationKind.TENANT, "/", tenant.displayName, tenant.displayName)),
                scopeRoots(),
                rootFolders().map { it.toSummary(ownerBadge(it.scope())) },
                rootAlbums().map { it.toSummary(ownerBadge(it.scope())) },
            )

            is ResolvedExplorerLocation.Group -> ExplorerResponse(
                ExplorerLocation(LocationKind.GROUP, LibraryScopes.pathPrefix(context.scope), context.scope.group.displayName),
                listOf(
                    ExplorerBreadcrumb(LocationKind.TENANT, "/", tenant().displayName, tenant().displayName),
                    ExplorerBreadcrumb(LocationKind.GROUP, LibraryScopes.pathPrefix(context.scope), context.scope.group.displayName, "@${context.scope.group.displayName}"),
                ),
                emptyList(),
                rootFolders(context.scope).map { it.toSummary() },
                rootAlbums(context.scope).map { it.toSummary() },
            )

            is ResolvedExplorerLocation.User -> ExplorerResponse(
                ExplorerLocation(LocationKind.USER, LibraryScopes.pathPrefix(context.scope), context.scope.user.displayName),
                listOf(
                    ExplorerBreadcrumb(LocationKind.TENANT, "/", tenant().displayName, tenant().displayName),
                    ExplorerBreadcrumb(LocationKind.USER, LibraryScopes.pathPrefix(context.scope), context.scope.user.displayName, "~${context.scope.user.displayName}"),
                ),
                emptyList(),
                rootFolders(context.scope).map { it.toSummary() },
                rootAlbums(context.scope).map { it.toSummary() },
            )

            is ResolvedExplorerLocation.Folder -> ExplorerResponse(
                ExplorerLocation(LocationKind.FOLDER, context.scope.path(), context.scope.displayName),
                breadcrumbs(context.scope),
                emptyList(),
                folderRepo.listChildren(tenant, context.scope)
                    .filter { isFolderVisible(it) }
                    .map { it.toSummary() },
                albumRepo.listChildren(tenant, context.scope)
                    .filter { isAlbumVisible(it) }
                    .map { it.toSummary() },
            )

            is ResolvedExplorerLocation.Album -> ExplorerResponse(
                ExplorerLocation(LocationKind.ALBUM, context.scope.path(), context.scope.displayName),
                breadcrumbs(context.scope),
                emptyList(),
                emptyList(),
                emptyList(),
            )
        }
    }

    private fun breadcrumbs(scope: LibraryScopeKey): List<ExplorerBreadcrumb> =
        when (scope) {
            is LibraryScopeKey.Tenant -> listOf(ExplorerBreadcrumb(LocationKind.TENANT, "/", tenant().displayName, tenant().displayName))
            is LibraryScopeKey.Group -> listOf(
                ExplorerBreadcrumb(LocationKind.TENANT, "/", tenant().displayName, tenant().displayName),
                ExplorerBreadcrumb(LocationKind.GROUP, LibraryScopes.pathPrefix(scope), scope.group.displayName, "@${scope.group.displayName}"),
            )
            is LibraryScopeKey.User -> listOf(
                ExplorerBreadcrumb(LocationKind.TENANT, "/", tenant().displayName, tenant().displayName),
                ExplorerBreadcrumb(LocationKind.USER, LibraryScopes.pathPrefix(scope), scope.user.displayName, "~${scope.user.displayName}"),
            )
        }

    private fun rootFolders(): List<TenantFolder> =
        folderRepo.listChildren(tenant(), null)
            .filter { isFolderVisible(it) }
            .sortedWith(compareBy<TenantFolder> { LibraryScopes.pathPrefix(it.scope()) }.thenBy { it.displayName })

    private fun rootAlbums(): List<TenantAlbum> =
        albumRepo.listChildren(tenant(), null)
            .filter { isAlbumVisible(it) }
            .sortedWith(compareBy<TenantAlbum> { LibraryScopes.pathPrefix(it.scope()) }.thenBy { it.displayName })

    private fun rootFolders(scope: LibraryScopeKey): List<TenantFolder> =
        folderRepo.listChildren(tenant(), null)
            .filter { it.hasScope(scope) && isFolderVisible(it) }

    private fun rootAlbums(scope: LibraryScopeKey): List<TenantAlbum> =
        albumRepo.listChildren(tenant(), null)
            .filter { it.hasScope(scope) && isAlbumVisible(it) }

    private fun scopeRoots(): List<ExplorerScopeRoot> {
        val userRoot = ExplorerScopeRoot(ExplorerScopeRoot.Kind.USER, "me", user().displayName, "/~me")
        val groupRoots = groupRepo.listByTenant(tenant())
            .filter { groupMemberRepo.findByGroupAndUser(it, user()) != null }
            .sortedBy { it.displayName.lowercase() }
            .map { ExplorerScopeRoot(ExplorerScopeRoot.Kind.GROUP, it.name, it.displayName, LibraryScopes.pathPrefix(LibraryScopeKey.Group(it))) }
        return listOf(userRoot) + groupRoots
    }
}
