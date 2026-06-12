package com.veyq.api.memory

import com.veyq.api.auth.filterchain.RequestContext
import com.veyq.api.domain.TenantFolderRepo
import com.veyq.api.domain.TenantGroupMemberRepo
import com.veyq.api.domain.TenantGroupRepo
import com.veyq.api.auth.NotFoundException
import com.veyq.api.generated.model.MemoryNavigationBreadcrumb
import com.veyq.api.generated.model.MemoryNavigationKind
import com.veyq.api.generated.model.MemoryNavigationLocation
import com.veyq.api.generated.model.MemoryNavigationContextResponse
import com.veyq.api.generated.model.MemorySummary
import com.veyq.api.generated.model.MemoryScopeRoot
import com.veyq.api.library.LibraryScopeKey
import com.veyq.api.library.LibraryScopes
import com.veyq.api.library.LibraryService
import com.veyq.api.library.ResolvedMemoryLocation
import jakarta.inject.Inject
import jakarta.inject.Singleton

@Singleton
class MemoryNavigationContextService @Inject constructor(
    private val folderRepo: TenantFolderRepo,
    private val groupRepo: TenantGroupRepo,
    private val groupMemberRepo: TenantGroupMemberRepo,
    private val memoryService: MemoryService,
    requestContext: RequestContext,
) : LibraryService(folderRepo, groupRepo, groupMemberRepo, requestContext) {
    fun navigationContext(path: String?): MemoryNavigationContextResponse {
        val memory = resolveMemoryPath(path)
        if (memory != null) return memoryNavigationContext(memory)

        val location = resolveVisibleContext(path)
        val parent = (location as? ResolvedMemoryLocation.Folder)?.folder
        val scope = when (location) {
            ResolvedMemoryLocation.Tenant -> LibraryScopeKey.Tenant
            is ResolvedMemoryLocation.Group -> location.scope
            is ResolvedMemoryLocation.User -> location.scope
            is ResolvedMemoryLocation.Folder -> location.folder.scope()
        }

        val locationSummary = location.toLocation()
        return MemoryNavigationContextResponse(
            location = locationSummary,
            breadcrumbs = location.toBreadcrumbs(),
            scopeRoots = if (location == ResolvedMemoryLocation.Tenant) scopeRoots() else emptyList(),
            favoriteMemories = if (location == ResolvedMemoryLocation.Tenant) memoryService.listFavoriteMemories() else emptyList(),
            memory = null,
            folders = folderRepo.listChildren(tenant(), parent)
                .filter { it.hasScope(scope) && isFolderVisible(it) }
                .map { it.toSummary() },
            memories = memoryService.listMemoriesByFolderPath(locationSummary.path),
        )
    }

    private fun resolveMemoryPath(path: String?): MemorySummary? {
        if (path?.trim()?.trim('/')?.isBlank() != false) return null
        return try {
            memoryService.getMemoryByPath(path)
        } catch (_: NotFoundException) {
            null
        }
    }

    private fun memoryNavigationContext(memory: MemorySummary): MemoryNavigationContextResponse =
        MemoryNavigationContextResponse(
            location = MemoryNavigationLocation(MemoryNavigationKind.MEMORY, memory.path, memory.displayName),
            breadcrumbs = memoryBreadcrumbs(memory),
            scopeRoots = emptyList(),
            favoriteMemories = emptyList(),
            memory = memory,
            folders = emptyList(),
            memories = emptyList(),
        )

    private fun memoryBreadcrumbs(memory: MemorySummary): List<MemoryNavigationBreadcrumb> {
        val prefixes = memory.path.split('/').filter { it.isNotBlank() }
        return prefixes.indices.mapNotNull { index ->
            val path = "/" + prefixes.take(index + 1).joinToString("/")
            if (path == memory.path) {
                MemoryNavigationBreadcrumb(MemoryNavigationKind.MEMORY, memory.path, memory.displayName, memory.displayName)
            } else {
                resolveContainerBreadcrumb(path) ?: resolveMemoryBreadcrumb(path)
            }
        }
    }

    private fun resolveContainerBreadcrumb(path: String): MemoryNavigationBreadcrumb? =
        try {
            resolveVisibleContext(path).toBreadcrumbs().lastOrNull()
        } catch (_: NotFoundException) {
            null
        }

    private fun resolveMemoryBreadcrumb(path: String): MemoryNavigationBreadcrumb? =
        try {
            val memory = memoryService.getMemoryByPath(path)
            MemoryNavigationBreadcrumb(MemoryNavigationKind.MEMORY, memory.path, memory.displayName, memory.displayName)
        } catch (_: NotFoundException) {
            null
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
