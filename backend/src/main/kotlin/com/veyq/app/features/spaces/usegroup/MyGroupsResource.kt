package com.veyq.app.features.spaces.usegroup

import com.veyq.app.generated.api.SpacesApi
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response

/** Thin REST entry implementing the generated SpacesApi; delegates to the handler. */
@ApplicationScoped
class MyGroupsResource(private val handler: MyGroupsHandler) : SpacesApi {

    override fun listMyGroups(authorization: String): Response =
        Response.ok(handler.listMyGroups(authorization)).build()
}
