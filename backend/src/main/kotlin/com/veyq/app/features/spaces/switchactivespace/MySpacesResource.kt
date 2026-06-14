package com.veyq.app.features.spaces.switchactivespace

import com.veyq.app.generated.api.SpaceRosterApi
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response

/** Thin REST entry implementing the generated SpaceRosterApi; delegates to the handler. */
@ApplicationScoped
class MySpacesResource(private val handler: MySpacesHandler) : SpaceRosterApi {

    override fun listMySpaces(authorization: String): Response =
        Response.ok(handler.listMySpaces(authorization)).build()
}
