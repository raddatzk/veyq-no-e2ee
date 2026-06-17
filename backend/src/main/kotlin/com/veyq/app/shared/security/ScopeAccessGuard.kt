package com.veyq.app.shared.security

import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.keymgmt.ScopeGrantRepository
import com.veyq.app.shared.keymgmt.ScopeRepository
import com.veyq.app.shared.tenancy.MembershipRepository
import com.veyq.app.shared.tenancy.MembershipStatus
import jakarta.enterprise.context.ApplicationScoped

/**
 * The single scope-access gate. Holding a scope grant is necessary but, for a TENANT scope, not
 * sufficient: the caller must ALSO be an ACTIVE member of the tenant. That second layer is defense in
 * depth — a missed grant cleanup (member removed, grant row left behind) must not silently become an
 * IDOR that hands out content or wrapped key material. Personal scopes have no tenant, so the grant alone
 * is authoritative.
 *
 * New call sites should use this instead of re-deriving the check (which had already drifted: some
 * key-material endpoints and the live-journal relay checked only the grant). See AD-03 / security-model.
 */
@ApplicationScoped
class ScopeAccessGuard(
    private val sessions: SessionService,
    private val scopes: ScopeRepository,
    private val grants: ScopeGrantRepository,
    private val memberships: MembershipRepository,
) {
    /** Resolve a `Bearer` header and assert scope access; returns the userId. Throws 401/403/404. */
    fun requireKeyHolder(auth: String?, scopeId: String): String {
        val userId = sessions.resolve(auth) ?: throw ApiException(401, "UNAUTHORIZED", "Missing or invalid session")
        assertKeyHolder(userId, scopeId)
        return userId
    }

    /** Assert scope access for an already-resolved user (e.g. a websocket that read the token itself). */
    fun assertKeyHolder(userId: String, scopeId: String) {
        val scope = scopes.findById(scopeId) ?: throw ApiException(404, "NOT_FOUND", "Scope not found")
        grants.find(scope.id, userId) ?: throw ApiException(403, "NOT_A_KEY_HOLDER", "You do not hold this scope")
        if (!isActiveMember(userId, scope.tenantId)) {
            throw ApiException(403, "NOT_A_MEMBER", "You are not an active member of this tenant")
        }
    }

    /** True if the caller holds the grant AND (for tenant scopes) is an active member. No throw. */
    fun isActiveKeyHolder(userId: String, scopeId: String): Boolean {
        val scope = scopes.findById(scopeId) ?: return false
        if (grants.find(scope.id, userId) == null) return false
        return isActiveMember(userId, scope.tenantId)
    }

    /** Membership layer only: personal scopes (no tenant) pass; tenant scopes require ACTIVE membership. */
    fun isActiveMember(userId: String, tenantId: String?): Boolean =
        tenantId == null || memberships.find(tenantId, userId)?.status == MembershipStatus.ACTIVE
}
