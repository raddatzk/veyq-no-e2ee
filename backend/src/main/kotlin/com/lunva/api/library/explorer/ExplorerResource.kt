package com.lunva.api.library.explorer

import com.lunva.api.domain.TenantAlbum
import com.lunva.api.domain.TenantFolder
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType

data class ExplorerSummary(
    val context: ExplorerContextSummary,
    val breadcrumbs: List<ExplorerBreadcrumb>,
    val folders: List<FolderSummary>,
    val albums: List<AlbumSummary>,
)

enum class ExplorerBreadcrumbType {
    Folder, Tenant, Album
}

data class ExplorerContextSummary(
    val type: ExplorerBreadcrumbType,
    val path: String,
    val displayName: String,
)

data class ExplorerBreadcrumb(
    val type: ExplorerBreadcrumbType,
    val name: String,
    val displayName: String,
    val path: String
)

data class FolderSummary(
    val id: String,
    val name: String,
    val displayName: String,
    val path: String
)

data class AlbumSummary(
    val id: String,
    val name: String,
    val displayName: String,
    val path: String,
    val coverMediaId: String?
)

@Path("/api/{tenantName}/explorer")
@ApplicationScoped
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
class ExplorerResource @Inject constructor(
    private val explorerService: ExplorerService,
) {
    @GET
    fun explorer(@QueryParam("path") path: String?): ExplorerSummary =
        explorerService.explorer(path)
}

sealed class ExplorerContext {
    data object Root : ExplorerContext()
    data class Folder(val folder: TenantFolder) : ExplorerContext()
    data class Album(val album: TenantAlbum) : ExplorerContext()
}