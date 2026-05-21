package com.veyq.api.settings

import com.veyq.api.auth.BadRequestException
import com.veyq.api.auth.ForbiddenException
import com.veyq.api.auth.filterchain.RequestContext
import com.veyq.api.domain.TenantFolderRepo
import com.veyq.api.domain.TenantGroupMemberRepo
import com.veyq.api.domain.TenantGroupRepo
import com.veyq.api.generated.model.MemoryNavigationBreadcrumb
import com.veyq.api.generated.model.MemoryNavigationKind
import com.veyq.api.generated.model.SettingsBreadcrumb
import com.veyq.api.generated.model.SettingsLocation
import com.veyq.api.generated.model.SettingsResponse
import com.veyq.api.generated.model.SettingsSubject
import com.veyq.api.generated.model.SettingsSubjectType
import com.veyq.api.library.LibraryScopes
import com.veyq.api.library.LibraryService
import com.veyq.api.library.ResolvedMemoryLocation
import com.veyq.api.tenant.TenantService
import jakarta.inject.Inject
import jakarta.inject.Singleton

@Singleton
class SettingsNavigationService @Inject constructor(
    private val tenantService: TenantService,
    folderRepo: TenantFolderRepo,
    tenantGroupRepo: TenantGroupRepo,
    tenantGroupMemberRepo: TenantGroupMemberRepo,
    requestContext: RequestContext,
) : LibraryService(folderRepo, tenantGroupRepo, tenantGroupMemberRepo, requestContext) {
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
            listOf(SettingsBreadcrumb(SettingsSubjectType.SERVER, "/settings", SETTINGS_DISPLAY_NAME, SETTINGS_DISPLAY_NAME)),
        )
    }

    private fun userSettingsContext(): SettingsResponse {
        val label = "~${user().displayName}"
        return SettingsResponse(
            SettingsLocation("/~me", SETTINGS_DISPLAY_NAME, SETTINGS_DISPLAY_NAME),
            SettingsSubject(SettingsSubjectType.USER, user().displayName, label),
            listOf(SettingsBreadcrumb(SettingsSubjectType.USER, "/~me", user().displayName, label)),
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

    private fun ResolvedMemoryLocation.toBreadcrumbs(): List<MemoryNavigationBreadcrumb> =
        when (this) {
            ResolvedMemoryLocation.Tenant -> listOf(
                MemoryNavigationBreadcrumb(MemoryNavigationKind.TENANT, "/", tenant().displayName, tenant().displayName)
            )
            is ResolvedMemoryLocation.Group -> listOf(
                MemoryNavigationBreadcrumb(MemoryNavigationKind.TENANT, "/", tenant().displayName, tenant().displayName),
                MemoryNavigationBreadcrumb(MemoryNavigationKind.GROUP, LibraryScopes.pathPrefix(scope), scope.group.displayName, "@${scope.group.displayName}"),
            )
            is ResolvedMemoryLocation.User -> listOf(
                MemoryNavigationBreadcrumb(MemoryNavigationKind.TENANT, "/", tenant().displayName, tenant().displayName),
                MemoryNavigationBreadcrumb(MemoryNavigationKind.USER, LibraryScopes.pathPrefix(scope), scope.user.displayName, "~${scope.user.displayName}"),
            )
            is ResolvedMemoryLocation.Folder -> breadcrumbs(folder)
        }

    private fun MemoryNavigationBreadcrumb.toSettingsBreadcrumb(tenantName: String): SettingsBreadcrumb =
        SettingsBreadcrumb(kind.toSubjectType(), "/$tenantName${path.trimEnd('/')}", displayName, label)

    private fun MemoryNavigationKind.toSubjectType(): SettingsSubjectType =
        when (this) {
            MemoryNavigationKind.TENANT -> SettingsSubjectType.TENANT
            MemoryNavigationKind.GROUP -> SettingsSubjectType.GROUP
            MemoryNavigationKind.USER -> SettingsSubjectType.TENANT_USER
            MemoryNavigationKind.FOLDER -> SettingsSubjectType.FOLDER
        }

    private fun ResolvedMemoryLocation.toSettingsSubject(): SettingsSubject =
        when (this) {
            ResolvedMemoryLocation.Tenant -> SettingsSubject(SettingsSubjectType.TENANT, tenant().displayName, tenant().displayName)
            is ResolvedMemoryLocation.Group -> SettingsSubject(SettingsSubjectType.GROUP, scope.group.displayName, "@${scope.group.displayName}")
            is ResolvedMemoryLocation.User -> SettingsSubject(SettingsSubjectType.TENANT_USER, scope.user.displayName, "~${scope.user.displayName}")
            is ResolvedMemoryLocation.Folder -> SettingsSubject(SettingsSubjectType.FOLDER, folder.displayName, folder.displayName)
        }

    private fun requireServerOwner() {
        if (user().role != com.veyq.api.domain.ServerRole.ServerOwner) {
            throw ForbiddenException("User ${user().displayName} has insufficient role on server")
        }
    }

    private fun ResolvedMemoryLocation.toSubjectType(): SettingsSubjectType =
        when (this) {
            ResolvedMemoryLocation.Tenant -> SettingsSubjectType.TENANT
            is ResolvedMemoryLocation.Group -> SettingsSubjectType.GROUP
            is ResolvedMemoryLocation.User -> SettingsSubjectType.TENANT_USER
            is ResolvedMemoryLocation.Folder -> SettingsSubjectType.FOLDER
        }

    private fun pathSegments(path: String): List<String> =
        path.trim('/').split('/').filter { it.isNotBlank() }

    private companion object {
        const val SETTINGS_PATH_SEGMENT = "settings"
        const val SETTINGS_DISPLAY_NAME = "Einstellungen"
        const val SERVER_DISPLAY_NAME = "Server"
    }
}
