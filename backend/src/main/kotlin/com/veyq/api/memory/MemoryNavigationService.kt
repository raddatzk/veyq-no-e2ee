package com.veyq.api.memory

import com.veyq.api.auth.filterchain.RequestContext
import com.veyq.api.domain.TenantFolderRepo
import com.veyq.api.domain.TenantGroupMemberRepo
import com.veyq.api.domain.TenantGroupRepo
import com.veyq.api.generated.model.MemoryNavigationKind
import com.veyq.api.generated.model.MemoryNavigationLocation
import com.veyq.api.generated.model.MemoryNavigationResponse
import com.veyq.api.generated.model.MemoryScopeRoot
import com.veyq.api.library.LibraryScopeKey
import com.veyq.api.library.LibraryScopes
import com.veyq.api.library.LibraryService
import com.veyq.api.library.ResolvedMemoryLocation
import jakarta.inject.Inject
import jakarta.inject.Singleton

@Singleton
class MemoryNavigationService @Inject constructor(
    private val folderRepo: TenantFolderRepo,
    private val groupRepo: TenantGroupRepo,
    private val groupMemberRepo: TenantGroupMemberRepo,
    requestContext: RequestContext,
) : LibraryService(folderRepo, groupRepo, groupMemberRepo, requestContext) {
    fun navigation(path: String?): MemoryNavigationResponse {
        val location = resolveVisibleContext(path)
        val parent = (location as? ResolvedMemoryLocation.Folder)?.folder
        val scope = when (location) {
            ResolvedMemoryLocation.Tenant -> LibraryScopeKey.Tenant
            is ResolvedMemoryLocation.Group -> location.scope
            is ResolvedMemoryLocation.User -> location.scope
            is ResolvedMemoryLocation.Folder -> location.folder.scope()
        }

        return MemoryNavigationResponse(
            location = location.toLocation(),
            breadcrumbs = location.toBreadcrumbs(),
            scopeRoots = if (location == ResolvedMemoryLocation.Tenant) scopeRoots() else emptyList(),
            folders = folderRepo.listChildren(tenant(), parent)
                .filter { it.hasScope(scope) && isFolderVisible(it) }
                .map { it.toSummary() },
        )
    }

    private fun ResolvedMemoryLocation.toLocation(): MemoryNavigationLocation =
        when (this) {
            ResolvedMemoryLocation.Tenant -> MemoryNavigationLocation(MemoryNavigationKind.TENANT, "/", tenant().displayName)
            is ResolvedMemoryLocation.Group -> MemoryNavigationLocation(
                MemoryNavigationKind.GROUP,
                LibraryScopes.pathPrefix(scope),
                scope.group.displayName,
            )
            is ResolvedMemoryLocation.User -> MemoryNavigationLocation(
                MemoryNavigationKind.USER,
                LibraryScopes.pathPrefix(scope),
                scope.user.displayName,
            )
            is ResolvedMemoryLocation.Folder -> MemoryNavigationLocation(
                MemoryNavigationKind.FOLDER,
                folder.path(),
                folder.displayName,
            )
        }

    private fun ResolvedMemoryLocation.toBreadcrumbs() =
        when (this) {
            ResolvedMemoryLocation.Tenant -> listOf(
                com.veyq.api.generated.model.MemoryNavigationBreadcrumb(
                    MemoryNavigationKind.TENANT,
                    "/",
                    tenant().displayName,
                    tenant().displayName,
                )
            )
            is ResolvedMemoryLocation.Group -> listOf(
                com.veyq.api.generated.model.MemoryNavigationBreadcrumb(
                    MemoryNavigationKind.TENANT,
                    "/",
                    tenant().displayName,
                    tenant().displayName,
                ),
                com.veyq.api.generated.model.MemoryNavigationBreadcrumb(
                    MemoryNavigationKind.GROUP,
                    LibraryScopes.pathPrefix(scope),
                    scope.group.displayName,
                    "@${scope.group.displayName}",
                ),
            )
            is ResolvedMemoryLocation.User -> listOf(
                com.veyq.api.generated.model.MemoryNavigationBreadcrumb(
                    MemoryNavigationKind.TENANT,
                    "/",
                    tenant().displayName,
                    tenant().displayName,
                ),
                com.veyq.api.generated.model.MemoryNavigationBreadcrumb(
                    MemoryNavigationKind.USER,
                    LibraryScopes.pathPrefix(scope),
                    scope.user.displayName,
                    "~${scope.user.displayName}",
                ),
            )
            is ResolvedMemoryLocation.Folder -> breadcrumbs(folder)
        }

    private fun scopeRoots(): List<MemoryScopeRoot> =
        listOf(MemoryScopeRoot(MemoryScopeRoot.Kind.USER, "me", user().displayName, "/~me")) +
            groupRepo.listByTenant(tenant())
                .filter { groupMemberRepo.findByGroupAndUser(it, user()) != null }
                .map { group -> MemoryScopeRoot(MemoryScopeRoot.Kind.GROUP, group.name, group.displayName, "/@${group.name}") }
}
