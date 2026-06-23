package com.veyq.app.features.admin.setupserver

import com.veyq.app.generated.model.CreateServerOwnerRequest
import com.veyq.app.generated.model.SetupStatus
import com.veyq.app.generated.model.UserSummary
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.config.AppConfig
import com.veyq.app.shared.crypto.CryptoSuiteRegistry
import com.veyq.app.shared.identity.AccountFactory
import com.veyq.app.shared.identity.NewAccount
import com.veyq.app.shared.identity.ServerRole
import com.veyq.app.shared.identity.toUserSummary
import com.veyq.app.shared.security.ServerSetupRepository
import com.veyq.app.shared.security.SetupGate
import com.veyq.app.shared.storage.BlobStore
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant

/** uc.admin.setup-server — cold E2EE registration of the first Server Owner. Server stays blind. */
@ApplicationScoped
class SetupServerHandler(
    private val accounts: AccountFactory,
    private val suites: CryptoSuiteRegistry,
    private val setupGate: SetupGate,
    private val serverSetup: ServerSetupRepository,
    private val store: BlobStore,
    app: AppConfig,
) {
    private val appBaseUrl: String = app.baseUrl()
    private val supportUrl: String? = app.supportUrl().orElse(null)
    private val serverVersion: String = app.version()

    @Transactional
    fun status(): SetupStatus =
        SetupStatus(
            setupRequired = !setupGate.isComplete(),
            suites = suites.supported(),
            appBaseUrl = appBaseUrl,
            supportUrl = supportUrl,
            serverVersion = serverVersion,
            // Direct-to-store is the only transport (ADR 0021): advertise whether this store can presign, so a
            // misconfigured store surfaces as an operator hint instead of silently-broken media.
            directTransport = if (store.supportsPresign()) SetupStatus.DirectTransport.ok else SetupStatus.DirectTransport.unavailable,
        )

    @Transactional
    fun createServerOwner(req: CreateServerOwnerRequest): UserSummary {
        // Serialize concurrent setups on the singleton lock row, THEN check — closes the TOCTOU where two
        // racing requests both read "no owner yet" and each create one.
        serverSetup.lockForSetup()
        if (setupGate.isComplete()) {
            throw ApiException(409, "ALREADY_SETUP", "The server is already set up")
        }
        val user = accounts.createAccount(
            NewAccount(
                email = req.email,
                displayName = req.displayName,
                serverRole = ServerRole.ServerOwner,
                identityPublicKey = req.identityPublicKey,
                wrappedIdentityPrivateKey = req.wrappedIdentityPrivateKey,
                masterKeyWraps = req.masterKeyWraps,
                recoveryAuthTag = req.recoveryAuthTag,
                authVerifier = req.authVerifier,
                personalScopePublicKey = req.personalScopePublicKey,
                wrappedPersonalScopePrivateKey = req.wrappedPersonalScopePrivateKey,
            ),
            Instant.now(),
        )
        return user.toUserSummary()
    }
}
