package com.lunva.api.setup

import com.lunva.api.usermgmt.UserSummary
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType

data class SetupStatusSummary(
    val setupRequired: Boolean
)

data class AdminSetupRequest(
    @field:NotBlank(message = "password must not be blank")
    @field:Size(min = 8, message = "password must be at least 8 characters")
    val password: String?,

    @field:NotBlank(message = "displayName must not be blank")
    val displayName: String?,

    @field:NotBlank(message = "email must not be blank")
    @field:Email(message = "email must be valid")
    val email: String?,
)

@Path("/api/setup")
@ApplicationScoped
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
class SetupResource @Inject constructor(
    private val setupService: SetupService,
) {

    @GET
    fun status(): SetupStatusSummary =
        setupService.status()

    @POST
    @Path("/admin")
    fun createAdmin(@Valid request: AdminSetupRequest): UserSummary =
        setupService.createAdmin(request)
}
