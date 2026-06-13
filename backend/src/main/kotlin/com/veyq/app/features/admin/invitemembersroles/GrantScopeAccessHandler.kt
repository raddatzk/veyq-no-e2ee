package com.veyq.app.features.admin.invitemembersroles

import com.veyq.app.generated.model.GrantScopeAccessRequest
import com.veyq.app.generated.model.MembershipSummary
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.crypto.CryptoSuiteRegistry
import com.veyq.app.shared.identity.UserRepository
import com.veyq.app.shared.keymgmt.IdentityKeyRepository
import com.veyq.app.shared.keymgmt.ScopeGrant
import com.veyq.app.shared.keymgmt.ScopeGrantRepository
import com.veyq.app.shared.keymgmt.ScopeRepository
import com.veyq.app.shared.security.SessionService
import com.veyq.app.shared.tenancy.Membership
import com.veyq.app.shared.tenancy.MembershipRepository
import com.veyq.app.shared.tenancy.TenantRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * uc.admin.invite-members-roles (existing-user direct grant) — a Tenant Owner who holds the tenant
 * scope re-wraps its private key to an existing user's identity public key and assigns a role. The
 * server enforces that the caller is an Owner who actually holds the scope (you can only grant what you
 * can read) and stores ciphertext only — it never sees a scope private key.
 */
@ApplicationScoped
class GrantScopeAccessHandler(
    private val users: UserRepository,
    private val identityKeys: IdentityKeyRepository,
    private val tenants: TenantRepository,
    private val memberships: MembershipRepository,
    private val scopes: ScopeRepository,
    private val grants: ScopeGrantRepository,
    private val sessions: SessionService,
    private val suites: CryptoSuiteRegistry,
) {
    private val dec = Base64.getUrlDecoder()

    @Transactional
    fun grantScopeAccess(auth: String?, tenantId: String, req: GrantScopeAccessRequest): MembershipSummary {
        val callerId = sessions.resolve(auth) ?: throw unauthorized()
        tenants.findById(tenantId) ?: throw notFound("Tenant")
        val scope = scopes.findByTenantId(tenantId) ?: throw notFound("Scope")
        requireSuite(req.wrappedScopePrivateKey.suiteId)

        // authority: the caller must be an active Owner of the tenant …
        val callerMembership = memberships.find(tenantId, callerId)
        if (callerMembership == null || callerMembership.role != "Owner" || callerMembership.status != "ACTIVE") {
            throw ApiException(403, "FORBIDDEN", "Tenant Owner role required")
        }
        // … and actually hold the scope keys (you can only grant what you can read — E1)
        grants.find(scope.id, callerId) ?: throw ApiException(403, "NOT_A_KEY_HOLDER", "You do not hold this scope")

        // the recipient must exist and be in the key directory (have a published identity key)
        val recipient = users.findById(req.recipientUserId) ?: throw notFound("User")
        identityKeys.findByUserId(recipient.id) ?: throw notFound("Identity key")
        if (recipient.id == callerId) throw ApiException(400, "SELF_GRANT", "Cannot grant access to yourself")

        val now = Instant.now()

        // upsert the recipient's scope grant (re-granting just replaces the wrapped key)
        val existingGrant = grants.find(scope.id, recipient.id)
        if (existingGrant != null) {
            existingGrant.suiteId = req.wrappedScopePrivateKey.suiteId
            existingGrant.wrappedPrivateKey = dec.decode(req.wrappedScopePrivateKey.bytes)
        } else {
            grants.persist(
                ScopeGrant().apply {
                    id = newId(); scopeId = scope.id; userId = recipient.id
                    suiteId = req.wrappedScopePrivateKey.suiteId
                    wrappedPrivateKey = dec.decode(req.wrappedScopePrivateKey.bytes); createdAt = now
                },
            )
        }

        // upsert the membership + assigned role
        val membership = memberships.find(tenantId, recipient.id) ?: Membership().apply {
            id = newId(); this.tenantId = tenantId; userId = recipient.id; createdAt = now
            memberships.persist(this)
        }
        membership.role = req.role.value
        membership.status = "ACTIVE"

        return MembershipSummary(tenantId = tenantId, userId = recipient.id, role = membership.role, status = membership.status)
    }

    private fun requireSuite(suiteId: String) {
        if (!suites.isKnown(suiteId)) throw ApiException(400, "UNKNOWN_SUITE", "Unknown crypto suite: $suiteId")
    }

    private fun unauthorized() = ApiException(401, "UNAUTHORIZED", "Missing or invalid session")
    private fun notFound(what: String) = ApiException(404, "NOT_FOUND", "$what not found")
    private fun newId(): String = UUID.randomUUID().toString()
}
