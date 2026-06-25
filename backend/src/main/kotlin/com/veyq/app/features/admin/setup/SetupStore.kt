package com.veyq.app.features.admin.setup

import com.veyq.app.shared.identity.MasterKeyWrapEntity
import com.veyq.app.shared.identity.VeyqUser
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant
import java.util.UUID

/** Persistence for first-run setup. Only knows how to read the setup lock and store the owner. */
@ApplicationScoped
class SetupStore {

    fun isSetupComplete(): Boolean = ServerSetup.count() > 0

    @Transactional
    fun persistOwner(registration: OwnerRegistration): String {
        val userId = UUID.randomUUID().toString()

        VeyqUser().apply {
            id = userId
            displayName = registration.displayName
            serverRole = "SERVER_OWNER"
            suiteId = registration.suiteId
            identityPublicKey = registration.identityPublicKey
            wrappedIdentityPrivateKey = registration.wrappedIdentityPrivateKey
            authPublicKey = registration.authPublicKey
            recoveryMasterKeyTag = registration.recoveryMasterKeyTag
            createdAt = Instant.now()
        }.persist()

        registration.wraps.forEach { wrap ->
            MasterKeyWrapEntity().apply {
                id = UUID.randomUUID().toString()
                this.userId = userId
                method = wrap.method
                wrappedKey = wrap.wrappedKey
            }.persist()
        }

        ServerSetup().apply {
            id = ServerSetup.SINGLETON_ID
            instanceName = registration.instanceName
            completedAt = Instant.now()
        }.persist()

        return userId
    }
}
