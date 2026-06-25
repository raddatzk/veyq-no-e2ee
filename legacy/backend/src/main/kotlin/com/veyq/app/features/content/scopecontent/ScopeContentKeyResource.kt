package com.veyq.app.features.content.scopecontent

import com.veyq.app.generated.api.ScopeContentKeyApi
import com.veyq.app.generated.model.ProvisionContentKeyRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response

/** Thin REST entry implementing the generated ScopeContentKeyApi; delegates to the handler. */
@ApplicationScoped
class ScopeContentKeyResource(private val handler: ScopeContentKeyHandler) : ScopeContentKeyApi {

    override fun getScopeContentKey(authorization: String, scopeId: String): Response =
        Response.ok(handler.getScopeContentKey(authorization, scopeId)).build()

    override fun provisionScopeContentKey(authorization: String, scopeId: String, provisionContentKeyRequest: ProvisionContentKeyRequest): Response =
        Response.ok(handler.provisionScopeContentKey(authorization, scopeId, provisionContentKeyRequest)).build()
}
