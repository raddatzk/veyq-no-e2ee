package com.veyq.app.features.admin.invitemembersroles

import com.veyq.app.generated.api.ScopeRotationApi
import com.veyq.app.generated.model.RotateScopeRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response

/** Thin REST entry implementing the generated ScopeRotationApi; delegates to the handler. */
@ApplicationScoped
class ScopeRotationResource(private val handler: ScopeRotationHandler) : ScopeRotationApi {

    override fun rotateScope(authorization: String, tenantId: String, rotateScopeRequest: RotateScopeRequest): Response =
        Response.ok(handler.rotateScope(authorization, tenantId, rotateScopeRequest)).build()
}
