package com.veyq.app.features.keys.rotateidentity

import com.veyq.app.generated.api.MyGrantsApi
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response

/** Thin REST entry implementing the generated MyGrantsApi; delegates to the handler. */
@ApplicationScoped
class MyGrantsResource(private val handler: MyGrantsHandler) : MyGrantsApi {

    override fun listMyGrants(authorization: String): Response =
        Response.ok(handler.listMyGrants(authorization)).build()
}
