package com.veyq.app.features.account.keymaterial

import com.veyq.app.generated.api.AccountApi
import com.veyq.app.generated.model.AccountKeyMaterial
import com.veyq.app.generated.model.ErrorResponse
import com.veyq.app.generated.model.MasterKeyWrap
import com.veyq.app.shared.http.bearerToken
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.Response
import org.jboss.logging.Logger

/**
 * HTTP adapter serving the authenticated caller's wrapped key material for client-side unlock
 * (uc.account.unlock). Implements the generated [AccountApi]; thin (ADR 0001) — extracts the bearer token,
 * delegates the resolve + load to [KeyMaterialHandler], and maps the stored ciphertext to the generated
 * models. The server performs no content crypto: every field served here is opaque ciphertext or the public
 * suite id.
 *
 * The material is keyed off the access token, never an id in the request, so it is no cross-account read.
 * A missing, malformed, expired, or revoked token is a single generic 401 (auth ≠ unlock — a stolen token
 * yields only ciphertext, useless without the password and Secret Key).
 */
class AccountResource(private val handler: KeyMaterialHandler) : AccountApi {

    @Context
    lateinit var headers: HttpHeaders

    override fun getAccountKeyMaterial(): Response {
        val material = handler.forAccessToken(bearerToken(headers)) ?: return unauthorized()

        // Map stored strings to the generated enums by their wire VALUE, not Enum.valueOf (which matches by
        // constant NAME and throws on any drift). Unknown wrap methods are skipped (forward-compatible);
        // an unknown suite the server can't serve is a controlled 500, never an uncaught exception.
        val suiteId = AccountKeyMaterial.SuiteId.entries.firstOrNull { it.value == material.suiteId }
            ?: return Response.serverError().entity(ErrorResponse(code = "internal_error")).build()
        val wraps = material.wraps.mapNotNull { wrap ->
            val method = MasterKeyWrap.Method.entries.firstOrNull { it.value == wrap.method }
            if (method == null) {
                // Forward-compatible skip, but never silent: a method the current enum doesn't know is
                // either a newer-client wrap (fine) or data corruption (e.g. a dropped PASSWORD wrap would
                // lock the account out) — surface it so an operator can tell the two apart. The method
                // string is low-sensitivity (a small known set), no ciphertext or secret is logged.
                log.warnf("Skipping master-key wrap with unrecognised method '%s'", wrap.method)
                null
            } else {
                MasterKeyWrap(id = wrap.id, method = method, wrappedKey = wrap.wrappedKey, kdfParams = wrap.kdfParams)
            }
        }

        return Response.ok(
            AccountKeyMaterial(
                suiteId = suiteId,
                wraps = wraps,
                wrappedIdentityPrivateKey = material.wrappedIdentityPrivateKey,
            ),
        ).build()
    }

    private fun unauthorized(): Response =
        Response.status(Response.Status.UNAUTHORIZED)
            .entity(ErrorResponse(code = "authentication_failed"))
            .build()

    private companion object {
        private val log: Logger = Logger.getLogger(AccountResource::class.java)
    }
}
