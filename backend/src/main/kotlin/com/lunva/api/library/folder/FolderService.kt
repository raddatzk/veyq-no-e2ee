package com.lunva.api.library.folder

import com.lunva.api.auth.BadRequestException
import com.lunva.api.auth.NotFoundException
import com.lunva.api.domain.*
import com.lunva.api.library.CreateLibraryItemRequest
import com.lunva.api.library.LibraryService
import com.lunva.api.library.MoveLibraryItemRequest
import com.lunva.api.library.UpdateLibraryItemRequest
import com.lunva.api.library.explorer.ExplorerContext
import com.lunva.api.library.explorer.FolderSummary
import com.lunva.api.auth.filterchain.RequestContext
import jakarta.inject.Inject
import jakarta.inject.Singleton
import jakarta.transaction.Transactional

@Singleton
class FolderService @Inject constructor(
    private val folderRepo: TenantFolderRepo,
    albumRepo: TenantAlbumRepo,
    folderUserShareRepo: TenantFolderUserShareRepo,
    folderGroupShareRepo: TenantFolderGroupShareRepo,
    albumUserShareRepo: TenantAlbumUserShareRepo,
    albumGroupShareRepo: TenantAlbumGroupShareRepo,
    groupMemberRepo: TenantGroupMemberRepo,
    requestContext: RequestContext,
) : LibraryService(folderRepo, albumRepo, folderUserShareRepo, folderGroupShareRepo, albumUserShareRepo, albumGroupShareRepo, groupMemberRepo, requestContext) {

    @Transactional
    fun createFolder(request: CreateLibraryItemRequest): FolderSummary {
        val parent = resolveParent(request.parentPath)
        val name = normalizedName(request.name)
        validateNoCollision(parent, name)
        val folder = TenantFolder().also {
            it.tenant = tenant()
            it.parentFolder = parent
            it.owner = user()
            it.name = name
            it.displayName = request.displayName!!.trim()
            folderRepo.persist(it)
        }
        folderRepo.flush()
        return folder.toSummary()
    }

    @Transactional
    fun updateFolder(request: UpdateLibraryItemRequest): FolderSummary {
        val folder = resolveFolder(request.path)
        requireVisible(folder)
        request.name?.let {
            val name = normalizedName(it)
            if (name != folder.name) validateNoCollision(folder.parentFolder, name)
            folder.name = name
        }
        request.displayName?.let { folder.displayName = it.trim() }
        return folder.toSummary()
    }

    @Transactional
    fun moveFolder(request: MoveLibraryItemRequest): FolderSummary {
        val folder = resolveFolder(request.path)
        requireVisible(folder)
        val newParent = resolveParent(request.newParentPath)
        if (newParent != null && isDescendantOrSame(newParent, folder)) {
            throw BadRequestException("Cannot move folder into itself")
        }
        val newName = request.name?.let { normalizedName(it) } ?: folder.name
        if (newParent?.id != folder.parentFolder?.id || newName != folder.name) {
            validateNoCollision(newParent, newName)
        }
        folder.parentFolder = newParent
        folder.name = newName
        return folder.toSummary()
    }

    @Transactional
    fun deleteFolder(path: String?) {
        val folder = resolveFolder(path)
        requireVisible(folder)
        folder.delete()
    }

    private fun resolveFolder(path: String?): TenantFolder =
        (resolveVisibleContext(path) as? ExplorerContext.Folder)?.folder ?: throw NotFoundException("Folder not found")

    private fun isDescendantOrSame(candidate: TenantFolder, ancestor: TenantFolder): Boolean =
        generateSequence(candidate) { it.parentFolder }.any { it.id == ancestor.id }
}
