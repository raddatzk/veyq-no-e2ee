package com.veyq.app.features.content.scopecontent

import com.veyq.app.generated.model.Blob
import com.veyq.app.generated.model.ProvisionContentKeyRequest
import com.veyq.app.generated.model.ScopeContentKeyResponse
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.content.ScopeContentKey
import com.veyq.app.shared.content.ScopeContentKeyRepository
import com.veyq.app.shared.crypto.CryptoSuiteRegistry
import com.veyq.app.shared.keymgmt.ScopeGrantRepository
import com.veyq.app.shared.keymgmt.ScopeRepository
import com.veyq.app.shared.security.SessionService
import com.veyq.app.shared.tenancy.MembershipRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * uc.spaces / content (AD-03) — the stable per-scope content key. A key-holder mints a content key,
 * wraps it to the scope public key and provisions it once; every key-holder unwraps the same key with
 * their scope private key. The server stores ciphertext only. The content key is independent of the
 * scope keypair, so it survives keypair rotation (rotation re-wraps it).
 */
@ApplicationScoped
class ScopeContentKeyHandler(
    private val scopes: ScopeRepository,
    private val grants: ScopeGrantRepository,
    private val contentKeys: ScopeContentKeyRepository,
    private val memberships: MembershipRepository,
    private val sessions: SessionService,
    private val suites: CryptoSuiteRegistry,
) {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()

    @Transactional
    fun getScopeContentKey(auth: String?, scopeId: String): ScopeContentKeyResponse {
        requireKeyHolder(scopeId, auth)
        val ck = contentKeys.findByScopeId(scopeId) ?: throw notFound("Content key")
        return ScopeContentKeyResponse(wrappedContentKey = Blob(suiteId = ck.suiteId, bytes = enc.encodeToString(ck.wrappedKey)))
    }

    @Transactional
    fun provisionScopeContentKey(auth: String?, scopeId: String, req: ProvisionContentKeyRequest): ScopeContentKeyResponse {
        requireKeyHolder(scopeId, auth)
        if (!suites.isKnown(req.wrappedContentKey.suiteId)) throw ApiException(400, "UNKNOWN_SUITE", "Unknown crypto suite")
        if (contentKeys.findByScopeId(scopeId) != null) {
            throw ApiException(409, "CONTENT_KEY_EXISTS", "The scope already has a content key")
        }
        val ck = ScopeContentKey().apply {
            id = UUID.randomUUID().toString(); this.scopeId = scopeId
            suiteId = req.wrappedContentKey.suiteId; wrappedKey = dec.decode(req.wrappedContentKey.bytes); createdAt = Instant.now()
        }
        contentKeys.persist(ck)
        return ScopeContentKeyResponse(wrappedContentKey = Blob(suiteId = ck.suiteId, bytes = enc.encodeToString(ck.wrappedKey)))
    }

    private fun requireKeyHolder(scopeId: String, auth: String?) {
        val userId = sessions.resolve(auth) ?: throw ApiException(401, "UNAUTHORIZED", "Missing or invalid session")
        val scope = scopes.findById(scopeId) ?: throw notFound("Scope")
        grants.find(scope.id, userId) ?: throw ApiException(403, "NOT_A_KEY_HOLDER", "You do not hold this scope")
        // defense in depth for tenant scopes: a held grant must also be backed by active membership, so a
        // missed grant cleanup can't silently become an IDOR (personal scopes have no membership).
        val tenantId = scope.tenantId
        if (tenantId != null && memberships.find(tenantId, userId)?.status != "ACTIVE") {
            throw ApiException(403, "NOT_A_MEMBER", "You are not an active member of this tenant")
        }
    }

    private fun notFound(what: String) = ApiException(404, "NOT_FOUND", "$what not found")
}
