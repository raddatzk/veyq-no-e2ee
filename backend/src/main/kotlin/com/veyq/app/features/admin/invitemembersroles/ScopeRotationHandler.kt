package com.veyq.app.features.admin.invitemembersroles

import com.veyq.app.generated.model.RotateScopeRequest
import com.veyq.app.generated.model.RotateScopeResponse
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.crypto.CryptoSuiteRegistry
import com.veyq.app.shared.keymgmt.ScopeGrant
import com.veyq.app.shared.keymgmt.ScopeGrantRepository
import com.veyq.app.shared.keymgmt.ScopeRepository
import com.veyq.app.shared.security.SessionService
import com.veyq.app.shared.tenancy.MembershipRepository
import com.veyq.app.shared.tenancy.TenantRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * uc.admin.invite-members-roles (A1, scope rotation — eventual layer of AD-24) — a key-holding Owner
 * mints a NEW scope keypair (client-side) and re-wraps it to every active member. The server swaps the
 * scope public key and atomically replaces all grants, denying a removed member key access to future
 * content. The request must cover exactly the active members — no member left out, no ghost grants.
 */
@ApplicationScoped
class ScopeRotationHandler(
    private val tenants: TenantRepository,
    private val memberships: MembershipRepository,
    private val scopes: ScopeRepository,
    private val grants: ScopeGrantRepository,
    private val contentKeys: com.veyq.app.shared.content.ScopeContentKeyRepository,
    private val sessions: SessionService,
    private val suites: CryptoSuiteRegistry,
) {
    private val dec = Base64.getUrlDecoder()

    @Transactional
    fun rotateScope(auth: String?, tenantId: String, req: RotateScopeRequest): RotateScopeResponse {
        val callerId = sessions.resolve(auth) ?: throw unauthorized()
        tenants.findById(tenantId) ?: throw notFound("Tenant")
        val scope = scopes.findByTenantId(tenantId) ?: throw notFound("Scope")
        requireSuite(req.scopePublicKey.suiteId)
        req.grants.forEach { requireSuite(it.wrappedScopePrivateKey.suiteId) }

        // authority: an active Owner who actually holds the scope (you can only re-key what you can read)
        val callerMembership = memberships.find(tenantId, callerId)
        if (callerMembership == null || callerMembership.role != "Owner" || callerMembership.status != "ACTIVE") {
            throw ApiException(403, "FORBIDDEN", "Tenant Owner role required")
        }
        grants.find(scope.id, callerId) ?: throw ApiException(403, "NOT_A_KEY_HOLDER", "You do not hold this scope")

        // the new grants must cover exactly the active members — nobody dropped, no outsiders added
        val activeMemberIds = memberships.listActive(tenantId).map { it.userId }.toSet()
        val grantUserIds = req.grants.map { it.userId }
        if (grantUserIds.size != grantUserIds.toSet().size) {
            throw ApiException(400, "DUPLICATE_GRANT", "Duplicate recipient in rotation")
        }
        if (grantUserIds.toSet() != activeMemberIds) {
            throw ApiException(400, "INCOMPLETE_ROTATION", "Rotation must re-grant exactly the active members")
        }

        val now = Instant.now()
        // swap the scope public key and atomically replace every grant
        scope.suiteId = req.scopePublicKey.suiteId
        scope.kemPublicKey = dec.decode(req.scopePublicKey.kemPublicKey)
        grants.deleteByScopeId(scope.id)
        req.grants.forEach { input ->
            grants.persist(
                ScopeGrant().apply {
                    id = UUID.randomUUID().toString(); scopeId = scope.id; userId = input.userId
                    suiteId = input.wrappedScopePrivateKey.suiteId
                    wrappedPrivateKey = dec.decode(input.wrappedScopePrivateKey.bytes); createdAt = now
                },
            )
        }

        // the content key is stable across rotation, but its wrapping is to the OLD keypair — re-wrap it to
        // the new one so encrypted content survives. Required iff the scope actually has a content key.
        val contentKey = contentKeys.findByScopeId(scope.id)
        if (contentKey != null) {
            val rewrapped = req.wrappedContentKey
                ?: throw ApiException(400, "CONTENT_KEY_REQUIRED", "The scope has content — re-wrap its content key to the new keypair")
            requireSuite(rewrapped.suiteId)
            contentKey.suiteId = rewrapped.suiteId
            contentKey.wrappedKey = dec.decode(rewrapped.bytes)
        }

        return RotateScopeResponse(scopeId = scope.id, memberCount = req.grants.size)
    }

    private fun requireSuite(suiteId: String) {
        if (!suites.isKnown(suiteId)) throw ApiException(400, "UNKNOWN_SUITE", "Unknown crypto suite: $suiteId")
    }

    private fun unauthorized() = ApiException(401, "UNAUTHORIZED", "Missing or invalid session")
    private fun notFound(what: String) = ApiException(404, "NOT_FOUND", "$what not found")
}
