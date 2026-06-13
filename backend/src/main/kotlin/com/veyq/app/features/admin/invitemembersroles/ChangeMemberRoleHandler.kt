package com.veyq.app.features.admin.invitemembersroles

import com.veyq.app.generated.model.ChangeMemberRoleRequest
import com.veyq.app.generated.model.MembershipSummary
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.security.SessionService
import com.veyq.app.shared.tenancy.MembershipRepository
import com.veyq.app.shared.tenancy.TenantRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

/**
 * uc.admin.invite-members-roles (A3, role change) — a Tenant Owner reassigns a member's role. Roles
 * govern membership/settings, not content (ADR 0005), so this touches no grants. Demoting the last
 * Owner is rejected to avoid an ownerless tenant.
 */
@ApplicationScoped
class ChangeMemberRoleHandler(
    private val tenants: TenantRepository,
    private val memberships: MembershipRepository,
    private val sessions: SessionService,
) {
    @Transactional
    fun changeMemberRole(auth: String?, tenantId: String, userId: String, req: ChangeMemberRoleRequest): MembershipSummary {
        val callerId = sessions.resolve(auth) ?: throw unauthorized()
        tenants.findById(tenantId) ?: throw notFound("Tenant")

        val callerMembership = memberships.find(tenantId, callerId)
        if (callerMembership == null || callerMembership.role != "Owner" || callerMembership.status != "ACTIVE") {
            throw ApiException(403, "FORBIDDEN", "Tenant Owner role required")
        }

        val membership = memberships.find(tenantId, userId) ?: throw notFound("Membership")
        val newRole = req.role.value

        // never demote the last remaining Owner — the tenant must keep an owner
        if (membership.role == "Owner" && newRole != "Owner") {
            val activeOwners = memberships.listActive(tenantId).count { it.role == "Owner" }
            if (activeOwners <= 1) throw ApiException(409, "LAST_OWNER", "Cannot demote the last Owner")
        }
        membership.role = newRole

        return MembershipSummary(tenantId = tenantId, userId = userId, role = membership.role, status = membership.status)
    }

    private fun unauthorized() = ApiException(401, "UNAUTHORIZED", "Missing or invalid session")
    private fun notFound(what: String) = ApiException(404, "NOT_FOUND", "$what not found")
}
