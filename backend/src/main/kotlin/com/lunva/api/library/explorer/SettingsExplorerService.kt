package com.lunva.api.library.explorer

import com.lunva.api.auth.BadRequestException
import com.lunva.api.auth.ForbiddenException
import com.lunva.api.auth.filterchain.RequestContext
import com.lunva.api.domain.*
import com.lunva.api.generated.model.ExplorerBreadcrumb
import com.lunva.api.generated.model.LocationKind
import com.lunva.api.generated.model.*
import com.lunva.api.library.LibraryScopes
import com.lunva.api.library.LibraryService
import com.lunva.api.tenant.TenantService
import jakarta.inject.Inject
import jakarta.inject.Singleton

@Singleton
class SettingsExplorerService @Inject constructor(
    private val tenantService: TenantService,
    folderRepo: TenantFolderRepo,
    albumRepo: TenantAlbumRepo,
    tenantGroupRepo: TenantGroupRepo,
    tenantGroupMemberRepo: TenantGroupMemberRepo,
    requestContext: RequestContext,
) : LibraryService(folderRepo, albumRepo, tenantGroupRepo, tenantGroupMemberRepo, requestContext) {
    fun settings(path: String?): SettingsResponse {
        val normalized = normalizePath(path)
        return when (normalized) {
            "/settings" -> serverSettingsContext()
            "/~me" -> userSettingsContext()
            else -> tenantSettingsContext(normalized)
        }
    }

    private fun normalizePath(path: String?): String {
        val normalized = path?.trim()?.takeIf { it.isNotBlank() } ?: throw BadRequestException("path is required")
        val segments = normalized.trim('/').split('/').filter { it.isNotBlank() }
        return "/" + segments.joinToString("/")
    }

    private fun serverSettingsContext(): SettingsResponse {
        requireServerOwner()
        return SettingsResponse(
            SettingsLocation("/settings", SETTINGS_DISPLAY_NAME, SETTINGS_DISPLAY_NAME),
            SettingsSubject(SettingsSubjectType.SERVER, SERVER_DISPLAY_NAME, SERVER_DISPLAY_NAME),
            listOf(
                SettingsBreadcrumb(SettingsSubjectType.SERVER, "/settings", SETTINGS_DISPLAY_NAME, SETTINGS_DISPLAY_NAME),
            ),
        )
    }

    private fun userSettingsContext(): SettingsResponse {
        val label = "~${user().displayName}"
        return SettingsResponse(
            SettingsLocation("/~me", SETTINGS_DISPLAY_NAME, SETTINGS_DISPLAY_NAME),
            SettingsSubject(SettingsSubjectType.USER, user().displayName, label),
            listOf(
                SettingsBreadcrumb(SettingsSubjectType.USER, "/~me", user().displayName, label),
            ),
        )
    }

    private fun tenantSettingsContext(path: String): SettingsResponse {
        val segments = pathSegments(path)
        if (segments.size < 2 || segments.last() != SETTINGS_PATH_SEGMENT) {
            throw BadRequestException("Unsupported settings path")
        }

        val tenantName = segments.first()
        val tenant = tenantService.findTenantByName(tenantName)
        tenantService.requireTenantMembership(tenant, user())
        requestContext.tenant = tenant

        val tenantSettingsPath = "/" + segments.drop(1).joinToString("/")
        val subjectPath = tenantSettingsPath.removeSuffix("/settings").ifBlank { "/" }
        val subject = resolveVisibleContext(subjectPath)
        val subjectType = subject.toSubjectType()
        val fullSettingsPath = "/$tenantName${tenantSettingsPath}"

        return SettingsResponse(
            SettingsLocation(fullSettingsPath, SETTINGS_DISPLAY_NAME, SETTINGS_DISPLAY_NAME),
            subject.toSettingsSubject(),
            subject.toBreadcrumbs().map { it.toSettingsBreadcrumb(tenantName) } +
                    SettingsBreadcrumb(subjectType, fullSettingsPath, SETTINGS_DISPLAY_NAME, SETTINGS_DISPLAY_NAME),
        )
    }

    private fun ResolvedExplorerLocation.toBreadcrumbs(): List<ExplorerBreadcrumb> =
        when (this) {
            ResolvedExplorerLocation.Tenant -> listOf(
                ExplorerBreadcrumb(LocationKind.TENANT, "/", tenant().displayName, tenant().displayName)
            )

            is ResolvedExplorerLocation.Group -> listOf(
                ExplorerBreadcrumb(LocationKind.TENANT, "/", tenant().displayName, tenant().displayName),
                ExplorerBreadcrumb(LocationKind.GROUP, LibraryScopes.pathPrefix(scope), scope.group.displayName, "@${scope.group.displayName}"),
            )

            is ResolvedExplorerLocation.User -> listOf(
                ExplorerBreadcrumb(LocationKind.TENANT, "/", tenant().displayName, tenant().displayName),
                ExplorerBreadcrumb(LocationKind.USER, LibraryScopes.pathPrefix(scope), scope.user.displayName, "~${scope.user.displayName}"),
            )

            is ResolvedExplorerLocation.Folder -> breadcrumbs(scope)
            is ResolvedExplorerLocation.Album -> breadcrumbs(scope)
        }

    private fun ExplorerBreadcrumb.toSettingsBreadcrumb(tenantName: String): SettingsBreadcrumb =
        SettingsBreadcrumb(kind.toSubjectType(), "/$tenantName${path.trimEnd('/')}", displayName, label)

    private fun LocationKind.toSubjectType(): SettingsSubjectType =
        when (this) {
            LocationKind.TENANT -> SettingsSubjectType.TENANT
            LocationKind.GROUP -> SettingsSubjectType.GROUP
            LocationKind.USER -> SettingsSubjectType.TENANT_USER
            LocationKind.FOLDER -> SettingsSubjectType.FOLDER
            LocationKind.ALBUM -> SettingsSubjectType.ALBUM
        }

    private fun ResolvedExplorerLocation.toSettingsSubject(): SettingsSubject =
        when (this) {
            ResolvedExplorerLocation.Tenant -> SettingsSubject(SettingsSubjectType.TENANT, tenant().displayName, tenant().displayName)
            is ResolvedExplorerLocation.Group -> SettingsSubject(
                SettingsSubjectType.GROUP,
                scope.group.displayName,
                "@${scope.group.displayName}",
            )

            is ResolvedExplorerLocation.User -> SettingsSubject(
                SettingsSubjectType.TENANT_USER,
                scope.user.displayName,
                "~${scope.user.displayName}",
            )

            is ResolvedExplorerLocation.Folder -> SettingsSubject(
                SettingsSubjectType.FOLDER,
                scope.displayName,
                scope.displayName,
            )

            is ResolvedExplorerLocation.Album -> SettingsSubject(
                SettingsSubjectType.ALBUM,
                scope.displayName,
                scope.displayName,
            )
        }

    private fun requireServerOwner() {
        if (user().role != com.lunva.api.domain.ServerRole.ServerOwner) {
            throw ForbiddenException("User ${user().displayName} has insufficient role on server")
        }
    }

    private fun ResolvedExplorerLocation.toSubjectType(): SettingsSubjectType =
        when (this) {
            ResolvedExplorerLocation.Tenant -> SettingsSubjectType.TENANT
            is ResolvedExplorerLocation.Group -> SettingsSubjectType.GROUP
            is ResolvedExplorerLocation.User -> SettingsSubjectType.TENANT_USER
            is ResolvedExplorerLocation.Folder -> SettingsSubjectType.FOLDER
            is ResolvedExplorerLocation.Album -> SettingsSubjectType.ALBUM
        }

    private fun pathSegments(path: String): List<String> =
        path.trim('/').split('/').filter { it.isNotBlank() }

    private companion object {
        const val SETTINGS_PATH_SEGMENT = "settings"
        const val SETTINGS_DISPLAY_NAME = "Einstellungen"
        const val SERVER_DISPLAY_NAME = "Server"
    }
}
