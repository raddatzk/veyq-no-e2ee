package com.veyq.api.library.folder

import com.veyq.api.generated.api.FoldersApi
import com.veyq.api.generated.model.CreateFolderRequest
import com.veyq.api.generated.model.MoveFolderRequest
import com.veyq.api.generated.model.UpdateFolderRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.core.Response

@ApplicationScoped
class FolderResource @Inject constructor(
    private val folderService: FolderService,
) : FoldersApi {
    override fun createFolder(tenantName: String, request: CreateFolderRequest): Response =
        Response.ok(folderService.createFolder(request)).build()

    override fun updateFolder(tenantName: String, request: UpdateFolderRequest): Response =
        Response.ok(folderService.updateFolder(request)).build()

    override fun moveFolder(tenantName: String, request: MoveFolderRequest): Response =
        Response.ok(folderService.moveFolder(request)).build()

    override fun deleteFolder(tenantName: String, path: String?): Response {
        folderService.deleteFolder(path)
        return Response.noContent().build()
    }
}
