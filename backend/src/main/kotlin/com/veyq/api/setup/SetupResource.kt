package com.veyq.api.setup

import com.veyq.api.generated.api.SetupApi
import com.veyq.api.generated.model.AdminPasskeySetupOptionsRequest
import com.veyq.api.generated.model.AdminPasskeySetupRequest
import com.veyq.api.generated.model.AdminSetupRequest
import io.vertx.ext.web.RoutingContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

@ApplicationScoped
class SetupResource @Inject constructor(
    private val setupService: SetupService,
    private val routingContext: RoutingContext,
) : SetupApi {

    override fun status(): Response =
        Response.ok(setupService.status()).build()

    override fun createAdmin(request: AdminSetupRequest): Response =
        Response.ok(setupService.createAdmin(request)).build()

    override fun createAdminPasskeyOptions(request: AdminPasskeySetupOptionsRequest): Response =
        Response.ok(
            setupService.createAdminPasskeyOptions(request, routingContext),
            MediaType.APPLICATION_JSON,
        ).build()

    override fun createAdminWithPasskey(request: AdminPasskeySetupRequest): Response {
        val (response, accessCookie, refreshCookie) = setupService.createAdminWithPasskey(request, routingContext)

        return Response.ok(response)
            .cookie(accessCookie, refreshCookie)
            .build()
    }
}
