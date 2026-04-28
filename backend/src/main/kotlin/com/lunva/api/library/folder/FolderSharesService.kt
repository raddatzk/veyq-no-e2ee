package com.lunva.api.library.folder

import com.lunva.api.auth.NotFoundException
import com.lunva.api.domain.*
import com.lunva.api.library.LibrarySharesSummary
import com.lunva.api.library.ShareGroupRequest
import com.lunva.api.library.ShareUserRequest
import com.lunva.api.library.SharesService
import com.lunva.api.library.explorer.ExplorerContext
import com.lunva.api.security.RequestContext
import jakarta.inject.Inject
import jakarta.inject.Singleton
import jakarta.transaction.Transactional

@Singleton
class FolderSharesService @Inject constructor(
    private val userRepo: UserRepo,
    private val groupRepo: TenantGroupRepo,
    private val folderUserShareRepo: TenantFolderUserShareRepo,
    private val folderGroupShareRepo: TenantFolderGroupShareRepo,
    private val albumUserShareRepo: TenantAlbumUserShareRepo,
    private val albumGroupShareRepo: TenantAlbumGroupShareRepo,
    folderRepo: TenantFolderRepo,
    albumRepo: TenantAlbumRepo,
    groupMemberRepo: TenantGroupMemberRepo,
    requestContext: RequestContext,
) : SharesService(folderUserShareRepo, folderGroupShareRepo, albumUserShareRepo, albumGroupShareRepo, folderRepo, albumRepo, groupMemberRepo, requestContext) {

    fun folderShares(path: String?): LibrarySharesSummary = shares(resolveFolder(path))

    @Transactional
    fun shareFolderWithUser(path: String?, request: ShareUserRequest): LibrarySharesSummary {
        val folder = resolveFolder(path)
        requireVisible(folder)
        val target = userRepo.findById(request.userId!!) ?: throw NotFoundException("User not found")
        if (folderUserShareRepo.find("folder = ?1 and user = ?2", folder, target).firstResult() == null) {
            folderUserShareRepo.persist(TenantFolderUserShare().also {
                it.id = TenantFolderUserShareId(folder.id, target.id)
                it.folder = folder
                it.user = target
            })
        }
        return shares(folder)
    }

    @Transactional
    fun shareFolderWithGroup(path: String?, request: ShareGroupRequest): LibrarySharesSummary {
        val folder = resolveFolder(path)
        requireVisible(folder)
        val group = groupRepo.findById(request.groupId!!) ?: throw NotFoundException("Group not found")
        requireGroupInTenant(group)
        if (folderGroupShareRepo.find("folder = ?1 and group = ?2", folder, group).firstResult() == null) {
            folderGroupShareRepo.persist(TenantFolderGroupShare().also {
                it.id = TenantFolderGroupShareId(folder.id, group.id)
                it.folder = folder
                it.group = group
            })
        }
        return shares(folder)
    }

    @Transactional
    fun unshareFolderWithUser(path: String?, userId: Long): LibrarySharesSummary {
        val folder = resolveFolder(path)
        requireVisible(folder)
        folderUserShareRepo.find("folder = ?1 and user.id = ?2", folder, userId).firstResult()?.delete()
        return shares(folder)
    }

    @Transactional
    fun unshareFolderWithGroup(path: String?, groupId: Long): LibrarySharesSummary {
        val folder = resolveFolder(path)
        requireVisible(folder)
        folderGroupShareRepo.find("folder = ?1 and group.id = ?2", folder, groupId).firstResult()?.delete()
        return shares(folder)
    }

    private fun resolveFolder(path: String?): TenantFolder =
        (resolveVisibleContext(path) as? ExplorerContext.Folder)?.folder ?: throw NotFoundException("Folder not found")

}
