package com.lunva.api.user

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

@Path("/api/users")
@ApplicationScoped
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
class UserResource(
    private val userService: UserService,
) {

    data class MeResponse(val userId: Long, val username: String, val displayName: String)

    @GET
    @Path("/me")
    fun me(): MeResponse {
        val user = userService.me()
        return MeResponse(user.id, user.username, user.displayName)
    }
}