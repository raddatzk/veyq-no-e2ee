package com.veyq.app.features.admin.setup

import com.veyq.app.generated.api.SetupApi
import com.veyq.app.generated.model.Capability
import com.veyq.app.generated.model.CreateServerOwnerRequest
import com.veyq.app.generated.model.ErrorResponse
import com.veyq.app.generated.model.SetupResult
import com.veyq.app.generated.model.SetupStatus
import com.veyq.app.generated.model.Version
import jakarta.annotation.security.PermitAll
import jakarta.ws.rs.core.Response
import java.util.UUID

/**
 * HTTP adapter for first-run setup. Implements the generated [SetupApi]; thin — it maps to/from the
 * generated models and delegates to [SetupHandler] (ADR 0001). Field validation is enforced by @Valid
 * on the generated request model before this runs. Public (`@PermitAll`, cr-0012): setup runs pre-auth,
 * before any account exists.
 */
@PermitAll
class SetupResource(private val handler: SetupHandler) : SetupApi {

    override fun getSetupStatus(): Response {
        val state = handler.state()
        return Response.ok(
            SetupStatus(
                setupRequired = state.setupRequired,
                suites = state.suites,
                instanceSalt = state.instanceSalt,
                version = Version(major = state.versionMajor, build = state.versionBuild),
                capabilities = state.capabilities.map { Capability(id = it) },
            ),
        ).build()
    }

    override fun createServerOwner(createServerOwnerRequest: CreateServerOwnerRequest): Response =
        try {
            val userId = handler.createOwner(createServerOwnerRequest.toRegistration())
            Response.status(Response.Status.CREATED)
                .entity(SetupResult(userId = UUID.fromString(userId)))
                .build()
        } catch (e: SetupAlreadyComplete) {
            Response.status(Response.Status.CONFLICT)
                .entity(ErrorResponse(code = "setup_already_complete"))
                .build()
        }
}

private fun CreateServerOwnerRequest.toRegistration() = OwnerRegistration(
    email = email.lowercase(),
    suiteId = suiteId.value,
    identityPublicKey = identityPublicKey,
    wrappedIdentityPrivateKey = wrappedIdentityPrivateKey,
    authPublicKey = authPublicKey,
    recoveryMasterKeyTag = recoveryMasterKeyTag,
    wraps = masterKeyWraps.map { WrapInput(method = it.method.value, wrappedKey = it.wrappedKey, kdfParams = it.kdfParams) },
)
