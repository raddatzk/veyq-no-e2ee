package com.veyq.app.features.admin.setup

import com.veyq.app.shared.blobstore.BlobStorePreflight
import com.veyq.app.shared.instance.InstanceStore
import jakarta.enterprise.context.ApplicationScoped

/** Current setup state for the status endpoint. */
data class SetupState(
    val setupRequired: Boolean,
    val suites: List<String>,
    val instanceSalt: String,
    val directTransport: Boolean,
)

/** The owner registration payload, mapped from the API request at the resource boundary. */
data class OwnerRegistration(
    val email: String,
    val suiteId: String,
    val identityPublicKey: String,
    val wrappedIdentityPrivateKey: String,
    val authPublicKey: String,
    val recoveryMasterKeyTag: String?,
    val wraps: List<WrapInput>,
)

data class WrapInput(val method: String, val wrappedKey: String, val kdfParams: String? = null)

/** Setup is already complete; a second owner cannot be bootstrapped. */
class SetupAlreadyComplete : RuntimeException("setup_already_complete")

/**
 * First-run setup use case: report status and create the first Server Owner.
 * Operator-blind — stores only ciphertext, public keys and metadata; performs no content crypto and
 * does not verify the password (there is none). See ADR 0003 and the setup-server use-case.
 */
@ApplicationScoped
class SetupHandler(
    private val store: SetupStore,
    private val instanceStore: InstanceStore,
    private val blobPreflight: BlobStorePreflight,
) {
    private val supportedSuites = listOf("v1")

    fun state(): SetupState = SetupState(
        setupRequired = !store.isSetupComplete(),
        suites = supportedSuites,
        instanceSalt = instanceStore.instanceSalt(),
        directTransport = blobPreflight.directTransport(),
    )

    fun createOwner(registration: OwnerRegistration): String {
        // Field validation (non-blank, size, base64) is enforced at the boundary by @Valid on the
        // generated request model; here we only guard the single-owner invariant.
        if (store.isSetupComplete()) throw SetupAlreadyComplete()
        return store.persistOwner(registration)
    }
}
