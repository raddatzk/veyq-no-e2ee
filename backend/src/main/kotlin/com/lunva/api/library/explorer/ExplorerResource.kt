package com.lunva.api.library.explorer

import com.lunva.api.generated.explorer.api.ExplorerApi
import com.lunva.api.generated.explorer.model.ExplorerResponse
import com.lunva.api.domain.TenantAlbum
import com.lunva.api.domain.TenantFolder
import com.lunva.api.library.LibraryScopeKey
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

@ApplicationScoped
class ExplorerResource @Inject constructor(
    private val explorerService: ExplorerService,
) : ExplorerApi {
    override fun getExplorer(tenantName: String, path: String): ExplorerResponse =
        explorerService.explorer(path)
}

sealed class ResolvedExplorerLocation {
    data object Tenant : ResolvedExplorerLocation()
    data class Group(val scope: LibraryScopeKey.Group) : ResolvedExplorerLocation()
    data class User(val scope: LibraryScopeKey.User) : ResolvedExplorerLocation()
    data class Folder(val scope: TenantFolder) : ResolvedExplorerLocation()
    data class Album(val scope: TenantAlbum) : ResolvedExplorerLocation()
}
