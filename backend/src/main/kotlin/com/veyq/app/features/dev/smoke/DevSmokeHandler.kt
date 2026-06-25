package com.veyq.app.features.dev.smoke

import com.veyq.app.generated.model.AuthVerifier
import com.veyq.app.generated.model.CreateServerOwnerRequest
import com.veyq.app.generated.model.UserSummary
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.config.DevToolsConfig
import com.veyq.app.shared.crypto.Argon2Verifier
import com.veyq.app.shared.identity.AccountFactory
import com.veyq.app.shared.identity.NewAccount
import com.veyq.app.shared.identity.ServerRole
import com.veyq.app.shared.identity.UserRepository
import com.veyq.app.shared.identity.toUserSummary
import io.quarkus.runtime.LaunchMode
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant

/**
 * Dev/test-only smoke bootstrap. The posted material is still client-generated E2EE account material; this
 * handler only routes it through the same blind AccountFactory used by setup/invitation registration.
 */
@ApplicationScoped
class DevSmokeHandler(
    private val config: DevToolsConfig,
    private val accounts: AccountFactory,
    private val users: UserRepository,
    private val argon2: Argon2Verifier,
) {
    @Transactional
    fun createAccount(req: CreateServerOwnerRequest): UserSummary {
        val mode = LaunchMode.current()
        if (!config.enabled() || (mode != LaunchMode.DEVELOPMENT && mode != LaunchMode.TEST)) {
            throw ApiException(404, "NOT_FOUND", "Resource not found")
        }
        if (users.findByEmail(req.email) != null) {
            throw ApiException(409, "EMAIL_TAKEN", "An account with that email already exists")
        }

        val user = accounts.createAccount(
            NewAccount(
                email = req.email,
                displayName = req.displayName,
                serverRole = ServerRole.ServerMember,
                identityPublicKey = req.identityPublicKey,
                wrappedIdentityPrivateKey = req.wrappedIdentityPrivateKey,
                masterKeyWraps = req.masterKeyWraps,
                recoveryMasterKeyTag = req.recoveryMasterKeyTag,
                authVerifier = AuthVerifier(
                    kind = AuthVerifier.Kind.ARGON2_PROOF,
                    suiteId = "v1",
                    verifier = argon2.createPasswordVerifier(req.password),
                ),
                personalScopePublicKey = req.personalScopePublicKey,
                wrappedPersonalScopePrivateKey = req.wrappedPersonalScopePrivateKey,
            ),
            Instant.now(),
        )
        return user.toUserSummary()
    }
}
