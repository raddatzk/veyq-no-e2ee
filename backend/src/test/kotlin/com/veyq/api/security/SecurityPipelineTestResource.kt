package com.veyq.api.security

import com.veyq.api.auth.filterchain.GroupOwnerRequired
import com.veyq.api.auth.filterchain.TenantOwnerRequired
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

@Path("/api/{tenantName}/security-test")
@ApplicationScoped
@Produces(MediaType.TEXT_PLAIN)
class SecurityPipelineTestResource {
    @GET
    @Path("/member")
    fun member(@PathParam("tenantName") tenantName: String): String =
        "member:$tenantName"

    @GET
    @Path("/tenant-owner")
    @TenantOwnerRequired
    fun tenantOwner(@PathParam("tenantName") tenantName: String): String =
        "tenant-owner:$tenantName"

    @GET
    @Path("/groups/{groupName}/owner")
    @GroupOwnerRequired
    fun groupOwner(
        @PathParam("tenantName") tenantName: String,
        @PathParam("groupName") groupName: String,
    ): String =
        "group-owner:$tenantName:$groupName"
}
