package com.veyq.api.settings

import com.veyq.api.generated.model.ClientCapabilitiesResponse
import com.veyq.api.generated.model.ClientCompatibilitySummary
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

@ApplicationScoped
@Path("/api/client-capabilities")
@Produces(MediaType.APPLICATION_JSON)
class ClientCapabilitiesResource(
    private val capabilityRegistry: CapabilityRegistry,
) {
    @GET
    fun getClientCapabilities(): ClientCapabilitiesResponse =
        ClientCapabilitiesResponse(
            capabilities = capabilityRegistry.listFor(),
            compatibility = ClientCompatibilitySummary(serverMajor = 1),
        )
}
