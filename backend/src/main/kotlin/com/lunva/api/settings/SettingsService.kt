package com.lunva.api.settings

import com.lunva.api.auth.BadRequestException
import com.lunva.api.auth.ConflictException
import com.lunva.api.auth.ForbiddenException
import com.lunva.api.auth.NotFoundException
import com.lunva.api.auth.PasswordService
import com.lunva.api.auth.UnauthorizedException
import com.lunva.api.auth.filterchain.RequestContext
import com.lunva.api.domain.ServerRole
import com.lunva.api.domain.TenantAlbumRepo
import com.lunva.api.domain.TenantFolderRepo
import com.lunva.api.domain.TenantGroup
import com.lunva.api.domain.TenantGroupMemberRepo
import com.lunva.api.domain.TenantGroupRepo
import com.lunva.api.domain.UserRepo
import com.lunva.api.generated.explorer.model.ExplorerBreadcrumb
import com.lunva.api.generated.explorer.model.LocationKind
import com.lunva.api.generated.settings.model.SettingsBreadcrumb
import com.lunva.api.generated.settings.model.SettingsLocation
import com.lunva.api.generated.settings.model.SettingsResponse
import com.lunva.api.generated.settings.model.SettingsSubject
import com.lunva.api.generated.settings.model.SettingsSubjectType
import com.lunva.api.library.LibraryScopes
import com.lunva.api.library.LibraryService
import com.lunva.api.library.explorer.ResolvedExplorerLocation
import com.lunva.api.tenant.TenantService
import com.lunva.api.usermgmt.UserService
import jakarta.inject.Inject
import jakarta.inject.Singleton
import jakarta.transaction.Transactional

@Singleton
class SettingsService @Inject constructor(
    folderRepo: TenantFolderRepo,
    albumRepo: TenantAlbumRepo,
    private val settingsGroupRepo: TenantGroupRepo,
    private val settingsGroupMemberRepo: TenantGroupMemberRepo,
    private val tenantService: TenantService,
    private val userRepo: UserRepo,
    private val passwordService: PasswordService,
    requestContext: RequestContext,
) : LibraryService(folderRepo, albumRepo, settingsGroupRepo, settingsGroupMemberRepo, requestContext) {

    fun settings(path: String?): SettingsResponse {
        val normalized = normalizePath(path)
        return when (normalized) {
            "/settings" -> serverSettingsContext()
            "/~me" -> userSettingsContext()
            else -> tenantSettingsContext(normalized)
        }
    }

    fun serverSettings(): ServerSettingsResponse {
        requireServerOwner()
        return ServerSettingsResponse("Server", true)
    }

    fun mySettings(): UserSettingsResponse =
        UserService.toUserSummary(user()).let {
            UserSettingsResponse(it.userId, it.email, it.displayName)
        }

    @Transactional
    fun updateMySettings(request: UpdateUserSettingsRequest): UserSettingsResponse {
        val user = user()
        val displayName = request.displayName?.trim()?.takeIf { it.isNotBlank() }
            ?: throw BadRequestException("displayName must not be blank")
        val email = request.email?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            ?: throw BadRequestException("email must not be blank")
        val emailOwner = userRepo.findByEmail(email)
        if (emailOwner != null && emailOwner.id != user.id) {
            throw ConflictException("Email already exists")
        }

        user.displayName = displayName
        user.email = email

        return UserSettingsResponse(user.id, user.email, user.displayName)
    }

    @Transactional
    fun changeMyPassword(request: ChangePasswordRequest) {
        val currentPassword = request.currentPassword ?: throw BadRequestException("currentPassword must not be blank")
        val newPassword = request.newPassword?.takeIf { it.length >= 8 }
            ?: throw BadRequestException("newPassword must be at least 8 characters")
        val user = userRepo.findById(user().id) ?: throw NotFoundException("User not found")
        if (!passwordService.matches(currentPassword, user.passwordHash)) {
            throw UnauthorizedException("Invalid current password")
        }
        user.passwordHash = passwordService.hash(newPassword)
    }

    fun tenantSettings(tenantName: String): TenantSettingsResponse =
        withTenant(tenantName) {
            TenantSettingsResponse(tenant().id, tenant().name, tenant().displayName)
        }

    @Transactional
    fun updateTenantSettings(tenantName: String, request: UpdateNamedSettingsRequest): TenantSettingsResponse =
        withTenant(tenantName) {
            tenant().displayName = request.displayName?.trim()?.takeIf { it.isNotBlank() }
                ?: throw BadRequestException("displayName must not be blank")
            TenantSettingsResponse(tenant().id, tenant().name, tenant().displayName)
        }

    fun tenantUserSettings(tenantName: String): TenantUserSettingsResponse =
        withTenant(tenantName) {
            TenantUserSettingsResponse(tenant().id, tenant().name, user().id, user().displayName)
        }

    fun groupSettings(tenantName: String, groupName: String): GroupSettingsResponse =
        withTenant(tenantName) {
            val group = tenantGroup(groupName)
            requireGroupMembership(group)
            GroupSettingsResponse(tenant().id, tenant().name, group.id, group.name, group.displayName)
        }

    @Transactional
    fun updateGroupSettings(tenantName: String, groupName: String, request: UpdateNamedSettingsRequest): GroupSettingsResponse =
        withTenant(tenantName) {
            val group = tenantGroup(groupName)
            group.displayName = request.displayName?.trim()?.takeIf { it.isNotBlank() }
                ?: throw BadRequestException("displayName must not be blank")
            GroupSettingsResponse(tenant().id, tenant().name, group.id, group.name, group.displayName)
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

    private fun normalizePath(path: String?): String {
        val normalized = path?.trim()?.takeIf { it.isNotBlank() } ?: throw BadRequestException("path is required")
        val segments = normalized.trim('/').split('/').filter { it.isNotBlank() }
        return "/" + segments.joinToString("/")
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

    private fun ResolvedExplorerLocation.toSubjectType(): SettingsSubjectType =
        when (this) {
            ResolvedExplorerLocation.Tenant -> SettingsSubjectType.TENANT
            is ResolvedExplorerLocation.Group -> SettingsSubjectType.GROUP
            is ResolvedExplorerLocation.User -> SettingsSubjectType.TENANT_USER
            is ResolvedExplorerLocation.Folder -> SettingsSubjectType.FOLDER
            is ResolvedExplorerLocation.Album -> SettingsSubjectType.ALBUM
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

    private fun <T> withTenant(tenantName: String, block: () -> T): T {
        val tenant = tenantService.findTenantByName(tenantName)
        tenantService.requireTenantMembership(tenant, user())
        requestContext.tenant = tenant
        return block()
    }

    private fun tenantGroup(groupName: String) =
        settingsGroupRepo.findByTenantAndName(tenant(), groupName)
            ?: throw NotFoundException("Group $groupName not found")

    private fun requireGroupMembership(group: TenantGroup) {
        settingsGroupMemberRepo.findByGroupAndUser(group, user())
            ?: throw NotFoundException("Group $group.name not found")
    }

    private fun requireServerOwner() {
        if (user().role != ServerRole.ServerOwner) {
            throw ForbiddenException("User ${user().displayName} has insufficient role on server")
        }
    }

    private fun pathSegments(path: String): List<String> =
        path.trim('/').split('/').filter { it.isNotBlank() }

    private companion object {
        const val SETTINGS_PATH_SEGMENT = "settings"
        const val SETTINGS_DISPLAY_NAME = "Einstellungen"
        const val SERVER_DISPLAY_NAME = "Server"
    }
}
