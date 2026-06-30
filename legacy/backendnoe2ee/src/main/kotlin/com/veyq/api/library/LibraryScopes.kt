package com.veyq.api.library

import com.veyq.api.domain.LibraryScopeType
import com.veyq.api.domain.Media
import com.veyq.api.domain.Memory
import com.veyq.api.domain.TenantFolder
import com.veyq.api.domain.TenantGroup
import com.veyq.api.domain.User

sealed class LibraryScopeKey {
    data object Tenant : LibraryScopeKey()
    data class Group(val group: TenantGroup) : LibraryScopeKey()
    data class User(val user: com.veyq.api.domain.User) : LibraryScopeKey()
}

object LibraryScopes {
    fun scope(folder: TenantFolder): LibraryScopeKey =
        fromFields(folder.scopeType, folder.scopeGroup, folder.scopeUser)

    fun scope(media: Media): LibraryScopeKey =
        fromFields(media.scopeType, media.scopeGroup, media.scopeUser)

    fun scope(memory: Memory): LibraryScopeKey =
        fromFields(memory.scopeType, memory.scopeGroup, memory.scopeUser)

    fun apply(folder: TenantFolder, scope: LibraryScopeKey) {
        folder.scopeType = scope.type()
        folder.scopeGroup = (scope as? LibraryScopeKey.Group)?.group
        folder.scopeUser = (scope as? LibraryScopeKey.User)?.user
    }

    fun apply(media: Media, scope: LibraryScopeKey) {
        media.scopeType = scope.type()
        media.scopeGroup = (scope as? LibraryScopeKey.Group)?.group
        media.scopeUser = (scope as? LibraryScopeKey.User)?.user
    }

    fun apply(memory: Memory, scope: LibraryScopeKey) {
        memory.scopeType = scope.type()
        memory.scopeGroup = (scope as? LibraryScopeKey.Group)?.group
        memory.scopeUser = (scope as? LibraryScopeKey.User)?.user
    }

    fun pathPrefix(scope: LibraryScopeKey): String =
        when (scope) {
            LibraryScopeKey.Tenant -> ""
            is LibraryScopeKey.Group -> "/@${scope.group.name}"
            is LibraryScopeKey.User -> "/~me"
        }

    fun sameAs(left: LibraryScopeKey, right: LibraryScopeKey): Boolean =
        when {
            left is LibraryScopeKey.Tenant && right is LibraryScopeKey.Tenant -> true
            left is LibraryScopeKey.Group && right is LibraryScopeKey.Group -> left.group.id == right.group.id
            left is LibraryScopeKey.User && right is LibraryScopeKey.User -> left.user.id == right.user.id
            else -> false
        }

    private fun LibraryScopeKey.type(): LibraryScopeType =
        when (this) {
            LibraryScopeKey.Tenant -> LibraryScopeType.Tenant
            is LibraryScopeKey.Group -> LibraryScopeType.Group
            is LibraryScopeKey.User -> LibraryScopeType.User
        }

    private fun fromFields(type: LibraryScopeType, group: TenantGroup?, user: User?): LibraryScopeKey =
        when (type) {
            LibraryScopeType.Tenant -> LibraryScopeKey.Tenant
            LibraryScopeType.Group -> LibraryScopeKey.Group(group ?: throw IllegalStateException("Group scope has no group"))
            LibraryScopeType.User -> LibraryScopeKey.User(user ?: throw IllegalStateException("User scope has no user"))
        }
}
