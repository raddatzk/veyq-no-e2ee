package com.lunva.api.server

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

data class ServerSummary(
    val name: String,
)

@Path("/api/server")
@ApplicationScoped
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
class ServerResource @Inject constructor(
    private val serverService: ServerService
) {

    @GET
    fun server(): ServerSummary {
        return serverService.server()
    }
}