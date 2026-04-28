package com.lunva.api.library.media

import com.lunva.api.domain.*
import com.lunva.api.library.LibraryService
import com.lunva.api.library.explorer.ExplorerContext
import com.lunva.api.security.RequestContext
import jakarta.inject.Inject
import jakarta.inject.Singleton

@Singleton
class MediaService @Inject constructor(
    private val albumMediaRepo: AlbumMediaRepo,
    private val albumRepo: TenantAlbumRepo,
    private val mediaRepo: MediaRepo,
    folderRepo: TenantFolderRepo,
    folderUserShareRepo: TenantFolderUserShareRepo,
    folderGroupShareRepo: TenantFolderGroupShareRepo,
    albumUserShareRepo: TenantAlbumUserShareRepo,
    albumGroupShareRepo: TenantAlbumGroupShareRepo,
    groupMemberRepo: TenantGroupMemberRepo,
    requestContext: RequestContext
) : LibraryService(folderRepo, albumRepo, folderUserShareRepo, folderGroupShareRepo, albumUserShareRepo, albumGroupShareRepo, groupMemberRepo, requestContext) {

    fun streamMedia(path: String?): Sequence<StreamMediaSummary> {
        val context = resolveVisibleContext(path)
        val albums = when (context) {
            ExplorerContext.Root -> visibleAlbums()
            is ExplorerContext.Folder -> visibleAlbums().filter { it.parentFolder != null && isDescendantOrSame(it.parentFolder!!, context.folder) }
            is ExplorerContext.Album -> listOf(context.album)
        }

        return albums.asSequence()
            .flatMap { album ->
                albumMediaRepo.listVisibleByAlbum(album).asSequence()
                    .filter { isMediaVisible(it.media, album) }
                    .map { it.media.toStreamSummary(album) }
            }
            .distinctBy { it.id }
    }

    private fun visibleAlbums(): List<TenantAlbum> =
        albumRepo.listByTenant(tenant()).filter { isAlbumVisible(it) }

    private fun isDescendantOrSame(candidate: TenantFolder, ancestor: TenantFolder): Boolean =
        generateSequence(candidate) { it.parentFolder }.any { it.id == ancestor.id }

    private fun isMediaVisible(media: Media, album: TenantAlbum): Boolean =
        media.owner.id == user().id ||
                isAlbumVisible(album) ||
                mediaRepo.listAccessible(tenant(), user()).any { it.id == media.id }

    private fun Media.toStreamSummary(album: TenantAlbum?): StreamMediaSummary =
        StreamMediaSummary(publicId, title, album?.path(), owner.id, createdAt)
}
