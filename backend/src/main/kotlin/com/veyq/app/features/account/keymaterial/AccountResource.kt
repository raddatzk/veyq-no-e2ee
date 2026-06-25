package com.veyq.app.features.account.keymaterial

import com.veyq.app.features.account.login.SessionService
import com.veyq.app.generated.api.AccountApi
import com.veyq.app.generated.model.AccountKeyMaterial
import com.veyq.app.generated.model.ErrorResponse
import com.veyq.app.generated.model.MasterKeyWrap
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.Response

/**
 * HTTP adapter serving the authenticated caller's wrapped key material for client-side unlock
 * (uc.account.unlock). Implements the generated [AccountApi]; thin — resolves the caller from the bearer
 * access token via [SessionService] and maps the stored ciphertext to the generated models. The server
 * performs no content crypto: every field served here is opaque ciphertext or the public suite id.
 *
 * The material is keyed off the access token, never an id in the request, so it is no cross-account read.
 * A missing, malformed, expired, or revoked token is a single generic 401 (auth ≠ unlock — a stolen token
 * yields only ciphertext, useless without the password and Secret Key).
 */
class AccountResource(
    private val sessions: SessionService,
    private val store: KeyMaterialStore,
) : AccountApi {

    @Context
    lateinit var headers: HttpHeaders

    override fun getAccountKeyMaterial(): Response {
        val token = headers.getHeaderString("Authorization")
            ?.removePrefix("Bearer ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val userId = token?.let { sessions.resolveUserId(it) } ?: return unauthorized()
        val material = store.find(userId) ?: return unauthorized()

        return Response.ok(
            AccountKeyMaterial(
                suiteId = AccountKeyMaterial.SuiteId.valueOf(material.suiteId),
                wraps = material.wraps.map {
                    MasterKeyWrap(
                        method = MasterKeyWrap.Method.valueOf(it.method),
                        wrappedKey = it.wrappedKey,
                        kdfParams = it.kdfParams,
                    )
                },
                wrappedIdentityPrivateKey = material.wrappedIdentityPrivateKey,
            ),
        ).build()
    }

    private fun unauthorized(): Response =
        Response.status(Response.Status.UNAUTHORIZED)
            .entity(ErrorResponse(code = "authentication_failed"))
            .build()
}
