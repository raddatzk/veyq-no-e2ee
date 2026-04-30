package com.lunva.api.library.folder

import com.lunva.api.generated.explorer.model.ExplorerFolder
import com.lunva.api.library.*
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
) {
    @POST
    fun createFolder(@Valid request: CreateLibraryItemRequest): ExplorerFolder =
        folderService.createFolder(request)

    @POST
    @Path("/update")
    fun updateFolder(@Valid request: UpdateLibraryItemRequest): ExplorerFolder =
        folderService.updateFolder(request)

    @POST
    @Path("/move")
    fun moveFolder(@Valid request: MoveLibraryItemRequest): ExplorerFolder =
        folderService.moveFolder(request)

    @DELETE
    fun deleteFolder(@QueryParam("path") path: String?) =
        folderService.deleteFolder(path)

}
