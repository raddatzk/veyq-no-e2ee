package com.veyq.app.features.admin.setupserver

import com.veyq.app.generated.model.CreateServerOwnerRequest
import com.veyq.app.generated.model.SetupStatus
import com.veyq.app.generated.model.UserSummary
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.crypto.CryptoSuiteRegistry
import com.veyq.app.shared.identity.ServerRole
import com.veyq.app.shared.identity.User
import com.veyq.app.shared.identity.UserRepository
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
import com.veyq.app.shared.security.SetupGate
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant
import java.util.Base64
import java.util.UUID
import com.veyq.app.generated.model.ServerRole as ApiServerRole

/** uc.admin.setup-server — cold E2EE registration of the first Server Owner. Server stays blind. */
@ApplicationScoped
class SetupServerHandler(
    private val users: UserRepository,
    private val identityKeys: IdentityKeyRepository,
    private val keyBlobs: KeyBlobRepository,
    private val authVerifiers: AuthVerifierRepository,
    private val scopes: ScopeRepository,
    private val grants: ScopeGrantRepository,
    private val suites: CryptoSuiteRegistry,
    private val setupGate: SetupGate,
    @param:org.eclipse.microprofile.config.inject.ConfigProperty(name = "veyq.app.base-url")
    private val appBaseUrl: String,
) {
    private val b64 = Base64.getUrlDecoder()

    @Transactional
    fun status(): SetupStatus =
        SetupStatus(setupRequired = !setupGate.isComplete(), suites = suites.supported(), appBaseUrl = appBaseUrl)

    @Transactional
    fun createServerOwner(req: CreateServerOwnerRequest): UserSummary {
        if (setupGate.isComplete()) {
            throw ApiException(409, "ALREADY_SETUP", "The server is already set up")
        }
        requireSuite(req.identityPublicKey.suiteId)
        requireSuite(req.wrappedIdentityPrivateKey.suiteId)
        requireSuite(req.authVerifier.suiteId)
        requireSuite(req.personalScopePublicKey.suiteId)
        requireSuite(req.wrappedPersonalScopePrivateKey.suiteId)
        if (req.masterKeyWraps.isEmpty()) {
            throw ApiException(400, "NO_UNLOCK_METHOD", "At least one unlock method is required")
        }
        req.masterKeyWraps.forEach { requireSuite(it.suiteId) }
        // A RECOVERY wrap must carry the master-key commitment, or password reset could never prove Secret-Key
        // possession (and the reset confirm would always be rejected).
        if (req.masterKeyWraps.any { it.unlockMethod.value == "RECOVERY" } && req.recoveryAuthTag.isNullOrBlank()) {
            throw ApiException(400, "MISSING_RECOVERY_AUTH", "A RECOVERY wrap requires recoveryAuthTag")
        }

        val now = Instant.now()
        val user = User().apply {
            id = newId()
            email = req.email
            displayName = req.displayName
            serverRole = ServerRole.ServerOwner
            systemAccount = false
            createdAt = now
        }
        users.persist(user)

        identityKeys.persist(
            IdentityKey().apply {
                id = newId()
                userId = user.id
                suiteId = req.identityPublicKey.suiteId
                kemPublicKey = b64.decode(req.identityPublicKey.kemPublicKey)
                signPublicKey = b64.decode(req.identityPublicKey.signPublicKey)
                wrappedPrivateKey = b64.decode(req.wrappedIdentityPrivateKey.bytes)
                wrappedPrivateSuiteId = req.wrappedIdentityPrivateKey.suiteId
                createdAt = now
            },
        )

        req.masterKeyWraps.forEach { wrap ->
            keyBlobs.persist(
                KeyBlob().apply {
                    id = newId()
                    userId = user.id
                    unlockMethod = wrap.unlockMethod.value
                    suiteId = wrap.suiteId
                    wrappedKey = b64.decode(wrap.wrappedKey)
                    kdfParams = wrap.kdfParams?.let { b64.decode(it) }
                    credentialRef = wrap.credentialRef
                    if (wrap.unlockMethod.value == "RECOVERY") recoveryAuthTag = req.recoveryAuthTag?.let { b64.decode(it) }
                    createdAt = now
                },
            )
        }

        authVerifiers.persist(
            AuthVerifier().apply {
                id = newId()
                userId = user.id
                kind = req.authVerifier.kind.value
                suiteId = req.authVerifier.suiteId
                verifier = b64.decode(req.authVerifier.verifier)
                createdAt = now
            },
        )

        val personalScope = Scope().apply {
            id = newId()
            ownerUserId = user.id
            kind = "PERSONAL_ROOT"
            suiteId = req.personalScopePublicKey.suiteId
            kemPublicKey = b64.decode(req.personalScopePublicKey.kemPublicKey)
            createdAt = now
        }
        scopes.persist(personalScope)
        grants.persist(
            ScopeGrant().apply {
                id = newId()
                scopeId = personalScope.id
                userId = user.id
                suiteId = req.wrappedPersonalScopePrivateKey.suiteId
                wrappedPrivateKey = b64.decode(req.wrappedPersonalScopePrivateKey.bytes)
                createdAt = now
            },
        )

        return UserSummary(
            id = user.id,
            email = user.email,
            displayName = user.displayName,
            serverRole = ApiServerRole.ServerOwner,
        )
    }

    private fun requireSuite(suiteId: String) {
        if (!suites.isKnown(suiteId)) {
            throw ApiException(400, "UNKNOWN_SUITE", "Unknown crypto suite: $suiteId")
        }
    }

    private fun newId(): String = UUID.randomUUID().toString()
}
