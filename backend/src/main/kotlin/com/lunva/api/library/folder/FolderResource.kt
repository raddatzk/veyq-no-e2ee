package com.lunva.api.library.folder

import com.lunva.api.library.*
import com.lunva.api.library.explorer.FolderSummary
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.validation.Valid
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType

@Path("/api/{tenantName}/folders")
@ApplicationScoped
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
class FolderResource @Inject constructor(
    private val folderService: FolderService,
    private val folderSharesService: FolderSharesService,
) {
    @POST
    fun createFolder(@Valid request: CreateLibraryItemRequest): FolderSummary =
        folderService.createFolder(request)

    @POST
    @Path("/update")
    fun updateFolder(@Valid request: UpdateLibraryItemRequest): FolderSummary =
        folderService.updateFolder(request)

    @POST
    @Path("/move")
    fun moveFolder(@Valid request: MoveLibraryItemRequest): FolderSummary =
        folderService.moveFolder(request)

    @DELETE
    fun deleteFolder(@QueryParam("path") path: String?) =
        folderService.deleteFolder(path)

    @GET
    @Path("/shares")
    fun folderShares(@QueryParam("path") path: String?): LibrarySharesSummary =
        folderSharesService.folderShares(path)

    @POST
    @Path("/shares/users")
    fun shareFolderWithUser(@QueryParam("path") path: String?, @Valid request: ShareUserRequest): LibrarySharesSummary =
        folderSharesService.shareFolderWithUser(path, request)

    @POST
    @Path("/shares/groups")
    fun shareFolderWithGroup(@QueryParam("path") path: String?, @Valid request: ShareGroupRequest): LibrarySharesSummary =
        folderSharesService.shareFolderWithGroup(path, request)

    @DELETE
    @Path("/shares/users/{userId}")
    fun unshareFolderWithUser(@QueryParam("path") path: String?, @PathParam("userId") userId: Long): LibrarySharesSummary =
        folderSharesService.unshareFolderWithUser(path, userId)

    @DELETE
    @Path("/shares/groups/{groupId}")
    fun unshareFolderWithGroup(@QueryParam("path") path: String?, @PathParam("groupId") groupId: Long): LibrarySharesSummary =
        folderSharesService.unshareFolderWithGroup(path, groupId)
}
