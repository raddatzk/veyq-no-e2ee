package com.lunva.api.auth

import com.lunva.api.auth.cookie.CookieService
import com.lunva.api.auth.token.TokenService
import com.lunva.api.user.UserService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

@Path("/api/auth")
@ApplicationScoped
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
class AuthResource @Inject constructor(
    private val tokenService: TokenService,
    private val authService: AuthService,
    private val cookieService: CookieService,
    private val userService: UserService,
) {
    data class LoginRequest(
        @field:NotBlank(message = "username must not be blank") val username: String?,
        @field:NotBlank(message = "password must not be blank") val password: String?
    )

    data class LoginResponse(val userId: Long, val username: String, val displayName: String)

    @POST
    @Path("/login")
    fun login(@Valid request: LoginRequest): Response {
        val (response, accessCookie, refreshCookie) = authService.login(request)

        return Response.ok(response)
            .cookie(accessCookie, refreshCookie)
            .build()
    }

    @POST
    @Path("/logout")
    fun logout(): Response {
        val (accessCookie, refreshCookie) = authService.logout()

        return Response.noContent()
            .cookie(accessCookie, refreshCookie)
            .build()
    }

    @POST
    @Path("/refresh")
    fun refresh(): Response {
        val (_, accessCookie, refreshCookie) = authService.refresh()

        return Response.noContent()
            .cookie(accessCookie, refreshCookie)
            .build()
    }

}
