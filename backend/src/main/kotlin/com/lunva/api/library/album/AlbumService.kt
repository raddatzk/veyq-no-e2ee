package com.lunva.api.library.album

import com.lunva.api.auth.NotFoundException
import com.lunva.api.domain.AlbumMedia
import com.lunva.api.domain.AlbumMediaId
import com.lunva.api.domain.AlbumMediaRepo
import com.lunva.api.domain.Media
import com.lunva.api.domain.MediaRepo
import com.lunva.api.domain.TenantAlbum
import com.lunva.api.domain.TenantAlbumGroupShareRepo
import com.lunva.api.domain.TenantAlbumRepo
import com.lunva.api.domain.TenantAlbumUserShareRepo
import com.lunva.api.domain.TenantFolderGroupShareRepo
import com.lunva.api.domain.TenantFolderRepo
import com.lunva.api.domain.TenantFolderUserShareRepo
import com.lunva.api.domain.TenantGroupMemberRepo
import com.lunva.api.library.AlbumMediaRequest
import com.lunva.api.library.CreateLibraryItemRequest
import com.lunva.api.library.explorer.AlbumSummary
import com.lunva.api.library.explorer.ExplorerContext
import com.lunva.api.library.LibraryService
import com.lunva.api.library.MoveLibraryItemRequest
import com.lunva.api.library.SetAlbumCoverRequest
import com.lunva.api.library.UpdateLibraryItemRequest
import com.lunva.api.security.RequestContext
import jakarta.inject.Inject
import jakarta.inject.Singleton
import jakarta.transaction.Transactional

@Singleton
class AlbumService @Inject constructor(
    private val albumRepo: TenantAlbumRepo,
    private val mediaRepo: MediaRepo,
    private val albumMediaRepo: AlbumMediaRepo,
    folderRepo: TenantFolderRepo,
    folderUserShareRepo: TenantFolderUserShareRepo,
    folderGroupShareRepo: TenantFolderGroupShareRepo,
    albumUserShareRepo: TenantAlbumUserShareRepo,
    albumGroupShareRepo: TenantAlbumGroupShareRepo,
    groupMemberRepo: TenantGroupMemberRepo,
    requestContext: RequestContext,
): LibraryService(folderRepo, albumRepo, folderUserShareRepo, folderGroupShareRepo, albumUserShareRepo, albumGroupShareRepo, groupMemberRepo, requestContext) {
    @Transactional
    fun createAlbum(request: CreateLibraryItemRequest): AlbumSummary {
        val parent = resolveParent(request.parentPath)
        val name = normalizedName(request.name)
        validateNoCollision(parent, name)
        val album = TenantAlbum().also {
            it.tenant = tenant()
            it.parentFolder = parent
            it.owner = user()
            it.name = name
            it.displayName = request.displayName!!.trim()
            albumRepo.persist(it)
        }
        albumRepo.flush()
        return album.toSummary()
    }

    @Transactional
    fun updateAlbum(request: UpdateLibraryItemRequest): AlbumSummary {
        val album = resolveAlbum(request.path)
        requireVisible(album)
        request.name?.let {
            val name = normalizedName(it)
            if (name != album.name) validateNoCollision(album.parentFolder, name)
            album.name = name
        }
        request.displayName?.let { album.displayName = it.trim() }
        return album.toSummary()
    }

    @Transactional
    fun moveAlbum(request: MoveLibraryItemRequest): AlbumSummary {
        val album = resolveAlbum(request.path)
        requireVisible(album)
        val newParent = resolveParent(request.newParentPath)
        val newName = request.name?.let { normalizedName(it) } ?: album.name
        if (newParent?.id != album.parentFolder?.id || newName != album.name) {
            validateNoCollision(newParent, newName)
        }
        album.parentFolder = newParent
        album.name = newName
        return album.toSummary()
    }

    @Transactional
    fun deleteAlbum(path: String?) {
        val album = resolveAlbum(path)
        requireVisible(album)
        album.delete()
    }

    @Transactional
    fun addMediaToAlbum(request: AlbumMediaRequest): AlbumSummary {
        val album = resolveAlbum(request.albumPath)
        requireVisible(album)
        val media = mediaRepo.findByTenantAndPublicId(tenant(), request.mediaId!!)
            ?: throw NotFoundException("Media not found")
        linkMedia(album, media, request.hidden ?: false, request.position ?: 0)
        return album.toSummary()
    }

    @Transactional
    fun setAlbumCover(request: SetAlbumCoverRequest): AlbumSummary {
        val album = resolveAlbum(request.albumPath)
        requireVisible(album)
        val media = mediaRepo.findByTenantAndPublicId(tenant(), request.mediaId!!)
            ?: throw NotFoundException("Media not found")
        linkMedia(album, media, request.hidden ?: true, 0)
        album.coverMedia = media
        return album.toSummary()
    }

    private fun resolveAlbum(path: String?): TenantAlbum =
        (resolveVisibleContext(path) as? ExplorerContext.Album)?.album ?: throw NotFoundException("Album not found")

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
