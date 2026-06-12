package com.veyq.api.tenant

import com.veyq.api.generated.model.JoinTenantRegistrationInviteRequest
import com.veyq.api.generated.model.JoinTenantRegistrationInviteWithPasskeyRequest
import com.veyq.api.generated.model.TenantRegistrationInvitePasskeyOptionsRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.io.File

@ApplicationScoped
@Path("/api")
class TenantRegistrationInviteResource @Inject constructor(
    private val tenantRegistrationInviteService: TenantRegistrationInviteService,
) {

    @GET
    @Path("/tenant-registration-invites/{token}")
    @Produces(MediaType.APPLICATION_JSON)
    fun getTenantRegistrationInvite(@PathParam("token") token: String): Response =
        Response.ok(tenantRegistrationInviteService.metadata(token)).build()

    @GET
    @Path("/tenant-registration-invites/{token}/logo")
    @Produces("image/png", "image/jpeg")
    fun getTenantRegistrationInviteLogo(@PathParam("token") token: String): File =
        tenantRegistrationInviteService.logo(token)

    @POST
    @Path("/tenant-registration-invites/{token}/join")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    fun joinTenantRegistrationInvite(
        @PathParam("token") token: String,
        joinTenantRegistrationInviteRequest: JoinTenantRegistrationInviteRequest,
    ): Response {
        val (response, accessCookie, refreshCookie) =
            tenantRegistrationInviteService.join(token, joinTenantRegistrationInviteRequest)
        return Response.ok(response)
            .cookie(accessCookie, refreshCookie)
            .build()
    }

    @POST
    @Path("/tenant-registration-invites/{token}/passkey-options")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    fun createTenantRegistrationInvitePasskeyOptions(
        @PathParam("token") token: String,
        request: TenantRegistrationInvitePasskeyOptionsRequest,
    ): Response =
        Response.ok(tenantRegistrationInviteService.passkeyOptions(token, request)).build()

    @POST
    @Path("/tenant-registration-invites/{token}/join/passkey")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    fun joinTenantRegistrationInviteWithPasskey(
        @PathParam("token") token: String,
        request: JoinTenantRegistrationInviteWithPasskeyRequest,
    ): Response {
        val (response, accessCookie, refreshCookie) =
            tenantRegistrationInviteService.joinWithPasskey(token, request)
        return Response.ok(response)
            .cookie(accessCookie, refreshCookie)
            .build()
    }
}
