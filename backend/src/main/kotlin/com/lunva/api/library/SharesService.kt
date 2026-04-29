package com.lunva.api.library

import com.lunva.api.auth.BadRequestException
import com.lunva.api.domain.*
import com.lunva.api.auth.filterchain.RequestContext

abstract class SharesService(
    private val folderUserShareRepo: TenantFolderUserShareRepo,
    private val folderGroupShareRepo: TenantFolderGroupShareRepo,
    private val albumUserShareRepo: TenantAlbumUserShareRepo,
    private val albumGroupShareRepo: TenantAlbumGroupShareRepo,
    folderRepo: TenantFolderRepo,
    albumRepo: TenantAlbumRepo,
    groupMemberRepo: TenantGroupMemberRepo,
    requestContext: RequestContext,
) : LibraryService(folderRepo, albumRepo, folderUserShareRepo, folderGroupShareRepo, albumUserShareRepo, albumGroupShareRepo, groupMemberRepo, requestContext) {

    fun shares(folder: TenantFolder): LibrarySharesSummary {
        requireVisible(folder)
        return LibrarySharesSummary(
            users = folderUserShareRepo.list("folder", folder).map { UserShareSummary(it.user.id, it.user.email, it.user.displayName) },
            groups = folderGroupShareRepo.list("folder", folder).map { GroupShareSummary(it.group.id, it.group.name, it.group.displayName) },
        )
    }

    fun shares(album: TenantAlbum): LibrarySharesSummary {
        requireVisible(album)
        return LibrarySharesSummary(
            users = albumUserShareRepo.list("album", album).map { UserShareSummary(it.user.id, it.user.email, it.user.displayName) },
            groups = albumGroupShareRepo.list("album", album).map { GroupShareSummary(it.group.id, it.group.name, it.group.displayName) },
        )
    }

    fun requireGroupInTenant(group: TenantGroup) {
        if (group.tenant.id != tenant().id) throw BadRequestException("Group is not part of this tenant")
    }
}