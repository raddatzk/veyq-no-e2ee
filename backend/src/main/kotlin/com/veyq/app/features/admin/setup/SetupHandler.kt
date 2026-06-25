package com.veyq.app.features.admin.setup

import jakarta.enterprise.context.ApplicationScoped

/** Current setup state for the status endpoint. */
data class SetupState(val setupRequired: Boolean, val suites: List<String>)

/** The owner registration payload, mapped from the API request at the resource boundary. */
data class OwnerRegistration(
    val displayName: String,
    val instanceName: String,
    val suiteId: String,
    val identityPublicKey: String,
    val wrappedIdentityPrivateKey: String,
    val authPublicKey: String,
    val recoveryMasterKeyTag: String?,
    val wraps: List<WrapInput>,
)

data class WrapInput(val method: String, val wrappedKey: String)

/** Setup is already complete; a second owner cannot be bootstrapped. */
class SetupAlreadyComplete : RuntimeException("setup_already_complete")

/**
 * First-run setup use case: report status and create the first Server Owner.
 * Operator-blind — stores only ciphertext, public keys and metadata; performs no content crypto and
 * does not verify the password (there is none). See ADR 0003 and the setup-server use-case.
 */
@ApplicationScoped
class SetupHandler(private val store: SetupStore) {
    private val supportedSuites = listOf("v1")

    fun state(): SetupState = SetupState(setupRequired = !store.isSetupComplete(), suites = supportedSuites)

    fun createOwner(registration: OwnerRegistration): String {
        // Field validation (non-blank, size, base64) is enforced at the boundary by @Valid on the
        // generated request model; here we only guard the single-owner invariant.
        if (store.isSetupComplete()) throw SetupAlreadyComplete()
        return store.persistOwner(registration)
    }
}
