package com.veyq.app.features.keys.rewrapscopegrant

import com.veyq.app.generated.api.KeysApi
import com.veyq.app.generated.model.RewrapMyScopeGrantRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response

/** Thin REST entry implementing the generated KeysApi; delegates to the handler. */
@ApplicationScoped
class ScopeGrantRewrapResource(private val handler: ScopeGrantRewrapHandler) : KeysApi {
    override fun rewrapMyScopeGrant(
        authorization: String,
        scopeId: String,
        rewrapMyScopeGrantRequest: RewrapMyScopeGrantRequest,
    ): Response =
        Response.ok(handler.rewrapMyScopeGrant(authorization, scopeId, rewrapMyScopeGrantRequest)).build()
}
