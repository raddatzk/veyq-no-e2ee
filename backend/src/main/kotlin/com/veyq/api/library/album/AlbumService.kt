package com.veyq.api.library.album

import com.veyq.api.auth.NotFoundException
import com.veyq.api.domain.AlbumMedia
import com.veyq.api.domain.AlbumMediaId
import com.veyq.api.domain.AlbumMediaRepo
import com.veyq.api.domain.Media
import com.veyq.api.domain.MediaRepo
import com.veyq.api.domain.TenantAlbum
import com.veyq.api.domain.TenantAlbumRepo
import com.veyq.api.domain.TenantFolderRepo
import com.veyq.api.domain.TenantGroupMemberRepo
import com.veyq.api.domain.TenantGroupRepo
import com.veyq.api.generated.model.ExplorerAlbum
import com.veyq.api.generated.model.AlbumMediaRequest
import com.veyq.api.generated.model.CreateLibraryItemRequest
import com.veyq.api.library.LibraryDeletionService
import com.veyq.api.library.LibraryService
import com.veyq.api.generated.model.MoveLibraryItemRequest
import com.veyq.api.generated.model.SetAlbumCoverRequest
import com.veyq.api.generated.model.UpdateLibraryItemRequest
import com.veyq.api.library.explorer.ResolvedExplorerLocation
import com.veyq.api.auth.filterchain.RequestContext
import jakarta.inject.Inject
import jakarta.inject.Singleton
import jakarta.transaction.Transactional

@Singleton
class AlbumService @Inject constructor(
    private val albumRepo: TenantAlbumRepo,
    private val mediaRepo: MediaRepo,
    private val albumMediaRepo: AlbumMediaRepo,
    private val libraryDeletionService: LibraryDeletionService,
    folderRepo: TenantFolderRepo,
    groupRepo: TenantGroupRepo,
    groupMemberRepo: TenantGroupMemberRepo,
    requestContext: RequestContext,
): LibraryService(folderRepo, albumRepo, groupRepo, groupMemberRepo, requestContext) {
    @Transactional
    fun createAlbum(request: CreateLibraryItemRequest): ExplorerAlbum {
        val parent = resolveParent(request.parentPath)
        val scope = resolveCreateScope(parent, request)
        val name = normalizedName(request.name)
        validateNoCollision(parent, scope, name)
        val album = TenantAlbum().also {
            it.tenant = tenant()
            it.parentFolder = parent
            it.owner = user()
            applyScope(it, scope)
            it.name = name
            it.displayName = request.displayName!!.trim()
            albumRepo.persist(it)
        }
        albumRepo.flush()
        return album.toSummary()
    }

    @Transactional
    fun updateAlbum(request: UpdateLibraryItemRequest): ExplorerAlbum {
        val album = resolveAlbum(request.path)
        requireVisible(album)
        request.name?.let {
            val name = normalizedName(it)
            if (name != album.name) validateNoCollision(album.parentFolder, album.scope(), name)
            album.name = name
        }
        request.displayName?.let { album.displayName = it.trim() }
        return album.toSummary()
    }

    @Transactional
    fun moveAlbum(request: MoveLibraryItemRequest): ExplorerAlbum {
        val album = resolveAlbum(request.path)
        requireVisible(album)
        val newParent = resolveParent(request.newParentPath)
        requireMoveTargetScope(album.scope(), request.newParentPath)
        requireSameScope(album.scope(), newParent)
        val newName = request.name?.let { normalizedName(it) } ?: album.name
        if (newParent?.id != album.parentFolder?.id || newName != album.name) {
            validateNoCollision(newParent, album.scope(), newName)
        }
        album.parentFolder = newParent
        album.name = newName
        return album.toSummary()
    }

    @Transactional
    fun deleteAlbum(path: String?) {
        val album = resolveAlbum(path)
        requireVisible(album)
        libraryDeletionService.deleteAlbum(album)
    }

    @Transactional
    fun addMediaToAlbum(request: AlbumMediaRequest): ExplorerAlbum {
        val album = resolveAlbum(request.albumPath)
        requireVisible(album)
        val media = mediaRepo.findByTenantAndPublicId(tenant(), request.mediaId!!)
            ?: throw NotFoundException("Media not found")
        if (!media.scope().sameAs(album.scope())) throw NotFoundException("Media not found")
        linkMedia(album, media, request.hidden ?: false, request.position ?: 0)
        return album.toSummary()
    }

    @Transactional
    fun setAlbumCover(request: SetAlbumCoverRequest): ExplorerAlbum {
        val album = resolveAlbum(request.albumPath)
        requireVisible(album)
        val media = mediaRepo.findByTenantAndPublicId(tenant(), request.mediaId!!)
            ?: throw NotFoundException("Media not found")
        if (!media.scope().sameAs(album.scope())) throw NotFoundException("Media not found")
        linkMedia(album, media, request.hidden ?: true, 0)
        album.coverMedia = media
        return album.toSummary()
    }

    private fun resolveAlbum(path: String?): TenantAlbum =
        (resolveVisibleContext(path) as? ResolvedExplorerLocation.Album)?.scope ?: throw NotFoundException("Album not found")

    private fun linkMedia(album: TenantAlbum, media: Media, hidden: Boolean, position: Int) {
        require(media.tenant.id == album.tenant.id)
        val link = albumMediaRepo.findByAlbumAndMedia(album, media)
        if (link == null) {
            albumMediaRepo.persist(AlbumMedia().also {
                it.id = AlbumMediaId(album.id, media.id)
                it.album = album
                it.media = media
                it.hidden = hidden
                it.position = position
            })
        } else {
            link.hidden = hidden
            link.position = position
        }
    }
}
