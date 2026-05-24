package com.veyq.api.auth.passkey

import com.veyq.api.auth.AuthService
import com.veyq.api.auth.BadRequestException
import com.veyq.api.auth.ConflictException
import com.veyq.api.auth.NotFoundException
import com.veyq.api.auth.UnauthorizedException
import com.veyq.api.auth.LoginContext
import com.veyq.api.auth.filterchain.RequestContext
import com.veyq.api.domain.PasskeyCredential
import com.veyq.api.domain.PasskeyCredentialRepo
import com.veyq.api.domain.User
import com.veyq.api.domain.UserRepo
import com.veyq.api.generated.model.CreatePasskeyRequest
import com.veyq.api.generated.model.PasskeyAssertionResponse
import com.veyq.api.generated.model.PasskeyAttestationResponse
import com.veyq.api.generated.model.PasskeyLoginRequest
import com.veyq.api.generated.model.PasskeyRegistrationRequest
import com.veyq.api.generated.model.PasskeySummary
import com.veyq.api.generated.model.PasskeysSummary
import io.quarkus.security.webauthn.WebAuthnCredentialRecord
import io.quarkus.security.webauthn.WebAuthnSecurity
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import java.time.Instant

@ApplicationScoped
class PasskeyService @Inject constructor(
    private val userRepo: UserRepo,
    private val passkeyCredentialRepo: PasskeyCredentialRepo,
    private val requestContext: RequestContext,
    private val authService: AuthService,
    private val webAuthnSecurity: WebAuthnSecurity,
) {
    fun loginOptions(routingContext: RoutingContext): String {
        val options = webAuthnSecurity.getLoginChallenge(null, routingContext).await().indefinitely()
        return webAuthnSecurity.toJsonString(options)
    }

    @Transactional
    fun login(request: PasskeyLoginRequest, routingContext: RoutingContext): LoginContext {
        val credentialRecord = try {
            webAuthnSecurity.login(request.toJsonObject(), routingContext).await().indefinitely()
        } catch (caught: Exception) {
            throw UnauthorizedException("Invalid passkey")
        }
        val credential = passkeyCredentialRepo.findByCredentialId(credentialRecord.credentialID)
            ?: throw UnauthorizedException("Invalid passkey")
        val user = userRepo.findById(credential.user.id)
            ?: throw UnauthorizedException("Invalid passkey")

        credential.counter = credentialRecord.counter
        credential.lastUsedAt = Instant.now()

        return authService.loginUser(user)
    }

    fun registrationOptions(routingContext: RoutingContext): String {
        val user = currentUser()
        return registrationOptionsForUser(user, routingContext)
    }

    fun registrationOptionsForUser(user: User, routingContext: RoutingContext): String {
        val options = webAuthnSecurity
            .getRegisterChallenge(user.id.toString(), user.displayName, routingContext)
            .await()
            .indefinitely()
        return webAuthnSecurity.toJsonString(options)
    }

    fun listMyPasskeys(): PasskeysSummary =
        PasskeysSummary(passkeyCredentialRepo.listByUser(currentUser()).map { it.toSummary() })

    @Transactional
    fun createMyPasskey(request: CreatePasskeyRequest, routingContext: RoutingContext): PasskeySummary {
        val user = currentManagedUser()
        val displayName = request.displayName.trim().ifBlank { "Passkey" }
        return createPasskeyForUser(user, displayName, request.credential, routingContext)
    }

    @Transactional
    fun createPasskeyForUser(
        user: User,
        displayName: String,
        credentialRequest: PasskeyRegistrationRequest,
        routingContext: RoutingContext,
    ): PasskeySummary {
        val credentialRecord = try {
            webAuthnSecurity
                .register(user.id.toString(), credentialRequest.toJsonObject(), routingContext)
                .await()
                .indefinitely()
        } catch (_: Exception) {
            throw BadRequestException("Invalid passkey registration")
        }

        if (passkeyCredentialRepo.findByCredentialId(credentialRecord.credentialID) != null) {
            throw ConflictException("Passkey already exists")
        }

        val credential = PasskeyCredential().also {
            val persisted = credentialRecord.requiredPersistedData
            it.user = user
            it.credentialId = persisted.credentialId()
            it.publicKey = persisted.publicKey()
            it.publicKeyAlgorithm = persisted.publicKeyAlgorithm()
            it.counter = persisted.counter()
            it.aaguid = persisted.aaguid().toString()
            it.transports = credentialRecord.transports?.joinToString(",") { transport -> transport.value }
            it.displayName = displayName.trim().ifBlank { "Passkey" }
            it.createdAt = Instant.now()
            it.persistAndFlush()
        }

        return credential.toSummary()
    }

    @Transactional
    fun deleteMyPasskey(passkeyId: Long) {
        val credential = passkeyCredentialRepo.findByIdAndUser(passkeyId, currentManagedUser())
            ?: throw NotFoundException("Passkey not found")
        credential.delete()
    }

    private fun currentUser(): User =
        requestContext.user ?: throw UnauthorizedException("Authentication required")

    private fun currentManagedUser(): User =
        userRepo.findById(currentUser().id) ?: throw UnauthorizedException("Invalid user")
}

fun PasskeyCredential.toCredentialRecord(): WebAuthnCredentialRecord =
    WebAuthnCredentialRecord.fromRequiredPersistedData(
        WebAuthnCredentialRecord.RequiredPersistedData(
            user.id.toString(),
            credentialId,
            java.util.UUID.fromString(aaguid),
            publicKey,
            publicKeyAlgorithm,
            counter,
        ),
    )

fun PasskeyCredential.toSummary(): PasskeySummary =
    PasskeySummary(
        id,
        displayName,
        createdAt.toString(),
        lastUsedAt?.toString(),
    )

private fun PasskeyLoginRequest.toJsonObject(): JsonObject =
    JsonObject()
        .put("id", id)
        .put("rawId", rawId)
        .put("type", type)
        .put("response", response.toJsonObject())

private fun PasskeyAssertionResponse.toJsonObject(): JsonObject =
    JsonObject()
        .put("clientDataJSON", clientDataJSON)
        .put("authenticatorData", authenticatorData)
        .put("signature", signature)
        .also { json ->
            userHandle?.let { json.put("userHandle", it) }
        }

private fun PasskeyRegistrationRequest.toJsonObject(): JsonObject =
    JsonObject()
        .put("id", id)
        .put("rawId", rawId)
        .put("type", type)
        .put("response", response.toJsonObject())

private fun PasskeyAttestationResponse.toJsonObject(): JsonObject =
    JsonObject()
        .put("clientDataJSON", clientDataJSON)
        .put("attestationObject", attestationObject)
