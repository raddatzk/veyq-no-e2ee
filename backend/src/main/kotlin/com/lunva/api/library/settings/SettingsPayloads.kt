package com.lunva.api.library.settings

data class ServerSettingsResponse(val displayName: String, val userManagementAvailable: Boolean)

data class UserSettingsResponse(val userId: Long, val email: String, val displayName: String)

data class UpdateUserSettingsRequest(val displayName: String?, val email: String?)

data class ChangePasswordRequest(val currentPassword: String?, val newPassword: String?)

data class TenantSettingsResponse(val tenantId: Long, val name: String, val displayName: String)

data class TenantUserSettingsResponse(
    val tenantId: Long,
    val tenantName: String,
    val userId: Long,
    val displayName: String,
)

data class GroupSettingsResponse(
    val tenantId: Long,
    val tenantName: String,
    val groupId: Long,
    val name: String,
    val displayName: String,
)

data class UpdateNamedSettingsRequest(val displayName: String?)
