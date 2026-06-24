package com.veyq.app.features.spaces.switchactivespace

import com.veyq.app.generated.api.SpacesApi
import com.veyq.app.shared.tenancy.CurrentTenantResolver
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response

/** Thin REST entry implementing the generated SpacesApi; delegates to the handler. */
@ApplicationScoped
class MySpacesResource(
    private val handler: MySpacesHandler,
    private val currentTenant: CurrentTenantResolver,
) : SpacesApi {

    override fun listSpaces(authorization: String): Response =
        Response.ok(handler.listSpaces(authorization, currentTenant.currentTenantId())).build()
}
