package com.lunva.api.library.album

import com.lunva.api.library.AlbumMediaRequest
import com.lunva.api.library.CreateLibraryItemRequest
import com.lunva.api.library.LibrarySharesSummary
import com.lunva.api.library.MoveLibraryItemRequest
import com.lunva.api.library.SetAlbumCoverRequest
import com.lunva.api.library.ShareGroupRequest
import com.lunva.api.library.ShareUserRequest
import com.lunva.api.library.UpdateLibraryItemRequest
import com.lunva.api.library.explorer.AlbumSummary
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
    private val albumSharesService: AlbumSharesService,
) {

    @POST
    fun createAlbum(@Valid request: CreateLibraryItemRequest): AlbumSummary =
        albumService.createAlbum(request)

    @POST
    @Path("/update")
    fun updateAlbum(@Valid request: UpdateLibraryItemRequest): AlbumSummary =
        albumService.updateAlbum(request)

    @POST
    @Path("/move")
    fun moveAlbum(@Valid request: MoveLibraryItemRequest): AlbumSummary =
        albumService.moveAlbum(request)

    @DELETE
    fun deleteAlbum(@QueryParam("path") path: String?) =
        albumService.deleteAlbum(path)

    @POST
    @Path("/media")
    fun addMedia(@Valid request: AlbumMediaRequest): AlbumSummary =
        albumService.addMediaToAlbum(request)

    @POST
    @Path("/cover")
    fun setCover(@Valid request: SetAlbumCoverRequest): AlbumSummary =
        albumService.setAlbumCover(request)

    @GET
    @Path("/shares")
    fun albumShares(@QueryParam("path") path: String?): LibrarySharesSummary =
        albumSharesService.albumShares(path)

    @POST
    @Path("/shares/users")
    fun shareAlbumWithUser(@QueryParam("path") path: String?, @Valid request: ShareUserRequest): LibrarySharesSummary =
        albumSharesService.shareAlbumWithUser(path, request)

    @POST
    @Path("/shares/groups")
    fun shareAlbumWithGroup(@QueryParam("path") path: String?, @Valid request: ShareGroupRequest): LibrarySharesSummary =
        albumSharesService.shareAlbumWithGroup(path, request)

    @DELETE
    @Path("/shares/users/{userId}")
    fun unshareAlbumWithUser(@QueryParam("path") path: String?, @PathParam("userId") userId: Long): LibrarySharesSummary =
        albumSharesService.unshareAlbumWithUser(path, userId)

    @DELETE
    @Path("/shares/groups/{groupId}")
    fun unshareAlbumWithGroup(@QueryParam("path") path: String?, @PathParam("groupId") groupId: Long): LibrarySharesSummary =
        albumSharesService.unshareAlbumWithGroup(path, groupId)
}
