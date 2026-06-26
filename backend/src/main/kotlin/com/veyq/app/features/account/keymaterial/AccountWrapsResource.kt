package com.veyq.app.features.account.keymaterial

import com.veyq.app.generated.api.AccountWrapsApi
import com.veyq.app.generated.model.AddWrapRequest
import com.veyq.app.generated.model.AddWrapResult
import com.veyq.app.generated.model.ErrorResponse
import com.veyq.app.shared.http.bearerToken
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.Response

/**
 * HTTP adapter for adding/removing the caller's Master Key wraps (uc.account.trust-device). Implements the
 * generated [AccountWrapsApi]; thin (ADR 0001) — extracts the bearer token and delegates to [WrapsHandler].
 * Operator-blind: the server stores only the ciphertext wrap + opaque KDF params, never a key or password.
 * Both endpoints act only on the **caller's own** wraps (resolved from the access token, never an id in the
 * body); the PASSWORD floor can't be removed and delete is idempotent (always 204).
 */
class AccountWrapsResource(private val handler: KeyMaterialHandler) : AccountWrapsApi {

    @Context
    lateinit var headers: HttpHeaders

    override fun addAccountWrap(addWrapRequest: AddWrapRequest): Response =
        when (val outcome = handler.addWrap(
            bearerToken(headers),
            addWrapRequest.method.value,
            addWrapRequest.wrappedKey,
            addWrapRequest.kdfParams,
        )) {
            is AddWrapOutcome.Created ->
                Response.status(Response.Status.CREATED).entity(AddWrapResult(id = outcome.id)).build()
            AddWrapOutcome.Unauthenticated -> unauthorized()
            AddWrapOutcome.LimitReached ->
                Response.status(Response.Status.CONFLICT).entity(ErrorResponse(code = "too_many_devices")).build()
        }

    override fun deleteAccountWrap(id: String): Response {
        if (!handler.deleteWrap(bearerToken(headers), id)) return unauthorized()
        return Response.noContent().build()
    }

    private fun unauthorized(): Response =
        Response.status(Response.Status.UNAUTHORIZED)
            .entity(ErrorResponse(code = "authentication_failed"))
            .build()
}
