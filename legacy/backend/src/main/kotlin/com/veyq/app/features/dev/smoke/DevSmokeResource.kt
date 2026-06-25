package com.veyq.app.features.dev.smoke

import com.veyq.app.generated.model.CreateServerOwnerRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.validation.Valid
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

@ApplicationScoped
@Path("/api/dev/smoke/accounts")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
class DevSmokeResource(private val handler: DevSmokeHandler) {
    @POST
    fun createAccount(@Valid req: CreateServerOwnerRequest): Response =
        Response.ok(handler.createAccount(req)).build()
}
