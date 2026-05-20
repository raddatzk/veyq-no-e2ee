package com.veyq.api.library.folder

import com.veyq.api.generated.api.FoldersApi
import com.veyq.api.generated.model.CreateLibraryItemRequest
import com.veyq.api.generated.model.MoveLibraryItemRequest
import com.veyq.api.generated.model.UpdateLibraryItemRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.core.Response

@ApplicationScoped
class FolderResource @Inject constructor(
    private val folderService: FolderService,
) : FoldersApi {
    override fun createFolder(tenantName: String, request: CreateLibraryItemRequest): Response =
        Response.ok(folderService.createFolder(request)).build()

    override fun updateFolder(tenantName: String, request: UpdateLibraryItemRequest): Response =
        Response.ok(folderService.updateFolder(request)).build()

    override fun moveFolder(tenantName: String, request: MoveLibraryItemRequest): Response =
        Response.ok(folderService.moveFolder(request)).build()

    override fun deleteFolder(tenantName: String, path: String?): Response {
        folderService.deleteFolder(path)
        return Response.noContent().build()
    }
}
