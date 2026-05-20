package com.veyq.api.auth

import com.veyq.api.generated.api.AuthApi
import com.veyq.api.generated.model.LoginRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.core.Response

@ApplicationScoped
class AuthResource @Inject constructor(
    private val authService: AuthService,
) : AuthApi {
    override fun login(request: LoginRequest): Response {
        val (response, accessCookie, refreshCookie) = authService.login(request)

        return Response.ok(response)
            .cookie(accessCookie, refreshCookie)
            .build()
    }

    override fun logout(): Response {
        val (accessCookie, refreshCookie) = authService.logout()

        return Response.noContent()
            .cookie(accessCookie, refreshCookie)
            .build()
    }

    override fun refresh(): Response {
        val (response, accessCookie, refreshCookie) = authService.refresh()

        return Response.ok(response)
            .cookie(accessCookie, refreshCookie)
            .build()
    }
}
