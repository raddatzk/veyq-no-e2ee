package com.lunva.api.tenant

import com.lunva.api.auth.BadRequestException
import com.lunva.api.auth.ConflictException
import com.lunva.api.auth.NotFoundException
import com.lunva.api.auth.filterchain.RequestContext
import com.lunva.api.domain.Tenant
import com.lunva.api.domain.TenantMember
import com.lunva.api.domain.TenantMemberId
import com.lunva.api.domain.TenantMemberRepo
import com.lunva.api.domain.TenantRole
import com.lunva.api.domain.User
import com.lunva.api.domain.UserRepo
import com.lunva.api.generated.model.InviteTenantUserRequest
import com.lunva.api.generated.model.TenantUserSearchResponse
import com.lunva.api.generated.model.TenantUserSearchSummary
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
) {

    fun tenantUserSettings(tenantName: String): TenantUserSettingsResponse =
        TenantUserSettingsResponse(tenant().id, tenant().name, user().id, user().displayName)

    fun tenantUsers(tenantName: String): TenantUsersSummary =
        TenantUsersSummary(
            tenantMemberRepo.listByTenant(tenant())
                .sortedWith(compareBy<TenantMember> { it.user.displayName.lowercase() }.thenBy { it.user.email })
                .map { it.toTenantUserSummary() }
        )

    fun searchTenantUsers(tenantName: String, q: String): TenantUserSearchResponse {
        val query = q.trim()
        if (query.length < MIN_SEARCH_LENGTH) {
            throw BadRequestException("Search query must contain at least $MIN_SEARCH_LENGTH characters")
        }
        val existingUserIds = tenantMemberRepo.listByTenant(tenant()).map { it.user.id }.toSet()
        val users = userRepo.searchByNameOrEmail(query)
            .asSequence()
            .filter { it.id !in existingUserIds }
            .take(MAX_SEARCH_RESULTS)
            .map { TenantUserSearchSummary(it.id, it.displayName, maskEmail(it.email)) }
            .toList()

        return TenantUserSearchResponse(users)
    }

    @Transactional
    fun inviteTenantUser(tenantName: String, userId: Long, request: InviteTenantUserRequest): TenantUserSummary {
        val tenant = tenant()
        val targetUser = userRepo.findById(userId) ?: throw NotFoundException("User not found")
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

    private fun maskEmail(email: String): String {
        val local = email.substringBefore("@")
        val domain = email.substringAfter("@", "")
        if (domain.isBlank()) return maskToken(local)
        return "${maskToken(local)}@${domain.split(".").joinToString(".") { maskDomainPart(it) }}"
    }

    private fun maskToken(value: String): String =
        when (value.length) {
            0 -> "***"
            1 -> "${value.first()}***"
            2 -> "${value.first()}***${value.last()}"
            else -> "${value.first()}***${value.last()}"
        }

    private fun maskDomainPart(value: String): String =
        when (value.length) {
            0 -> "***"
            1 -> "${value.first()}**"
            else -> "${value.first()}**"
        }

    private companion object {
        const val MIN_SEARCH_LENGTH = 3
        const val MAX_SEARCH_RESULTS = 10
    }
}
