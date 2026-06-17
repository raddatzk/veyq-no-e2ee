package com.veyq.app.features.admin.invitemembersroles

import com.veyq.app.generated.model.MembershipSummary
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.keymgmt.ScopeGrantRepository
import com.veyq.app.shared.keymgmt.ScopeRepository
import com.veyq.app.shared.security.SessionService
import com.veyq.app.shared.security.TenantAdminGuard
import com.veyq.app.shared.tenancy.MembershipRepository
import com.veyq.app.shared.tenancy.MembershipStatus
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import com.veyq.app.platform.error.notFound

/**
 * uc.admin.invite-members-roles (A1, removal — instant layer of AD-24) — a Tenant Owner soft-removes a
 * member: the membership becomes REMOVED (rejoinable), their scope grant is deleted and their sessions
 * are invalidated, so the server immediately stops handing them keys or resolving their tokens. Eventual
 * scope-key rotation is a separate step; already-downloaded content cannot be retracted.
 */
@ApplicationScoped
class RemoveMemberHandler(
    private val memberships: MembershipRepository,
    private val scopes: ScopeRepository,
    private val grants: ScopeGrantRepository,
    private val sessions: SessionService,
    private val guard: TenantAdminGuard,
) {
    @Transactional
    fun removeMember(auth: String?, tenantId: String, userId: String): MembershipSummary {
        val callerId = guard.requireOwner(auth, tenantId) // ACTIVE Owner; implies the tenant exists
        if (userId == callerId) throw ApiException(400, "CANNOT_REMOVE_SELF", "Cannot remove yourself")

        val membership = memberships.find(tenantId, userId) ?: throw notFound("Membership")

        // revoke key access: drop the member's scope grant so the server can no longer hand it to them
        scopes.findByTenantId(tenantId)?.let { scope ->
            grants.find(scope.id, userId)?.let { grants.delete(it) }
        }
        // instant token revocation
        sessions.invalidateAll(userId)
        // soft removal — rejoinable; content + attribution are preserved
        membership.status = MembershipStatus.REMOVED

        return MembershipSummary(tenantId = tenantId, userId = userId, role = membership.role.name, status = membership.status.name)
    }

}
