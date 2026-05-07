package com.lunva.api.tenant

import com.lunva.api.auth.BadRequestException
import com.lunva.api.auth.ConflictException
import com.lunva.api.auth.NotFoundException
import com.lunva.api.auth.PasswordService
import com.lunva.api.auth.filterchain.RequestContext
import com.lunva.api.domain.ServerRole
import com.lunva.api.domain.Tenant
import com.lunva.api.domain.TenantMember
import com.lunva.api.domain.TenantMemberId
import com.lunva.api.domain.TenantMemberRepo
import com.lunva.api.domain.TenantRole
import com.lunva.api.domain.User
import com.lunva.api.domain.UserRepo
import com.lunva.api.generated.model.InviteTenantUserRequest
import com.lunva.api.generated.model.TenantUserSettingsResponse
import com.lunva.api.generated.model.TenantUserStatus
import com.lunva.api.generated.model.TenantUserSummary
import com.lunva.api.generated.model.TenantUsersSummary
import com.lunva.api.generated.model.UpdateTenantUserRoleRequest
import com.lunva.api.usermgmt.InvitationService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import java.time.Instant
import com.lunva.api.generated.model.TenantRole as ApiTenantRole

@ApplicationScoped
class TenantUserService @Inject constructor(
    private val requestContext: RequestContext,
    private val tenantMemberRepo: TenantMemberRepo,
    private val userRepo: UserRepo,
    private val invitationService: InvitationService,
    private val passwordService: PasswordService,
) {

    fun tenantUserSettings(tenantName: String): TenantUserSettingsResponse =
        TenantUserSettingsResponse(tenant().id, tenant().name, user().id, user().displayName)

    fun tenantUsers(tenantName: String): TenantUsersSummary =
        TenantUsersSummary(
            tenantMemberRepo.listByTenant(tenant())
                .sortedWith(compareBy<TenantMember> { it.user.displayName.lowercase() }.thenBy { it.user.email })
                .map { it.toTenantUserSummary() }
        )

    @Transactional
    fun inviteTenantUser(tenantName: String, request: InviteTenantUserRequest): TenantUserSummary {
        val tenant = tenant()
        val targetUser = findOrCreatePendingUser(request.email)
        if (tenantMemberRepo.findByTenantAndUser(tenant, targetUser) != null) {
            throw ConflictException("User is already member of this tenant")
        }

        val membership = TenantMember().also {
            it.id = TenantMemberId(tenant.id, targetUser.id)
            it.tenant = tenant
            it.user = targetUser
            it.role = request.role.toDomainRole()
            it.acceptedAt = null
            tenantMemberRepo.persist(it)
        }

        invitationService.createAndSendInvitation(targetUser, tenant)

        return membership.toTenantUserSummary()
    }

    @Transactional
    fun reinviteTenantUser(tenantName: String, userId: Long): TenantUserSummary {
        val tenant = tenant()
        val targetUser = userRepo.findById(userId) ?: throw NotFoundException("User not found")
        val membership = tenantMemberRepo.findByTenantAndUser(tenant, targetUser)
            ?: throw NotFoundException("Tenant user not found")

        if (membership.acceptedAt != null) {
            throw ConflictException("User is already active")
        }

        invitationService.createAndSendInvitation(targetUser, tenant)
        return membership.toTenantUserSummary()
    }

    @Transactional
    fun updateTenantUserRole(tenantName: String, userId: Long, request: UpdateTenantUserRoleRequest): TenantUserSummary {
        val targetUser = userRepo.findById(userId) ?: throw NotFoundException("User not found")
        val membership = tenantMemberRepo.findByTenantAndUser(tenant(), targetUser)
            ?: throw NotFoundException("Tenant user not found")
        val nextRole = request.role.toDomainRole()

        if (
            membership.acceptedAt != null &&
            membership.role == TenantRole.TenantOwner &&
            nextRole != TenantRole.TenantOwner &&
            tenantMemberRepo.countActiveTenantOwners(tenant()) <= 1
        ) {
            throw ConflictException("At least one tenant admin is required")
        }

        membership.role = nextRole
        return membership.toTenantUserSummary()
    }

    private fun tenant() = requestContext.tenant!!
    private fun user() = requestContext.user!!

    private fun findOrCreatePendingUser(email: String): User {
        val normalizedEmail = normalizeEmail(email)
        return userRepo.findByEmail(normalizedEmail) ?: User().also {
            it.email = normalizedEmail
            it.displayName = normalizedEmail.substringBefore("@").ifBlank { normalizedEmail }
            it.role = ServerRole.ServerMember
            it.passwordHash = passwordService.randomPasswordHash()
            it.passwordSetAt = null
            it.persistAndFlush()
        }
    }

    private fun normalizeEmail(email: String?): String =
        email?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            ?: throw BadRequestException("email must not be blank")

    private fun TenantMember.toTenantUserSummary(): TenantUserSummary =
        TenantUserSummary(
            user.id,
            user.email,
            user.displayName,
            role.toApiRole(),
            if (acceptedAt == null) TenantUserStatus.Pending else TenantUserStatus.Active,
        )

    private fun TenantRole.toApiRole(): ApiTenantRole =
        when (this) {
            TenantRole.TenantOwner -> ApiTenantRole.TenantOwner
            TenantRole.TenantMember -> ApiTenantRole.TenantMember
        }

    private fun ApiTenantRole.toDomainRole(): TenantRole =
        when (this) {
            ApiTenantRole.TenantOwner -> TenantRole.TenantOwner
            ApiTenantRole.TenantMember -> TenantRole.TenantMember
        }

}
