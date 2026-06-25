package com.veyq.app.shared.security

import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.tenancy.Membership
import com.veyq.app.shared.tenancy.MembershipRepository
import com.veyq.app.shared.tenancy.MembershipStatus
import com.veyq.app.shared.tenancy.MemberRole
import jakarta.enterprise.context.ApplicationScoped

/**
 * The single tenant-administration gate: the caller must be an ACTIVE Owner of the tenant. Used by the
 * member-management, invite, role-change, scope-rotation and tenant-terms/lifecycle handlers, which all
 * re-derived the identical `memberships.find(...); role == "Owner" && status == "ACTIVE"` check. Centralizing
 * it mirrors what [ScopeAccessGuard] did for scope access, so the admin authorization can't drift per handler.
 */
@ApplicationScoped
class TenantAdminGuard(
    private val sessions: SessionService,
    private val memberships: MembershipRepository,
) {
    /** Resolve a `Bearer` header and assert the caller is an ACTIVE Owner of [tenantId]; returns the userId. */
    fun requireOwner(auth: String?, tenantId: String): String {
        val userId = sessions.resolve(auth) ?: throw ApiException(401, "UNAUTHORIZED", "Missing or invalid session")
        requireOwner(userId, tenantId)
        return userId
    }

    /** Assert an already-resolved [userId] is an ACTIVE Owner of [tenantId]. Returns their membership. */
    fun requireOwner(userId: String, tenantId: String): Membership {
        val m = memberships.find(tenantId, userId)
        if (m == null || m.role != MemberRole.Owner || m.status != MembershipStatus.ACTIVE) {
            throw ApiException(403, "FORBIDDEN", "Tenant Owner role required")
        }
        return m
    }

    /** Resolve a `Bearer` header and assert the caller is an ACTIVE member (any role) of [tenantId]; returns userId. */
    fun requireActiveMember(auth: String?, tenantId: String): String {
        val userId = sessions.resolve(auth) ?: throw ApiException(401, "UNAUTHORIZED", "Missing or invalid session")
        val m = memberships.find(tenantId, userId)
        if (m == null || m.status != MembershipStatus.ACTIVE) throw ApiException(403, "FORBIDDEN", "Tenant membership required")
        return userId
    }
}
