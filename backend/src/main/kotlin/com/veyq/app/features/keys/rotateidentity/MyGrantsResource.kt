package com.veyq.app.features.keys.rotateidentity

import com.veyq.app.generated.api.IdentityGrantsApi
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response

/** Thin REST entry implementing the generated IdentityGrantsApi; delegates to the handler. */
@ApplicationScoped
class MyGrantsResource(private val handler: MyGrantsHandler) : IdentityGrantsApi {

    override fun listMyGrants(authorization: String): Response =
        Response.ok(handler.listMyGrants(authorization)).build()
}
