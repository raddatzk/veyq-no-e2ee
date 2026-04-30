package com.lunva.api.library.album

import com.lunva.api.generated.explorer.model.ExplorerAlbum
import com.lunva.api.library.AlbumMediaRequest
import com.lunva.api.library.CreateLibraryItemRequest
import com.lunva.api.library.MoveLibraryItemRequest
import com.lunva.api.library.SetAlbumCoverRequest
import com.lunva.api.library.UpdateLibraryItemRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.validation.Valid
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType

@Path("/api/{tenantName}/albums")
@ApplicationScoped
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
class AlbumResource @Inject constructor(
    private val albumService: AlbumService,
) {

    @POST
    fun createAlbum(@Valid request: CreateLibraryItemRequest): ExplorerAlbum =
        albumService.createAlbum(request)

    @POST
    @Path("/update")
    fun updateAlbum(@Valid request: UpdateLibraryItemRequest): ExplorerAlbum =
        albumService.updateAlbum(request)

    @POST
    @Path("/move")
    fun moveAlbum(@Valid request: MoveLibraryItemRequest): ExplorerAlbum =
        albumService.moveAlbum(request)

    @DELETE
    fun deleteAlbum(@QueryParam("path") path: String?) =
        albumService.deleteAlbum(path)

    @POST
    @Path("/media")
    fun addMedia(@Valid request: AlbumMediaRequest): ExplorerAlbum =
        albumService.addMediaToAlbum(request)

    @POST
    @Path("/cover")
    fun setCover(@Valid request: SetAlbumCoverRequest): ExplorerAlbum =
        albumService.setAlbumCover(request)

}
