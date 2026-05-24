package com.veyq.api.auth

import com.veyq.api.generated.api.AuthApi
import com.veyq.api.generated.model.LoginRequest
import com.veyq.api.generated.model.PasskeyLoginRequest
import com.veyq.api.auth.passkey.PasskeyService
import io.vertx.ext.web.RoutingContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

@ApplicationScoped
class AuthResource @Inject constructor(
    private val authService: AuthService,
    private val passkeyService: PasskeyService,
    private val routingContext: RoutingContext,
) : AuthApi {
    override fun login(request: LoginRequest): Response {
        val (response, accessCookie, refreshCookie) = authService.login(request)

        return Response.ok(response)
            .cookie(accessCookie, refreshCookie)
            .build()
    }

    override fun passkeyLoginOptions(): Response =
        Response.ok(passkeyService.loginOptions(routingContext), MediaType.APPLICATION_JSON).build()

    override fun passkeyLogin(request: PasskeyLoginRequest): Response {
        val (response, accessCookie, refreshCookie) = passkeyService.login(request, routingContext)

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
