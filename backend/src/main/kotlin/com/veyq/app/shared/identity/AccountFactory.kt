package com.veyq.app.shared.identity

import com.veyq.app.generated.model.AuthVerifier as ApiAuthVerifier
import com.veyq.app.generated.model.Blob
import com.veyq.app.generated.model.IdentityPublicKey
import com.veyq.app.generated.model.KemPublicKey
import com.veyq.app.generated.model.MasterKeyWrap
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.crypto.CryptoSuiteRegistry
import com.veyq.app.shared.keymgmt.AuthVerifier
import com.veyq.app.shared.keymgmt.AuthVerifierRepository
import com.veyq.app.shared.keymgmt.IdentityKey
import com.veyq.app.shared.keymgmt.IdentityKeyRepository
import com.veyq.app.shared.keymgmt.KeyBlob
import com.veyq.app.shared.keymgmt.KeyBlobRepository
import com.veyq.app.shared.keymgmt.Scope
import com.veyq.app.shared.keymgmt.ScopeGrant
import com.veyq.app.shared.keymgmt.ScopeGrantRepository
import com.veyq.app.shared.keymgmt.ScopeRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * The client-generated material a fresh cryptographic account is born from — identical between
 * uc.admin.setup-server (the first Server Owner) and uc.account.register-via-invitation (a member).
 * Only the [serverRole] differs; everything else is the same public-key/ciphertext bundle.
 */
data class NewAccount(
    val email: String,
    val displayName: String,
    val serverRole: ServerRole,
    val identityPublicKey: IdentityPublicKey,
    val wrappedIdentityPrivateKey: Blob,
    val masterKeyWraps: List<MasterKeyWrap>,
    val recoveryAuthTag: String?,
    val authVerifier: ApiAuthVerifier,
    val personalScopePublicKey: KemPublicKey,
    val wrappedPersonalScopePrivateKey: Blob,
)

/**
 * The single place a new account's cryptographic core is persisted: identity keypair, master-key unlock
 * wraps, auth verifier, and a self-owned PERSONAL_ROOT scope with its self grant. Both onboarding flows
 * route through here so the "what gets stored for a new account" invariant has one definition. The server
 * stays blind — only public keys, ciphertext and the auth verifier ever land in the DB.
 */
@ApplicationScoped
class AccountFactory(
    private val users: UserRepository,
    private val identityKeys: IdentityKeyRepository,
    private val keyBlobs: KeyBlobRepository,
    private val authVerifiers: AuthVerifierRepository,
    private val scopes: ScopeRepository,
    private val grants: ScopeGrantRepository,
    private val suites: CryptoSuiteRegistry,
) {
    private val dec = Base64.getUrlDecoder()

    /** Validate the account material, persist it, and return the new User. Caller supplies the shared timestamp. */
    @Transactional
    fun createAccount(spec: NewAccount, now: Instant): User {
        if (spec.masterKeyWraps.isEmpty()) {
            throw ApiException(400, "NO_UNLOCK_METHOD", "At least one unlock method is required")
        }
        // A RECOVERY wrap must carry the master-key commitment, or password reset could never prove Secret-Key
        // possession (and the reset confirm would always be rejected).
        if (spec.masterKeyWraps.any { it.unlockMethod.value == "RECOVERY" } && spec.recoveryAuthTag.isNullOrBlank()) {
            throw ApiException(400, "MISSING_RECOVERY_AUTH", "A RECOVERY wrap requires recoveryAuthTag")
        }
        suites.require(spec.identityPublicKey.suiteId)
        suites.require(spec.wrappedIdentityPrivateKey.suiteId)
        suites.require(spec.authVerifier.suiteId)
        spec.masterKeyWraps.forEach { suites.require(it.suiteId) }
        suites.require(spec.personalScopePublicKey.suiteId)
        suites.require(spec.wrappedPersonalScopePrivateKey.suiteId)

        val user = User().apply {
            id = newId(); email = spec.email; displayName = spec.displayName
            serverRole = spec.serverRole; systemAccount = false; createdAt = now
        }
        users.persist(user)

        identityKeys.persist(
            IdentityKey().apply {
                id = newId(); userId = user.id; suiteId = spec.identityPublicKey.suiteId
                kemPublicKey = dec.decode(spec.identityPublicKey.kemPublicKey)
                signPublicKey = dec.decode(spec.identityPublicKey.signPublicKey)
                wrappedPrivateKey = dec.decode(spec.wrappedIdentityPrivateKey.bytes)
                wrappedPrivateSuiteId = spec.wrappedIdentityPrivateKey.suiteId; createdAt = now
            },
        )

        spec.masterKeyWraps.forEach { wrap ->
            keyBlobs.persist(
                KeyBlob().apply {
                    id = newId(); userId = user.id; unlockMethod = wrap.unlockMethod.value
                    suiteId = wrap.suiteId; wrappedKey = dec.decode(wrap.wrappedKey)
                    kdfParams = wrap.kdfParams?.let { dec.decode(it) }; credentialRef = wrap.credentialRef; createdAt = now
                    if (wrap.unlockMethod.value == "RECOVERY") recoveryAuthTag = spec.recoveryAuthTag?.let { dec.decode(it) }
                },
            )
        }

        authVerifiers.persist(
            AuthVerifier().apply {
                id = newId(); userId = user.id; kind = spec.authVerifier.kind.value
                suiteId = spec.authVerifier.suiteId; verifier = dec.decode(spec.authVerifier.verifier); createdAt = now
            },
        )

        val personalScope = Scope().apply {
            id = newId(); ownerUserId = user.id; kind = "PERSONAL_ROOT"
            suiteId = spec.personalScopePublicKey.suiteId
            kemPublicKey = dec.decode(spec.personalScopePublicKey.kemPublicKey); createdAt = now
        }
        scopes.persist(personalScope)
        grants.persist(
            ScopeGrant().apply {
                id = newId(); scopeId = personalScope.id; userId = user.id
                suiteId = spec.wrappedPersonalScopePrivateKey.suiteId
                wrappedPrivateKey = dec.decode(spec.wrappedPersonalScopePrivateKey.bytes); createdAt = now
            },
        )
        return user
    }


    private fun newId(): String = UUID.randomUUID().toString()
}
