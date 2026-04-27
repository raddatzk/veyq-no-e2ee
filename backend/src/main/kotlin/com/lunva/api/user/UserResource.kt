package com.lunva.api.user

import com.lunva.api.domain.ServerRole
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

data class UserSummary(val userId: Long, val email: String, val displayName: String, val serverRole: ServerRole)

@Path("/api")
@ApplicationScoped
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
class UserResource(
    private val userService: UserService,
) {

    @GET
    @Path("/me")
    fun me(): UserSummary {
        return userService.me()
    }
}
