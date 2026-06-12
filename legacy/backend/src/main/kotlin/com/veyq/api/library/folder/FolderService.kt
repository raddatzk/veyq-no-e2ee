package com.veyq.api.library.folder

import com.veyq.api.auth.BadRequestException
import com.veyq.api.auth.ConflictException
import com.veyq.api.auth.NotFoundException
import com.veyq.api.auth.filterchain.RequestContext
import com.veyq.api.domain.Memory
import com.veyq.api.domain.MemoryRepo
import com.veyq.api.domain.TenantFolder
import com.veyq.api.domain.TenantFolderRepo
import com.veyq.api.domain.TenantGroupMemberRepo
import com.veyq.api.domain.TenantGroupRepo
import com.veyq.api.generated.model.CreateFolderRequest
import com.veyq.api.generated.model.MemoryFolder
import com.veyq.api.generated.model.MoveFolderRequest
import com.veyq.api.generated.model.UpdateFolderRequest
import com.veyq.api.library.LibraryDeletionService
import com.veyq.api.library.LibraryScopeKey
import com.veyq.api.library.LibraryService
import com.veyq.api.library.ResolvedMemoryLocation
import jakarta.inject.Inject
import jakarta.inject.Singleton
import jakarta.transaction.Transactional

@Singleton
class FolderService @Inject constructor(
    private val folderRepo: TenantFolderRepo,
    private val memoryRepo: MemoryRepo,
    private val libraryDeletionService: LibraryDeletionService,
    groupRepo: TenantGroupRepo,
    groupMemberRepo: TenantGroupMemberRepo,
    requestContext: RequestContext,
) : LibraryService(folderRepo, groupRepo, groupMemberRepo, requestContext) {

    @Transactional
    fun createFolder(request: CreateFolderRequest): MemoryFolder {
        val parent = resolveParent(request.parentPath)
        val scope = resolveCreateScope(parent, request.scope)
        val name = normalizedName(request.name)
        validateNoFolderCollision(parent, scope, name)
        validateNoMemoryCollision(parent, scope, name)
        val folder = TenantFolder().also {
            it.tenant = tenant()
            it.parentFolder = parent
            it.owner = user()
            applyScope(it, scope)
            it.name = name
            it.displayName = request.displayName.trim()
            folderRepo.persist(it)
        }
        folderRepo.flush()
        return folder.toSummary()
    }

    @Transactional
    fun updateFolder(request: UpdateFolderRequest): MemoryFolder {
        val folder = resolveFolder(request.path)
        requireVisible(folder)
        request.name?.let {
            val name = normalizedName(it)
            if (name != folder.name) {
                validateNoFolderCollision(folder.parentFolder, folder.scope(), name)
                validateNoMemoryCollision(folder.parentFolder, folder.scope(), name)
            }
            folder.name = name
        }
        request.displayName?.let { folder.displayName = it.trim() }
        return folder.toSummary()
    }

    @Transactional
    fun moveFolder(request: MoveFolderRequest): MemoryFolder {
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
            validateNoFolderCollision(newParent, folder.scope(), newName)
            validateNoMemoryCollision(newParent, folder.scope(), newName)
        }
        folder.parentFolder = newParent
        folder.name = newName
        return folder.toSummary()
    }

    @Transactional
    fun deleteFolder(path: String?) {
        val folder = resolveFolder(path)
        requireVisible(folder)
        libraryDeletionService.deleteFolder(folder)
    }

    private fun resolveFolder(path: String?): TenantFolder =
        (resolveVisibleContext(path) as? ResolvedMemoryLocation.Folder)?.folder ?: throw NotFoundException("Folder not found")

    private fun isDescendantOrSame(candidate: TenantFolder, ancestor: TenantFolder): Boolean =
        generateSequence(candidate) { it.parentFolder }.any { it.id == ancestor.id }

    private fun validateNoMemoryCollision(parent: TenantFolder?, scope: LibraryScopeKey, name: String) {
        val collision = memoryRepo.listByFolder(tenant(), parent).any { it.name == name && it.scope().sameAs(scope) }
        if (collision) throw ConflictException("Path name already exists")
    }
}
