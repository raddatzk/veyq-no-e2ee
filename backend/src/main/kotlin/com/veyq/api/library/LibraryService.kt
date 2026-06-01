package com.veyq.api.library

import com.veyq.api.auth.BadRequestException
import com.veyq.api.auth.ConflictException
import com.veyq.api.auth.NotFoundException
import com.veyq.api.auth.filterchain.RequestContext
import com.veyq.api.domain.Media
import com.veyq.api.domain.Memory
import com.veyq.api.domain.Tenant
import com.veyq.api.domain.TenantFolder
import com.veyq.api.domain.TenantFolderRepo
import com.veyq.api.domain.TenantGroup
import com.veyq.api.domain.TenantGroupMemberRepo
import com.veyq.api.domain.TenantGroupRepo
import com.veyq.api.domain.User
import com.veyq.api.generated.model.MemoryFolder
import com.veyq.api.generated.model.MemoryNavigationBreadcrumb
import com.veyq.api.generated.model.MemoryNavigationKind
import com.veyq.api.generated.model.MemoryScopeRequest

abstract class LibraryService(
    private val folderRepo: TenantFolderRepo,
    private val groupRepo: TenantGroupRepo,
    private val groupMemberRepo: TenantGroupMemberRepo,
    protected val requestContext: RequestContext,
) {
    private val validName = Regex("^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$")
    private val reservedNames = setOf("settings")

    fun tenant(): Tenant = requestContext.tenant!!
    fun user(): User = requestContext.user!!

    fun breadcrumbs(folder: TenantFolder): List<MemoryNavigationBreadcrumb> {
        val folders = generateSequence(folder) { it.parentFolder }.toList().asReversed()
        return scopeBreadcrumbs(folder.scope()) +
            folders.map { MemoryNavigationBreadcrumb(MemoryNavigationKind.FOLDER, it.path(), it.displayName, it.displayName) }
    }

    fun TenantFolder.path(): String =
        (parentFolder?.path()?.trimEnd('/') ?: LibraryScopes.pathPrefix(scope()).trimEnd('/')) + "/" + name

    fun TenantFolder.toSummary(): MemoryFolder =
        MemoryFolder(publicId, name, displayName, path())

    fun resolveVisibleContext(path: String?): ResolvedMemoryLocation {
        val context = resolveContext(path)
        when (context) {
            ResolvedMemoryLocation.Tenant -> Unit
            is ResolvedMemoryLocation.Group -> requireScopeVisible(context.scope)
            is ResolvedMemoryLocation.User -> requireScopeVisible(context.scope)
            is ResolvedMemoryLocation.Folder -> requireVisible(context.folder)
        }
        return context
    }

    fun resolveContext(path: String?): ResolvedMemoryLocation {
        val parsed = parseScopedPath(path)
        val parsedScope = parsed.scope ?: return ResolvedMemoryLocation.Tenant
        if (parsed.segments.isEmpty()) {
            return when (parsedScope) {
                is LibraryScopeKey.Tenant -> ResolvedMemoryLocation.Tenant
                is LibraryScopeKey.Group -> ResolvedMemoryLocation.Group(parsedScope)
                is LibraryScopeKey.User -> ResolvedMemoryLocation.User(parsedScope)
            }
        }

        var parent: TenantFolder? = null
        parsed.segments.forEachIndexed { index, segment ->
            val folder = folderRepo.listChildren(tenant(), parent)
                .firstOrNull { it.name == segment && it.hasScope(parsedScope) }
            if (index == parsed.segments.lastIndex) {
                if (folder != null) return ResolvedMemoryLocation.Folder(folder)
            } else {
                parent = folder ?: throw NotFoundException("Path not found")
            }
        }
        throw NotFoundException("Path not found")
    }

    fun isFolderVisible(folder: TenantFolder): Boolean = isScopeVisible(folder.scope())

    fun resolveParent(path: String?): TenantFolder? =
        when (val context = resolveVisibleContext(path)) {
            ResolvedMemoryLocation.Tenant -> null
            is ResolvedMemoryLocation.Group -> null
            is ResolvedMemoryLocation.User -> null
            is ResolvedMemoryLocation.Folder -> context.folder
        }

    fun resolveCreateScope(parent: TenantFolder?, request: MemoryScopeRequest?): LibraryScopeKey =
        parent?.scope() ?: resolveRequestedRootScope(request)

    fun validateNoFolderCollision(parent: TenantFolder?, scope: LibraryScopeKey, name: String) {
        val folderCollision = folderRepo.listChildren(tenant(), parent).any { it.name == name && it.hasScope(scope) }
        if (folderCollision) throw ConflictException("Path name already exists")
    }

    fun requireSameScope(itemScope: LibraryScopeKey, parent: TenantFolder?) {
        if (parent != null && !parent.hasScope(itemScope)) {
            throw BadRequestException("Cannot move item between scopes")
        }
    }

    fun requireMoveTargetScope(itemScope: LibraryScopeKey, newParentPath: String?) {
        val context = resolveVisibleContext(newParentPath)
        val targetScope = when (context) {
            is ResolvedMemoryLocation.Group -> context.scope
            is ResolvedMemoryLocation.User -> context.scope
            else -> null
        }
        if (targetScope != null && !targetScope.sameAs(itemScope)) {
            throw BadRequestException("Cannot move item between scopes")
        }
    }

    fun normalizedName(
        value: String?,
        requiredCode: String = "BAD_REQUEST",
        invalidCode: String = "BAD_REQUEST",
    ): String {
        val name = value?.trim()?.lowercase() ?: throw BadRequestException("name is required", requiredCode)
        if (name.length > 120 || !name.matches(validName) || name in reservedNames) {
            throw BadRequestException("Invalid name", invalidCode)
        }
        return name
    }

    fun requireVisible(folder: TenantFolder) {
        if (!isFolderVisible(folder)) throw NotFoundException("Folder not found")
    }

    fun applyScope(folder: TenantFolder, scope: LibraryScopeKey) {
        LibraryScopes.apply(folder, scope)
    }

    fun applyScope(media: Media, scope: LibraryScopeKey) {
        LibraryScopes.apply(media, scope)
    }

    fun TenantFolder.scope(): LibraryScopeKey =
        LibraryScopes.scope(this)

    fun Media.scope(): LibraryScopeKey =
        LibraryScopes.scope(this)

    fun Memory.scope(): LibraryScopeKey =
        LibraryScopes.scope(this)

    fun TenantFolder.hasScope(scope: LibraryScopeKey): Boolean = scope().sameAs(scope)

    fun LibraryScopeKey.sameAs(other: LibraryScopeKey): Boolean =
        LibraryScopes.sameAs(this, other)

    fun isScopeVisible(scope: LibraryScopeKey): Boolean =
        when (scope) {
            LibraryScopeKey.Tenant -> true
            is LibraryScopeKey.Group -> isUserInGroup(scope.group)
            is LibraryScopeKey.User -> scope.user.id == user().id
        }

    private fun requireScopeVisible(scope: LibraryScopeKey) {
        if (!isScopeVisible(scope)) throw NotFoundException("Path not found")
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
        }.also { requireScopeVisible(it) }

    private fun parseScopedPath(path: String?): ParsedScopedPath {
        val segments = pathSegments(path)
        if (segments.isEmpty()) return ParsedScopedPath(null, emptyList())
        val first = segments.first()
        return when {
            first.startsWith("@") -> {
                val groupName = first.removePrefix("@").takeIf { it.isNotBlank() }
                    ?: throw NotFoundException("Path not found")
                ParsedScopedPath(LibraryScopeKey.Group(resolveGroup(groupName)), segments.drop(1))
            }
            first == "~me" -> ParsedScopedPath(LibraryScopeKey.User(user()), segments.drop(1))
            else -> ParsedScopedPath(LibraryScopeKey.Tenant, segments)
        }
    }

    private fun resolveGroup(name: String): TenantGroup =
        groupRepo.findByTenantAndName(tenant(), name) ?: throw NotFoundException("Group not found")

    private fun isUserInGroup(group: TenantGroup): Boolean =
        group.tenant.id == tenant().id && groupMemberRepo.findByGroupAndUser(group, user()) != null

    private fun scopeBreadcrumbs(scope: LibraryScopeKey): List<MemoryNavigationBreadcrumb> =
        listOf(MemoryNavigationBreadcrumb(MemoryNavigationKind.TENANT, "/", tenant().displayName, tenant().displayName)) +
            when (scope) {
                LibraryScopeKey.Tenant -> emptyList()
                is LibraryScopeKey.Group -> listOf(
                    MemoryNavigationBreadcrumb(
                        MemoryNavigationKind.GROUP,
                        LibraryScopes.pathPrefix(scope),
                        scope.group.displayName,
                        "@${scope.group.displayName}",
                    )
                )
                is LibraryScopeKey.User -> listOf(
                    MemoryNavigationBreadcrumb(
                        MemoryNavigationKind.USER,
                        LibraryScopes.pathPrefix(scope),
                        scope.user.displayName,
                        "~${scope.user.displayName}",
                    )
                )
            }

    private fun pathSegments(path: String?): List<String> =
        path?.trim()?.trim('/')?.split('/')?.filter { it.isNotBlank() } ?: emptyList()

    private data class ParsedScopedPath(
        val scope: LibraryScopeKey?,
        val segments: List<String>,
    )
}

sealed class ResolvedMemoryLocation {
    data object Tenant : ResolvedMemoryLocation()
    data class Group(val scope: LibraryScopeKey.Group) : ResolvedMemoryLocation()
    data class User(val scope: LibraryScopeKey.User) : ResolvedMemoryLocation()
    data class Folder(val folder: TenantFolder) : ResolvedMemoryLocation()
}
