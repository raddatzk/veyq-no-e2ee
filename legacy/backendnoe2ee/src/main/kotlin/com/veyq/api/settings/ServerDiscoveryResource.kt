package com.veyq.api.settings

import com.veyq.api.generated.model.WellKnownVeyqResponse
import io.quarkus.runtime.LaunchMode
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.UriInfo
import java.net.URI

@ApplicationScoped
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
class ServerDiscoveryResource(
    private val veyqConfig: VeyqConfig,
) {
    @Context
    lateinit var uriInfo: UriInfo

    @GET
    @Path(".well-known/veyq")
    fun getWellKnownVeyq(): WellKnownVeyqResponse {
        val base = publicBaseUri()
        val webBase = publicWebBaseUri(base)
        return WellKnownVeyqResponse(
            product = WellKnownVeyqResponse.Product.veyq,
            apiBaseUrl = base.resolve("/api"),
            clientCapabilitiesUrl = base.resolve("/api/client-capabilities"),
            mobileLoginUrl = webBase.resolve("login?handoff=mobile"),
            mobileCallbackScheme = "veyq",
        )
    }

    private fun publicBaseUri(): URI =
        uriInfo.baseUriBuilder
            .replacePath(null)
            .replaceQuery(null)
            .fragment(null)
            .build()

    private fun publicWebBaseUri(fallback: URI): URI {
        val configured = veyqConfig.publicWebBaseUrl.orElse("").trim()
        val base = if (configured.isBlank() || LaunchMode.current() != LaunchMode.DEVELOPMENT) {
            fallback
        } else {
            URI.create(configured)
        }
        return if (base.toString().endsWith("/")) base else URI.create("$base/")
    }
}
