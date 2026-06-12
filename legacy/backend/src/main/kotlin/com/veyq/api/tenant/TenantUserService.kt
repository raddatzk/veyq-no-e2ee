package com.veyq.api.tenant

import com.veyq.api.auth.BadRequestException
import com.veyq.api.auth.ConflictException
import com.veyq.api.auth.NotFoundException
import com.veyq.api.auth.PasswordService
import com.veyq.api.auth.filterchain.RequestContext
import com.veyq.api.common.Permalinks
import com.veyq.api.domain.ServerRole
import com.veyq.api.domain.TenantMember
import com.veyq.api.domain.TenantMemberId
import com.veyq.api.domain.TenantMemberRepo
import com.veyq.api.domain.TenantMemberStatus
import com.veyq.api.domain.TenantRole
import com.veyq.api.domain.User
import com.veyq.api.domain.UserRepo
import com.veyq.api.generated.model.InviteTenantUserRequest
import com.veyq.api.generated.model.PublicUserSummary
import com.veyq.api.generated.model.TenantUserSettingsResponse
import com.veyq.api.generated.model.TenantUserStatus
import com.veyq.api.generated.model.TenantUserSummary
import com.veyq.api.generated.model.TenantUsersSummary
import com.veyq.api.generated.model.UpdateTenantUserRoleRequest
import com.veyq.api.settings.CapabilityAcknowledgementService
import com.veyq.api.usermgmt.InvitationService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import java.time.Instant
import com.veyq.api.generated.model.TenantRole as ApiTenantRole

@ApplicationScoped
class TenantUserService @Inject constructor(
    private val requestContext: RequestContext,
    private val tenantMemberRepo: TenantMemberRepo,
    private val userRepo: UserRepo,
    private val invitationService: InvitationService,
    private val passwordService: PasswordService,
    private val capabilityAcknowledgementService: CapabilityAcknowledgementService,
) {

    fun tenantUserSettings(tenantName: String): TenantUserSettingsResponse =
        TenantUserSettingsResponse(tenant().id, tenant().name, user().id, user().displayName)

    fun tenantUsers(tenantName: String): TenantUsersSummary =
        TenantUsersSummary(
            tenantMemberRepo.listByTenant(tenant())
                .filter { canManageTenantUsers() || it.status == TenantMemberStatus.Active }
                .filter { it.user.deletedAt == null && !it.user.systemUser }
                .sortedWith(compareBy<TenantMember> { it.user.displayName.lowercase() }.thenBy { it.user.email })
                .map { it.toTenantUserSummary(includeManagementFields = canManageTenantUsers()) }
        )

    @Transactional
    fun inviteTenantUser(tenantName: String, request: InviteTenantUserRequest): TenantUserSummary {
        val tenant = tenant()
        val targetUserLookup = findOrCreatePendingUser(request.email)
        val targetUser = targetUserLookup.user
        tenantMemberRepo.findByTenantAndUser(tenant, targetUser)?.let { existing ->
            if (existing.status != TenantMemberStatus.Removed) {
                throw ConflictException("User is already member of this tenant")
            }
            existing.role = request.role.toDomainRole()
            existing.status = TenantMemberStatus.Pending
            existing.acceptedAt = null
            existing.removedAt = null
            existing.removedBy = null
            invitationService.createAndSendInvitation(targetUser, tenant)
            return existing.toTenantUserSummary(includeManagementFields = true)
        }

        val membership = TenantMember().also {
            it.id = TenantMemberId(tenant.id, targetUser.id)
            it.tenant = tenant
            it.user = targetUser
            it.role = request.role.toDomainRole()
            it.acceptedAt = null
            it.status = TenantMemberStatus.Pending
            tenantMemberRepo.persist(it)
        }

        if (targetUserLookup.created) {
            invitationService.createAndSendInvitationWithUserCompletion(targetUser, tenant)
        } else {
            invitationService.createAndSendInvitation(targetUser, tenant)
        }

        return membership.toTenantUserSummary(includeManagementFields = true)
    }

    @Transactional
    fun reinviteTenantUser(tenantName: String, userId: Long): TenantUserSummary {
        val tenant = tenant()
        val targetUser = userRepo.findById(userId) ?: throw NotFoundException("User not found")
        val membership = tenantMemberRepo.findByTenantAndUser(tenant, targetUser)
            ?: throw NotFoundException("Tenant user not found")

        if (membership.status != TenantMemberStatus.Pending) {
            throw ConflictException("User is already active")
        }

        invitationService.createAndSendInvitation(targetUser, tenant)
        return membership.toTenantUserSummary(includeManagementFields = true)
    }

    @Transactional
    fun updateTenantUserRole(tenantName: String, userId: Long, request: UpdateTenantUserRoleRequest): TenantUserSummary {
        val targetUser = userRepo.findById(userId) ?: throw NotFoundException("User not found")
        val membership = tenantMemberRepo.findByTenantAndUser(tenant(), targetUser)
            ?: throw NotFoundException("Tenant user not found")
        val nextRole = request.role.toDomainRole()

        if (
            membership.status == TenantMemberStatus.Active &&
            membership.role == TenantRole.TenantOwner &&
            nextRole != TenantRole.TenantOwner &&
            tenantMemberRepo.countActiveTenantOwners(tenant()) <= 1
        ) {
            throw ConflictException("At least one tenant admin is required")
        }

        membership.role = nextRole
        return membership.toTenantUserSummary(includeManagementFields = true)
    }

    @Transactional
    fun removeTenantUser(tenantName: String, userId: Long) {
        val targetUser = userRepo.findById(userId) ?: throw NotFoundException("User not found")
        val membership = tenantMemberRepo.findByTenantAndUser(tenant(), targetUser)
            ?: throw NotFoundException("Tenant user not found")

        if (membership.status == TenantMemberStatus.Removed) return
        if (
            membership.status == TenantMemberStatus.Active &&
            membership.role == TenantRole.TenantOwner &&
            tenantMemberRepo.countActiveTenantOwners(tenant()) <= 1
        ) {
            throw ConflictException("At least one tenant admin is required")
        }

        membership.status = TenantMemberStatus.Removed
        membership.removedAt = Instant.now()
        membership.removedBy = user()
    }

    private fun tenant() = requestContext.requireTenant()
    private fun user() = requestContext.requireUser()

    private fun findOrCreatePendingUser(email: String): PendingUserLookup {
        val normalizedEmail = normalizeEmail(email)
        userRepo.findByEmail(normalizedEmail)?.let { return PendingUserLookup(it, created = false) }
        val user = User().also {
            it.email = normalizedEmail
            it.displayName = normalizedEmail.substringBefore("@").ifBlank { normalizedEmail }
            it.permalink = uniquePermalink(it.displayName)
            it.role = ServerRole.ServerMember
            it.passwordHash = passwordService.randomPasswordHash()
            it.passwordSetAt = null
            it.persistAndFlush()
            capabilityAcknowledgementService.acknowledgeCurrentAnnouncements(it)
        }
        return PendingUserLookup(user, created = true)
    }

    private fun normalizeEmail(email: String?): String =
        email?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            ?: throw BadRequestException("email must not be blank")

    private fun uniquePermalink(displayName: String): String {
        val base = Permalinks.normalize(displayName).takeIf { it.isNotBlank() && it != "me" } ?: "user"
        var candidate = base
        var suffix = 2
        while (userRepo.findByPermalink(candidate) != null) {
            val nextSuffix = "-${suffix++}"
            candidate = base.take(120 - nextSuffix.length).trimEnd('-') + nextSuffix
        }
        return candidate
    }

    private fun canManageTenantUsers(): Boolean {
        if (user().role == ServerRole.ServerOwner) return true
        return tenantMemberRepo.findByTenantAndUser(tenant(), user())?.role == TenantRole.TenantOwner
    }

    private fun TenantMember.toTenantUserSummary(includeManagementFields: Boolean): TenantUserSummary =
        TenantUserSummary(
            user.toPublicUserSummary(),
            if (includeManagementFields) user.email else null,
            if (includeManagementFields) role.toApiRole() else null,
            if (includeManagementFields) {
                when {
                    user.disabledAt != null -> TenantUserStatus.Disabled
                    acceptedAt == null || status == TenantMemberStatus.Pending -> TenantUserStatus.Pending
                    status == TenantMemberStatus.Removed -> TenantUserStatus.Removed
                    else -> TenantUserStatus.Active
                }
            } else {
                null
            },
        )

    private fun User.toPublicUserSummary(): PublicUserSummary =
        PublicUserSummary(
            id,
            displayName,
            permalink,
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

    private data class PendingUserLookup(val user: User, val created: Boolean)

}
