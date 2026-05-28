package com.veyq.api.auth

import com.veyq.api.generated.api.AuthApi
import com.veyq.api.generated.model.CompletePasskeyLinkRequest
import com.veyq.api.generated.model.CreatePasskeyLinkRequest
import com.veyq.api.generated.model.ExchangePasskeyLinkCodeRequest
import com.veyq.api.generated.model.LoginRequest
import com.veyq.api.generated.model.PasskeyLinkOptionsRequest
import com.veyq.api.generated.model.PasskeyLoginRequest
import com.veyq.api.auth.passkey.PasskeyService
import com.veyq.api.auth.passkey.PasskeyLinkService
import io.vertx.ext.web.RoutingContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

@ApplicationScoped
class AuthResource @Inject constructor(
    private val authService: AuthService,
    private val passkeyService: PasskeyService,
    private val passkeyLinkService: PasskeyLinkService,
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

    override fun passkeyLoginDomains(): Response =
        Response.ok(passkeyService.loginDomains(routingContext)).build()

    override fun createPasskeyLink(request: CreatePasskeyLinkRequest): Response =
        Response.ok(passkeyLinkService.createLink(request, routingContext)).build()

    override fun exchangePasskeyLinkCode(request: ExchangePasskeyLinkCodeRequest): Response =
        Response.ok(passkeyLinkService.exchangeCode(request, routingContext)).build()

    override fun createPasskeyLinkOptions(request: PasskeyLinkOptionsRequest): Response =
        Response.ok(passkeyLinkService.registrationOptions(request, routingContext), MediaType.APPLICATION_JSON).build()

    override fun completePasskeyLink(request: CompletePasskeyLinkRequest): Response {
        val (response, accessCookie, refreshCookie) = passkeyLinkService.complete(request, routingContext)

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
