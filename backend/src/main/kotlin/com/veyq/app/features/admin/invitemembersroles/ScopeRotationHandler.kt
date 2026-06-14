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
    private val items: com.veyq.app.shared.content.ScopeItemRepository,
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

        // rotation mints a FRESH content key (not a re-wrap) and re-seals every item under it, so a removed
        // member who cached the old content key cannot read content written after rotation (forward secrecy).
        // Existing content stays readable because it is re-sealed. Required iff the scope has a content key.
        val contentKey = contentKeys.findByScopeId(scope.id)
        if (contentKey != null) {
            val newCk = req.wrappedContentKey
                ?: throw ApiException(400, "CONTENT_KEY_REQUIRED", "The scope has content — provide a fresh content key wrapped to the new keypair")
            requireSuite(newCk.suiteId)
            val reSealed = req.reSealedItems
                ?: throw ApiException(400, "RESEAL_REQUIRED", "The scope has content — re-seal its items under the new content key")
            val scopeItems = items.listByScopeId(scope.id)
            val reSealedIds = reSealed.map { it.id }
            if (reSealedIds.size != reSealedIds.toSet().size) throw ApiException(400, "DUPLICATE_ITEM", "Duplicate item in re-seal")
            if (reSealedIds.toSet() != scopeItems.map { it.id }.toSet()) {
                throw ApiException(400, "INCOMPLETE_RESEAL", "Rotation must re-seal exactly the scope's items")
            }
            contentKey.suiteId = newCk.suiteId
            contentKey.wrappedKey = dec.decode(newCk.bytes)
            val byId = scopeItems.associateBy { it.id }
            reSealed.forEach { rs ->
                requireSuite(rs.sealedContent.suiteId)
                val item = byId.getValue(rs.id)
                item.suiteId = rs.sealedContent.suiteId
                item.sealedContent = dec.decode(rs.sealedContent.bytes)
            }
        }

        return RotateScopeResponse(scopeId = scope.id, memberCount = req.grants.size)
    }

    private fun requireSuite(suiteId: String) {
        if (!suites.isKnown(suiteId)) throw ApiException(400, "UNKNOWN_SUITE", "Unknown crypto suite: $suiteId")
    }

    private fun unauthorized() = ApiException(401, "UNAUTHORIZED", "Missing or invalid session")
    private fun notFound(what: String) = ApiException(404, "NOT_FOUND", "$what not found")
}
