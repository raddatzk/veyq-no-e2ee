package com.lunva.api.library

import com.lunva.api.auth.BadRequestException
import com.lunva.api.auth.ConflictException
import com.lunva.api.auth.NotFoundException
import com.lunva.api.auth.filterchain.RequestContext
import com.lunva.api.domain.Tenant
import com.lunva.api.domain.TenantAlbum
import com.lunva.api.domain.TenantAlbumRepo
import com.lunva.api.domain.TenantFolder
import com.lunva.api.domain.TenantFolderRepo
import com.lunva.api.domain.TenantGroup
import com.lunva.api.domain.TenantGroupMemberRepo
import com.lunva.api.domain.TenantGroupRepo
import com.lunva.api.domain.User
import com.lunva.api.generated.explorer.model.ExplorerAlbum
import com.lunva.api.generated.explorer.model.ExplorerBreadcrumb
import com.lunva.api.generated.explorer.model.ExplorerFolder
import com.lunva.api.generated.explorer.model.LocationKind
import com.lunva.api.generated.explorer.model.OwnerBadge
import com.lunva.api.library.explorer.ResolvedExplorerLocation

abstract class LibraryService(
    private val folderRepo: TenantFolderRepo,
    private val albumRepo: TenantAlbumRepo,
    private val groupRepo: TenantGroupRepo,
    private val groupMemberRepo: TenantGroupMemberRepo,
    protected val requestContext: RequestContext,
) {
    private val validName = Regex("^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$")

    fun tenant(): Tenant = requestContext.tenant!!
    fun user(): User = requestContext.user!!

    fun breadcrumbs(folder: TenantFolder): List<ExplorerBreadcrumb> {
        val folders = generateSequence(folder) { it.parentFolder }.toList().asReversed()
        return scopeBreadcrumbs(folder.scope()) +
            folders.map { ExplorerBreadcrumb(LocationKind.FOLDER, it.path(), it.displayName, it.displayName) }
    }

    fun breadcrumbs(album: TenantAlbum): List<ExplorerBreadcrumb> =
        (album.parentFolder?.let { breadcrumbs(it) } ?: scopeBreadcrumbs(album.scope())) +
        ExplorerBreadcrumb(LocationKind.ALBUM, album.path(), album.displayName, album.displayName)

    fun TenantFolder.path(): String =
        (parentFolder?.path()?.trimEnd('/') ?: LibraryScopes.pathPrefix(scope()).trimEnd('/')) + "/" + name

    fun TenantAlbum.path(): String =
        (parentFolder?.path()?.trimEnd('/') ?: LibraryScopes.pathPrefix(scope()).trimEnd('/')) + "/" + name

    fun TenantFolder.toSummary(ownerBadge: OwnerBadge? = null): ExplorerFolder =
        ExplorerFolder(publicId, name, displayName, path()).ownerBadge(ownerBadge)

    fun TenantAlbum.toSummary(ownerBadge: OwnerBadge? = null): ExplorerAlbum =
        ExplorerAlbum(publicId, name, displayName, path(), coverMedia?.publicId).ownerBadge(ownerBadge)

    fun resolveVisibleContext(path: String?): ResolvedExplorerLocation {
        val context = resolveContext(path)
        when (context) {
            is ResolvedExplorerLocation.Tenant -> Unit
            is ResolvedExplorerLocation.Group -> requireScopeVisible(context.scope)
            is ResolvedExplorerLocation.User -> requireScopeVisible(context.scope)
            is ResolvedExplorerLocation.Folder -> requireVisible(context.scope)
            is ResolvedExplorerLocation.Album -> requireVisible(context.scope)
        }
        return context
    }

    fun resolveContext(path: String?): ResolvedExplorerLocation {
        val parsed = parseScopedPath(path)
        val parsedScope = parsed.scope ?: return ResolvedExplorerLocation.Tenant
        if (parsed.segments.isEmpty()) {
            return when (parsedScope) {
                is LibraryScopeKey.Tenant -> ResolvedExplorerLocation.Tenant
                is LibraryScopeKey.Group -> ResolvedExplorerLocation.Group(parsedScope)
                is LibraryScopeKey.User -> ResolvedExplorerLocation.User(parsedScope)
            }
        }

        var parent: TenantFolder? = null
        parsed.segments.forEachIndexed { index, segment ->
            val folder = folderRepo.listChildren(tenant(), parent)
                .firstOrNull { it.name == segment && it.hasScope(parsedScope) }
            val album = albumRepo.listChildren(tenant(), parent)
                .firstOrNull { it.name == segment && it.hasScope(parsedScope) }
            if (folder != null && album != null) throw ConflictException("Ambiguous path")
            if (index == parsed.segments.lastIndex) {
                if (folder != null) return ResolvedExplorerLocation.Folder(folder)
                if (album != null) return ResolvedExplorerLocation.Album(album)
            } else {
                parent = folder ?: throw NotFoundException("Path not found")
            }
        }
        throw NotFoundException("Path not found")
    }

    fun isFolderVisible(folder: TenantFolder): Boolean = isScopeVisible(folder.scope())

    fun isAlbumVisible(album: TenantAlbum): Boolean = isScopeVisible(album.scope())

    fun resolveParent(path: String?): TenantFolder? {
        return when (val context = resolveVisibleContext(path)) {
            ResolvedExplorerLocation.Tenant -> null
            is ResolvedExplorerLocation.Group -> null
            is ResolvedExplorerLocation.User -> null
            is ResolvedExplorerLocation.Folder -> context.scope
            is ResolvedExplorerLocation.Album -> throw BadRequestException("Album cannot be parent")
        }
    }

    fun resolveCreateScope(parent: TenantFolder?, request: CreateLibraryItemRequest): LibraryScopeKey =
        parent?.scope() ?: resolveRequestedRootScope(request.scope)

    fun validateNoCollision(parent: TenantFolder?, scope: LibraryScopeKey, name: String) {
        val folderCollision = folderRepo.listChildren(tenant(), parent).any { it.name == name && it.hasScope(scope) }
        val albumCollision = albumRepo.listChildren(tenant(), parent).any { it.name == name && it.hasScope(scope) }
        if (folderCollision || albumCollision) throw ConflictException("Path name already exists")
    }

    fun requireSameScope(itemScope: LibraryScopeKey, parent: TenantFolder?) {
        if (parent != null && !parent.hasScope(itemScope)) {
            throw BadRequestException("Cannot move library item between scopes")
        }
    }

    fun requireMoveTargetScope(itemScope: LibraryScopeKey, newParentPath: String?) {
        val context = resolveVisibleContext(newParentPath)
        val targetScope = when (context) {
            is ResolvedExplorerLocation.Group -> context.scope
            is ResolvedExplorerLocation.User -> context.scope
            else -> null
        }
        if (targetScope != null && !targetScope.sameAs(itemScope)) {
            throw BadRequestException("Cannot move library item between scopes")
        }
    }

    fun ownerBadge(scope: LibraryScopeKey): OwnerBadge =
        when (scope) {
            LibraryScopeKey.Tenant -> OwnerBadge(OwnerBadge.KindEnum.TENANT, tenant().displayName)
            is LibraryScopeKey.Group -> OwnerBadge(OwnerBadge.KindEnum.GROUP, scope.group.displayName).name(scope.group.name)
            is LibraryScopeKey.User -> OwnerBadge(OwnerBadge.KindEnum.USER, scope.user.displayName).name("me")
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

    fun applyScope(folder: TenantFolder, scope: LibraryScopeKey) {
        LibraryScopes.apply(folder, scope)
    }

    fun applyScope(album: TenantAlbum, scope: LibraryScopeKey) {
        LibraryScopes.apply(album, scope)
    }

    fun applyScope(media: com.lunva.api.domain.Media, scope: LibraryScopeKey) {
        LibraryScopes.apply(media, scope)
    }

    fun TenantFolder.scope(): LibraryScopeKey =
        LibraryScopes.scope(this)

    fun TenantAlbum.scope(): LibraryScopeKey =
        LibraryScopes.scope(this)

    fun com.lunva.api.domain.Media.scope(): LibraryScopeKey =
        LibraryScopes.scope(this)

    fun TenantFolder.hasScope(scope: LibraryScopeKey): Boolean = scope().sameAs(scope)

    fun TenantAlbum.hasScope(scope: LibraryScopeKey): Boolean = scope().sameAs(scope)

    fun LibraryScopeKey.sameAs(other: LibraryScopeKey): Boolean =
        LibraryScopes.sameAs(this, other)

    private fun requireScopeVisible(scope: LibraryScopeKey) {
        if (!isScopeVisible(scope)) throw NotFoundException("Path not found")
    }

    private fun isScopeVisible(scope: LibraryScopeKey): Boolean =
        when (scope) {
            LibraryScopeKey.Tenant -> true
            is LibraryScopeKey.Group -> isUserInGroup(scope.group)
            is LibraryScopeKey.User -> scope.user.id == user().id
        }

    private fun resolveRequestedRootScope(scope: LibraryScopeRequest?): LibraryScopeKey =
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

    private fun scopeBreadcrumbs(scope: LibraryScopeKey): List<ExplorerBreadcrumb> =
        listOf(ExplorerBreadcrumb(LocationKind.TENANT, "/", tenant().displayName, tenant().displayName)) +
            when (scope) {
                LibraryScopeKey.Tenant -> emptyList()
                is LibraryScopeKey.Group -> listOf(
                    ExplorerBreadcrumb(
                        LocationKind.GROUP,
                        LibraryScopes.pathPrefix(scope),
                        scope.group.displayName,
                        "@${scope.group.displayName}",
                    )
                )
                is LibraryScopeKey.User -> listOf(
                    ExplorerBreadcrumb(
                        LocationKind.USER,
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
