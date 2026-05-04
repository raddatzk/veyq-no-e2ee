package com.lunva.api.library.folder

import com.lunva.api.auth.BadRequestException
import com.lunva.api.auth.NotFoundException
import com.lunva.api.domain.*
import com.lunva.api.generated.model.ExplorerFolder
import com.lunva.api.generated.model.CreateLibraryItemRequest
import com.lunva.api.library.LibraryService
import com.lunva.api.generated.model.MoveLibraryItemRequest
import com.lunva.api.generated.model.UpdateLibraryItemRequest
import com.lunva.api.library.explorer.ResolvedExplorerLocation
import com.lunva.api.auth.filterchain.RequestContext
import jakarta.inject.Inject
import jakarta.inject.Singleton
import jakarta.transaction.Transactional

@Singleton
class FolderService @Inject constructor(
    private val folderRepo: TenantFolderRepo,
    albumRepo: TenantAlbumRepo,
    groupRepo: TenantGroupRepo,
    groupMemberRepo: TenantGroupMemberRepo,
    requestContext: RequestContext,
) : LibraryService(folderRepo, albumRepo, groupRepo, groupMemberRepo, requestContext) {

    @Transactional
    fun createFolder(request: CreateLibraryItemRequest): ExplorerFolder {
        val parent = resolveParent(request.parentPath)
        val scope = resolveCreateScope(parent, request)
        val name = normalizedName(request.name)
        validateNoCollision(parent, scope, name)
        val folder = TenantFolder().also {
            it.tenant = tenant()
            it.parentFolder = parent
            it.owner = user()
            applyScope(it, scope)
            it.name = name
            it.displayName = request.displayName!!.trim()
            folderRepo.persist(it)
        }
        folderRepo.flush()
        return folder.toSummary()
    }

    @Transactional
    fun updateFolder(request: UpdateLibraryItemRequest): ExplorerFolder {
        val folder = resolveFolder(request.path)
        requireVisible(folder)
        request.name?.let {
            val name = normalizedName(it)
            if (name != folder.name) validateNoCollision(folder.parentFolder, folder.scope(), name)
            folder.name = name
        }
        request.displayName?.let { folder.displayName = it.trim() }
        return folder.toSummary()
    }

    @Transactional
    fun moveFolder(request: MoveLibraryItemRequest): ExplorerFolder {
        val folder = resolveFolder(request.path)
        requireVisible(folder)
        val newParent = resolveParent(request.newParentPath)
        requireMoveTargetScope(folder.scope(), request.newParentPath)
        if (newParent != null && isDescendantOrSame(newParent, folder)) {
            throw BadRequestException("Cannot move folder into itself")
        }
        requireSameScope(folder.scope(), newParent)
        val newName = request.name?.let { normalizedName(it) } ?: folder.name
        if (newParent?.id != folder.parentFolder?.id || newName != folder.name) {
            validateNoCollision(newParent, folder.scope(), newName)
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
        (resolveVisibleContext(path) as? ResolvedExplorerLocation.Folder)?.scope ?: throw NotFoundException("Folder not found")

    private fun isDescendantOrSame(candidate: TenantFolder, ancestor: TenantFolder): Boolean =
        generateSequence(candidate) { it.parentFolder }.any { it.id == ancestor.id }
}
