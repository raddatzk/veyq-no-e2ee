package com.lunva.api.library.album

import com.lunva.api.auth.NotFoundException
import com.lunva.api.domain.*
import com.lunva.api.library.LibrarySharesSummary
import com.lunva.api.library.ShareGroupRequest
import com.lunva.api.library.ShareUserRequest
import com.lunva.api.library.SharesService
import com.lunva.api.library.explorer.ExplorerContext
import com.lunva.api.auth.filterchain.RequestContext
import jakarta.inject.Inject
import jakarta.inject.Singleton
import jakarta.transaction.Transactional

@Singleton
class AlbumSharesService @Inject constructor(
    private val userRepo: UserRepo,
    private val groupRepo: TenantGroupRepo,
    private val albumUserShareRepo: TenantAlbumUserShareRepo,
    private val albumGroupShareRepo: TenantAlbumGroupShareRepo,
    folderUserShareRepo: TenantFolderUserShareRepo,
    folderGroupShareRepo: TenantFolderGroupShareRepo,
    groupMemberRepo: TenantGroupMemberRepo,
    requestContext: RequestContext,
    folderRepo: TenantFolderRepo,
    albumRepo: TenantAlbumRepo,
) : SharesService(folderUserShareRepo, folderGroupShareRepo, albumUserShareRepo, albumGroupShareRepo, folderRepo, albumRepo, groupMemberRepo, requestContext) {

    fun albumShares(path: String?): LibrarySharesSummary = shares(resolveAlbum(path))

    @Transactional
    fun shareAlbumWithUser(path: String?, request: ShareUserRequest): LibrarySharesSummary {
        val album = resolveAlbum(path)
        requireVisible(album)
        val target = userRepo.findById(request.userId!!) ?: throw NotFoundException("User not found")
        if (albumUserShareRepo.find("album = ?1 and user = ?2", album, target).firstResult() == null) {
            albumUserShareRepo.persist(TenantAlbumUserShare().also {
                it.id = TenantAlbumUserShareId(album.id, target.id)
                it.album = album
                it.user = target
            })
        }
        return shares(album)
    }

    @Transactional
    fun shareAlbumWithGroup(path: String?, request: ShareGroupRequest): LibrarySharesSummary {
        val album = resolveAlbum(path)
        requireVisible(album)
        val group = groupRepo.findById(request.groupId!!) ?: throw NotFoundException("Group not found")
        requireGroupInTenant(group)
        if (albumGroupShareRepo.find("album = ?1 and group = ?2", album, group).firstResult() == null) {
            albumGroupShareRepo.persist(TenantAlbumGroupShare().also {
                it.id = TenantAlbumGroupShareId(album.id, group.id)
                it.album = album
                it.group = group
            })
        }
        return shares(album)
    }

    @Transactional
    fun unshareAlbumWithUser(path: String?, userId: Long): LibrarySharesSummary {
        val album = resolveAlbum(path)
        requireVisible(album)
        albumUserShareRepo.find("album = ?1 and user.id = ?2", album, userId).firstResult()?.delete()
        return shares(album)
    }

    @Transactional
    fun unshareAlbumWithGroup(path: String?, groupId: Long): LibrarySharesSummary {
        val album = resolveAlbum(path)
        requireVisible(album)
        albumGroupShareRepo.find("album = ?1 and group.id = ?2", album, groupId).firstResult()?.delete()
        return shares(album)
    }

    private fun resolveAlbum(path: String?): TenantAlbum =
        (resolveVisibleContext(path) as? ExplorerContext.Album)?.album ?: throw NotFoundException("Album not found")
}
