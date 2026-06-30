package com.veyq.api.auth.passkey

import com.veyq.api.domain.PasskeyCredentialRepo
import io.quarkus.security.webauthn.WebAuthnCredentialRecord
import io.quarkus.security.webauthn.WebAuthnUserProvider
import io.smallrye.common.annotation.Blocking
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional

@Blocking
@ApplicationScoped
class VeyqWebAuthnUserProvider @Inject constructor(
    private val passkeyCredentialRepo: PasskeyCredentialRepo,
) : WebAuthnUserProvider {
    @Transactional
    override fun findByUsername(username: String): Uni<List<WebAuthnCredentialRecord>> =
        Uni.createFrom().item(
            username.toLongOrNull()
                ?.let(passkeyCredentialRepo::listByUserId)
                ?.map { it.toCredentialRecord() }
                ?: emptyList(),
        )

    @Transactional
    override fun findByCredentialId(credentialId: String): Uni<WebAuthnCredentialRecord> {
        val credential = passkeyCredentialRepo.findByCredentialId(credentialId)
            ?: return Uni.createFrom().failure(IllegalArgumentException("Unknown passkey credential"))
        return Uni.createFrom().item(credential.toCredentialRecord())
    }

    override fun store(credentialRecord: WebAuthnCredentialRecord): Uni<Void> =
        Uni.createFrom().voidItem()

    @Transactional
    override fun update(credentialId: String, counter: Long): Uni<Void> {
        passkeyCredentialRepo.findByCredentialId(credentialId)?.counter = counter
        return Uni.createFrom().voidItem()
    }
}
