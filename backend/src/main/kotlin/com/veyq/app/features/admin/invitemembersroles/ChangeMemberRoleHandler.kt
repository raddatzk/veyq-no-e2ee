package com.veyq.app.features.admin.invitemembersroles

import com.veyq.app.generated.model.ChangeMemberRoleRequest
import com.veyq.app.generated.model.MembershipSummary
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.security.TenantAdminGuard
import com.veyq.app.shared.tenancy.MembershipRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import com.veyq.app.platform.error.notFound

/**
 * uc.admin.invite-members-roles (A3, role change) — a Tenant Owner reassigns a member's role. Roles
 * govern membership/settings, not content (ADR 0005), so this touches no grants. Demoting the last
 * Owner is rejected to avoid an ownerless tenant.
 */
@ApplicationScoped
class ChangeMemberRoleHandler(
    private val memberships: MembershipRepository,
    private val guard: TenantAdminGuard,
) {
    @Transactional
    fun changeMemberRole(auth: String?, tenantId: String, userId: String, req: ChangeMemberRoleRequest): MembershipSummary {
        guard.requireOwner(auth, tenantId)

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

}
