package com.lunva.api.library.album

import com.lunva.api.generated.api.AlbumsApi
import com.lunva.api.generated.model.AlbumMediaRequest
import com.lunva.api.generated.model.CreateLibraryItemRequest
import com.lunva.api.generated.model.MoveLibraryItemRequest
import com.lunva.api.generated.model.SetAlbumCoverRequest
import com.lunva.api.generated.model.UpdateLibraryItemRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.core.Response

@ApplicationScoped
class AlbumResource @Inject constructor(
    private val albumService: AlbumService,
) : AlbumsApi {

    override fun createAlbum(tenantName: String, request: CreateLibraryItemRequest): Response =
        Response.ok(albumService.createAlbum(request)).build()

    override fun updateAlbum(tenantName: String, request: UpdateLibraryItemRequest): Response =
        Response.ok(albumService.updateAlbum(request)).build()

    override fun moveAlbum(tenantName: String, request: MoveLibraryItemRequest): Response =
        Response.ok(albumService.moveAlbum(request)).build()

    override fun deleteAlbum(tenantName: String, path: String?): Response {
        albumService.deleteAlbum(path)
        return Response.noContent().build()
    }

    override fun addMedia(tenantName: String, request: AlbumMediaRequest): Response =
        Response.ok(albumService.addMediaToAlbum(request)).build()

    override fun setCover(tenantName: String, request: SetAlbumCoverRequest): Response =
        Response.ok(albumService.setAlbumCover(request)).build()

}
