package com.veyq.app.features.content.scopecontent

import com.veyq.app.generated.model.Blob
import com.veyq.app.generated.model.ProvisionContentKeyRequest
import com.veyq.app.generated.model.ScopeContentKeyResponse
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.content.ScopeContentKey
import com.veyq.app.shared.content.ScopeContentKeyRepository
import com.veyq.app.shared.crypto.CryptoSuiteRegistry
import com.veyq.app.shared.security.ScopeAccessGuard
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant
import java.util.Base64
import java.util.UUID
import com.veyq.app.platform.error.notFound

/**
 * uc.spaces / content (AD-03) — the stable per-scope content key. A key-holder mints a content key,
 * wraps it to the scope public key and provisions it once; every key-holder unwraps the same key with
 * their scope private key. The server stores ciphertext only. The content key is independent of the
 * scope keypair, so it survives keypair rotation (rotation re-wraps it).
 */
@ApplicationScoped
class ScopeContentKeyHandler(
    private val contentKeys: ScopeContentKeyRepository,
    private val guard: ScopeAccessGuard,
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
        guard.requireKeyHolder(auth, scopeId)
    }

}
