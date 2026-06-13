package com.veyq.app.features.keys.rewrapscopegrant

import com.veyq.app.generated.model.Blob
import com.veyq.app.generated.model.KemPublicKey
import com.veyq.app.generated.model.RewrapMyScopeGrantRequest
import com.veyq.app.generated.model.ScopeGrantResponse
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.crypto.CryptoSuiteRegistry
import com.veyq.app.shared.keymgmt.ScopeGrantRepository
import com.veyq.app.shared.keymgmt.ScopeRepository
import com.veyq.app.shared.security.SessionService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.util.Base64

/**
 * uc.keys.rewrap-scope-grant — replace only the caller's existing wrapped grant for a scope.
 * The endpoint cannot create access: an existing grant for the authenticated user is required.
 */
@ApplicationScoped
class ScopeGrantRewrapHandler(
    private val scopes: ScopeRepository,
    private val grants: ScopeGrantRepository,
    private val sessions: SessionService,
    private val suites: CryptoSuiteRegistry,
) {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()

    @Transactional
    fun rewrapMyScopeGrant(auth: String?, scopeId: String, req: RewrapMyScopeGrantRequest): ScopeGrantResponse {
        val userId = sessions.resolve(auth) ?: throw unauthorized()
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

    private fun unauthorized() = ApiException(401, "UNAUTHORIZED", "Missing or invalid session")
    private fun notFound(what: String) = ApiException(404, "NOT_FOUND", "$what not found")
}
