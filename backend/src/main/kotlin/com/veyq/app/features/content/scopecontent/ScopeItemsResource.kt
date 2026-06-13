package com.veyq.app.features.content.scopecontent

import com.veyq.app.generated.api.ScopeItemsApi
import com.veyq.app.generated.model.CreateScopeItemRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response

/** Thin REST entry implementing the generated ScopeItemsApi; delegates to the handler. */
@ApplicationScoped
class ScopeItemsResource(private val handler: ScopeItemsHandler) : ScopeItemsApi {

    override fun listScopeItems(authorization: String, scopeId: String): Response =
        Response.ok(handler.listScopeItems(authorization, scopeId)).build()

    override fun createScopeItem(authorization: String, scopeId: String, createScopeItemRequest: CreateScopeItemRequest): Response =
        Response.ok(handler.createScopeItem(authorization, scopeId, createScopeItemRequest)).build()
}
