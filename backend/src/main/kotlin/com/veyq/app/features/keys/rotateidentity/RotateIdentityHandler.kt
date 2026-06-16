package com.veyq.app.features.keys.rotateidentity

import com.veyq.app.generated.model.RotateIdentityRequest
import com.veyq.app.generated.model.UserSummary
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.crypto.CryptoSuiteRegistry
import com.veyq.app.shared.identity.User
import com.veyq.app.shared.identity.UserRepository
import com.veyq.app.shared.keymgmt.IdentityKeyRepository
import com.veyq.app.shared.keymgmt.ScopeGrantRepository
import com.veyq.app.shared.security.SessionService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.util.Base64
import com.veyq.app.generated.model.ServerRole as ApiServerRole
import com.veyq.app.platform.error.unauthorized

/**
 * uc.keys.rotate-identity — swap the account identity keypair and re-wrap every owned grant to it. The
 * master key + unlock wraps are untouched (login still works). The re-wrapped grants must cover exactly
 * the caller's current grants, so rotation neither drops nor invents access. The server stays blind.
 */
@ApplicationScoped
class RotateIdentityHandler(
    private val users: UserRepository,
    private val identityKeys: IdentityKeyRepository,
    private val grants: ScopeGrantRepository,
    private val sessions: SessionService,
    private val suites: CryptoSuiteRegistry,
) {
    private val dec = Base64.getUrlDecoder()

    @Transactional
    fun rotateIdentity(auth: String?, req: RotateIdentityRequest): UserSummary {
        val userId = sessions.resolve(auth) ?: throw unauthorized()
        val user = users.findById(userId) ?: throw unauthorized()
        suites.require(req.identityPublicKey.suiteId)
        suites.require(req.wrappedIdentityPrivateKey.suiteId)
        req.grants.forEach { suites.require(it.wrappedScopePrivateKey.suiteId) }

        // the re-wrapped grants must cover exactly the caller's current grants
        val current = grants.listByUserId(userId).associateBy { it.scopeId }
        val rewrapScopeIds = req.grants.map { it.scopeId }
        if (rewrapScopeIds.size != rewrapScopeIds.toSet().size) {
            throw ApiException(400, "DUPLICATE_GRANT", "Duplicate scope in rotation")
        }
        if (rewrapScopeIds.toSet() != current.keys) {
            throw ApiException(400, "INCOMPLETE_REWRAP", "Rotation must re-wrap exactly the grants you currently hold")
        }

        // swap the identity public key + wrapped private key (the master key is unchanged)
        val identity = identityKeys.findByUserId(userId) ?: throw ApiException(500, "MISSING_IDENTITY", "No identity key on file")
        identity.suiteId = req.identityPublicKey.suiteId
        identity.kemPublicKey = dec.decode(req.identityPublicKey.kemPublicKey)
        identity.signPublicKey = dec.decode(req.identityPublicKey.signPublicKey)
        identity.wrappedPrivateKey = dec.decode(req.wrappedIdentityPrivateKey.bytes)
        identity.wrappedPrivateSuiteId = req.wrappedIdentityPrivateKey.suiteId

        // replace each grant's ciphertext (now wrapped to the new identity)
        req.grants.forEach { rewrap ->
            val grant = current.getValue(rewrap.scopeId)
            grant.suiteId = rewrap.wrappedScopePrivateKey.suiteId
            grant.wrappedPrivateKey = dec.decode(rewrap.wrappedScopePrivateKey.bytes)
        }

        return user.toSummary()
    }

    private fun User.toSummary(): UserSummary =
        UserSummary(id = id, email = email, displayName = displayName, serverRole = ApiServerRole.decode(serverRole.name))


}
