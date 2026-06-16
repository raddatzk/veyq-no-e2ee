package com.veyq.app.features.keys.rewrapscopegrant

import com.veyq.app.generated.model.Blob
import com.veyq.app.generated.model.KemPublicKey
import com.veyq.app.generated.model.RewrapMyScopeGrantRequest
import com.veyq.app.generated.model.ScopeGrantResponse
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.crypto.CryptoSuiteRegistry
import com.veyq.app.shared.keymgmt.ScopeGrantRepository
import com.veyq.app.shared.keymgmt.ScopeRepository
import com.veyq.app.shared.security.ScopeAccessGuard
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.util.Base64

/**
 * uc.keys.rewrap-scope-grant — replace only the caller's existing wrapped grant for a scope.
 * The endpoint cannot create access: an existing grant for the authenticated user is required.
 *
 * Returns the wrapped scope private key, so it goes through [ScopeAccessGuard] — a held grant alone is not
 * enough for a tenant scope; the caller must still be an active member (a stale grant must not leak keys).
 */
@ApplicationScoped
class ScopeGrantRewrapHandler(
    private val scopes: ScopeRepository,
    private val grants: ScopeGrantRepository,
    private val guard: ScopeAccessGuard,
    private val suites: CryptoSuiteRegistry,
) {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()

    /** Read the caller's grant for a scope — used to open it by scope id (e.g. a group scope). */
    @Transactional
    fun getMyScopeGrant(auth: String?, scopeId: String): ScopeGrantResponse {
        val userId = guard.requireKeyHolder(auth, scopeId)
        val scope = scopes.findById(scopeId) ?: throw notFound("Scope")
        val grant = grants.find(scope.id, userId) ?: throw notFound("Scope grant")
        return ScopeGrantResponse(
            scopeId = scope.id,
            scopePublicKey = KemPublicKey(suiteId = scope.suiteId, kemPublicKey = enc.encodeToString(scope.kemPublicKey)),
            wrappedScopePrivateKey = Blob(suiteId = grant.suiteId, bytes = enc.encodeToString(grant.wrappedPrivateKey)),
        )
    }

    @Transactional
    fun rewrapMyScopeGrant(auth: String?, scopeId: String, req: RewrapMyScopeGrantRequest): ScopeGrantResponse {
        val userId = guard.requireKeyHolder(auth, scopeId)
        val scope = scopes.findById(scopeId) ?: throw notFound("Scope")
        val grant = grants.find(scope.id, userId) ?: throw notFound("Scope grant")
        requireSuite(req.wrappedScopePrivateKey.suiteId)

        grant.suiteId = req.wrappedScopePrivateKey.suiteId
        grant.wrappedPrivateKey = dec.decode(req.wrappedScopePrivateKey.bytes)

        return ScopeGrantResponse(
            scopeId = scope.id,
            scopePublicKey = KemPublicKey(suiteId = scope.suiteId, kemPublicKey = enc.encodeToString(scope.kemPublicKey)),
            wrappedScopePrivateKey = Blob(suiteId = grant.suiteId, bytes = enc.encodeToString(grant.wrappedPrivateKey)),
        )
    }

    private fun requireSuite(suiteId: String) {
        if (!suites.isKnown(suiteId)) throw ApiException(400, "UNKNOWN_SUITE", "Unknown crypto suite: $suiteId")
    }

    private fun notFound(what: String) = ApiException(404, "NOT_FOUND", "$what not found")
}
