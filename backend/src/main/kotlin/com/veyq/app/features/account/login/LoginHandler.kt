package com.veyq.app.features.account.login

import com.veyq.app.generated.model.Blob
import com.veyq.app.generated.model.IdentityPublicKey
import com.veyq.app.generated.model.KeyBag
import com.veyq.app.generated.model.KemPublicKey
import com.veyq.app.generated.model.LoginRequest
import com.veyq.app.generated.model.LoginResponse
import com.veyq.app.generated.model.MasterKeyWrap
import com.veyq.app.generated.model.PersonalScopeGrant
import com.veyq.app.generated.model.UserSummary
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.crypto.Argon2Verifier
import com.veyq.app.shared.identity.User
import com.veyq.app.shared.identity.UserRepository
import com.veyq.app.shared.keymgmt.AuthVerifierRepository
import com.veyq.app.shared.keymgmt.IdentityKeyRepository
import com.veyq.app.shared.keymgmt.KeyBlob
import com.veyq.app.shared.keymgmt.KeyBlobRepository
import com.veyq.app.shared.keymgmt.ScopeGrantRepository
import com.veyq.app.shared.keymgmt.ScopeRepository
import com.veyq.app.shared.security.SessionService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.util.Base64
import com.veyq.app.generated.model.ServerRole as ApiServerRole

/**
 * uc.account.login — authenticate, issue a session, and serve the wrapped key bag.
 * The server verifies a password proof (Argon2id) and returns ciphertext only; unlock is client-side.
 */
@ApplicationScoped
class LoginHandler(
    private val users: UserRepository,
    private val identityKeys: IdentityKeyRepository,
    private val keyBlobs: KeyBlobRepository,
    private val authVerifiers: AuthVerifierRepository,
    private val scopes: ScopeRepository,
    private val grants: ScopeGrantRepository,
    private val argon2: Argon2Verifier,
    private val sessions: SessionService,
) {
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    @Transactional
    fun login(req: LoginRequest): LoginResponse {
        val user = users.findByEmail(req.email) ?: throw invalidCredentials()
        val verifier = authVerifiers.findByUserId(user.id) ?: throw invalidCredentials()
        if (verifier.kind != "ARGON2_PROOF" || !argon2.verifyPassword(req.password, verifier.verifier)) {
            throw invalidCredentials()
        }

        val identity = identityKeys.findByUserId(user.id)
            ?: throw ApiException(500, "MISSING_KEY_BAG", "No identity key on file for this user")
        val keyBag = KeyBag(
            identityPublicKey = IdentityPublicKey(
                suiteId = identity.suiteId,
                kemPublicKey = b64.encodeToString(identity.kemPublicKey),
                signPublicKey = b64.encodeToString(identity.signPublicKey),
            ),
            wrappedIdentityPrivateKey = Blob(
                suiteId = identity.wrappedPrivateSuiteId,
                bytes = b64.encodeToString(identity.wrappedPrivateKey),
            ),
            masterKeyWraps = keyBlobs.listByUserId(user.id).map(::toWrap),
        )

        return LoginResponse(
            sessionToken = sessions.issue(user.id),
            user = user.toSummary(),
            keyBag = keyBag,
        )
    }

    @Transactional
    fun currentUser(authorization: String?): UserSummary {
        val userId = sessions.resolve(authorization) ?: throw unauthorized()
        val user = users.findById(userId) ?: throw unauthorized()
        return user.toSummary()
    }

    @Transactional
    fun personalScope(authorization: String?): PersonalScopeGrant {
        val userId = sessions.resolve(authorization) ?: throw unauthorized()
        val scope = scopes.findPersonalByOwnerUserId(userId) ?: throw ApiException(404, "NOT_FOUND", "Personal scope not found")
        val grant = grants.find(scope.id, userId) ?: throw ApiException(404, "NOT_FOUND", "Personal scope grant not found")
        return PersonalScopeGrant(
            scopeId = scope.id,
            scopePublicKey = KemPublicKey(
                suiteId = scope.suiteId,
                kemPublicKey = b64.encodeToString(scope.kemPublicKey),
            ),
            wrappedScopePrivateKey = Blob(
                suiteId = grant.suiteId,
                bytes = b64.encodeToString(grant.wrappedPrivateKey),
            ),
        )
    }

    private fun toWrap(blob: KeyBlob): MasterKeyWrap =
        MasterKeyWrap(
            unlockMethod = MasterKeyWrap.UnlockMethod.valueOf(blob.unlockMethod),
            suiteId = blob.suiteId,
            wrappedKey = b64.encodeToString(blob.wrappedKey),
            kdfParams = blob.kdfParams?.let { b64.encodeToString(it) },
            credentialRef = blob.credentialRef,
        )

    private fun User.toSummary(): UserSummary =
        UserSummary(id = id, email = email, displayName = displayName, serverRole = ApiServerRole.decode(serverRole.name))

    private fun invalidCredentials() = ApiException(401, "INVALID_CREDENTIALS", "Invalid email or password")
    private fun unauthorized() = ApiException(401, "UNAUTHORIZED", "Missing or invalid session")
}
