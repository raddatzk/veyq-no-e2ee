package com.lunva.api.library.settings

import com.lunva.api.auth.BadRequestException
import com.lunva.api.auth.filterchain.RequestContext
import com.lunva.api.domain.TenantAlbumRepo
import com.lunva.api.domain.TenantFolderRepo
import com.lunva.api.domain.TenantGroupMemberRepo
import com.lunva.api.domain.TenantGroupRepo
import com.lunva.api.generated.explorer.model.ExplorerBreadcrumb
import com.lunva.api.generated.settings.model.SettingsBreadcrumb
import com.lunva.api.generated.settings.model.SettingsLocation
import com.lunva.api.generated.settings.model.SettingsLocationKind
import com.lunva.api.generated.settings.model.SettingsResponse
import com.lunva.api.library.LibraryScopes
import com.lunva.api.library.LibraryService
import com.lunva.api.library.explorer.ResolvedExplorerLocation
import jakarta.inject.Inject
import jakarta.inject.Singleton

@Singleton
class SettingsService @Inject constructor(
    folderRepo: TenantFolderRepo,
    albumRepo: TenantAlbumRepo,
    groupRepo: TenantGroupRepo,
    groupMemberRepo: TenantGroupMemberRepo,
    requestContext: RequestContext,
) : LibraryService(folderRepo, albumRepo, groupRepo, groupMemberRepo, requestContext) {

    fun settings(path: String?): SettingsResponse {
        val settingsPath = normalizeSettingsPath(path)
        val subjectPath = settingsPath.removeSuffix("/settings").ifBlank { "/" }
        val subject = resolveVisibleContext(subjectPath)
        val subjectLocation = subject.toSettingsLocation()
        val location = SettingsLocation(
            SettingsLocationKind.SETTINGS,
            settingsPath,
            SETTINGS_DISPLAY_NAME,
            SETTINGS_DISPLAY_NAME,
        )
        return SettingsResponse(
            location,
            subjectLocation,
            subject.toBreadcrumbs().map { it.toSettingsBreadcrumb() } +
                SettingsBreadcrumb(SettingsLocationKind.SETTINGS, settingsPath, SETTINGS_DISPLAY_NAME, SETTINGS_DISPLAY_NAME),
        )
    }

    private fun normalizeSettingsPath(path: String?): String {
        val normalized = path?.trim()?.takeIf { it.isNotBlank() } ?: throw BadRequestException("path is required")
        val segments = normalized.trim('/').split('/').filter { it.isNotBlank() }
        if (segments.lastOrNull() != SETTINGS_PATH_SEGMENT) throw BadRequestException("Unsupported settings path")
        return "/" + segments.joinToString("/")
    }

    private fun ResolvedExplorerLocation.toSettingsLocation(): SettingsLocation =
        when (this) {
            ResolvedExplorerLocation.Tenant -> SettingsLocation(SettingsLocationKind.TENANT, "/", tenant().displayName, tenant().displayName)
            is ResolvedExplorerLocation.Group -> SettingsLocation(
                SettingsLocationKind.GROUP,
                LibraryScopes.pathPrefix(scope),
                scope.group.displayName,
                "@${scope.group.displayName}",
            )
            is ResolvedExplorerLocation.User -> SettingsLocation(
                SettingsLocationKind.USER,
                LibraryScopes.pathPrefix(scope),
                scope.user.displayName,
                "~${scope.user.displayName}",
            )
            is ResolvedExplorerLocation.Folder -> SettingsLocation(
                SettingsLocationKind.FOLDER,
                scope.path(),
                scope.displayName,
                scope.displayName,
            )
            is ResolvedExplorerLocation.Album -> SettingsLocation(
                SettingsLocationKind.ALBUM,
                scope.path(),
                scope.displayName,
                scope.displayName,
            )
        }

    private fun ResolvedExplorerLocation.toBreadcrumbs(): List<ExplorerBreadcrumb> =
        when (this) {
            ResolvedExplorerLocation.Tenant -> listOf(
                ExplorerBreadcrumb(com.lunva.api.generated.explorer.model.LocationKind.TENANT, "/", tenant().displayName, tenant().displayName)
            )
            is ResolvedExplorerLocation.Group -> listOf(
                ExplorerBreadcrumb(com.lunva.api.generated.explorer.model.LocationKind.TENANT, "/", tenant().displayName, tenant().displayName),
                ExplorerBreadcrumb(com.lunva.api.generated.explorer.model.LocationKind.GROUP, LibraryScopes.pathPrefix(scope), scope.group.displayName, "@${scope.group.displayName}"),
            )
            is ResolvedExplorerLocation.User -> listOf(
                ExplorerBreadcrumb(com.lunva.api.generated.explorer.model.LocationKind.TENANT, "/", tenant().displayName, tenant().displayName),
                ExplorerBreadcrumb(com.lunva.api.generated.explorer.model.LocationKind.USER, LibraryScopes.pathPrefix(scope), scope.user.displayName, "~${scope.user.displayName}"),
            )
            is ResolvedExplorerLocation.Folder -> breadcrumbs(scope)
            is ResolvedExplorerLocation.Album -> breadcrumbs(scope)
        }

    private fun ExplorerBreadcrumb.toSettingsBreadcrumb(): SettingsBreadcrumb =
        SettingsBreadcrumb(SettingsLocationKind.valueOf(kind.toString()), path, displayName, label)

    private companion object {
        const val SETTINGS_PATH_SEGMENT = "settings"
        const val SETTINGS_DISPLAY_NAME = "Einstellungen"
    }
}
