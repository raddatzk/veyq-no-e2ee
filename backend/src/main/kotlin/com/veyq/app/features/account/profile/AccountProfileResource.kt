package com.veyq.app.features.account.profile

import com.veyq.app.generated.api.AccountProfileApi
import com.veyq.app.generated.model.ErrorResponse
import com.veyq.app.generated.model.MemberProfile
import com.veyq.app.generated.model.ProfileUpdateRequest
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.Response

/**
 * HTTP adapter for the caller's per-tenant identity profile (username + sealed real name, ADR 0023). Implements
 * the generated [AccountProfileApi]; thin (ADR 0001) — delegates to [AccountProfileHandler]. Authentication is
 * central (`@Authenticated`); the tenant is resolved from the request Host (like the scopes endpoints). Only the
 * caller's own profile (userId from the principal) — no id/email in the request.
 */
@Authenticated
class AccountProfileResource(
    private val handler: AccountProfileHandler,
    private val identity: SecurityIdentity,
) : AccountProfileApi {

    @Context
    lateinit var headers: HttpHeaders

    override fun getMyProfile(): Response =
        when (val outcome = handler.get(identity.principal.name, activeHost())) {
            is ProfileOutcome.Found -> Response.ok(memberProfile(outcome)).build()
            else -> profileNotFound()
        }

    override fun updateMyProfile(profileUpdateRequest: ProfileUpdateRequest): Response =
        when (val outcome = handler.update(
            identity.principal.name, activeHost(), profileUpdateRequest.username, profileUpdateRequest.sealedName,
            profileUpdateRequest.requireGroupAddConsent,
        )) {
            is ProfileOutcome.Found -> Response.ok(memberProfile(outcome)).build()
            ProfileOutcome.NoTenant -> profileNotFound()
            ProfileOutcome.UsernameTaken -> Response.status(Response.Status.CONFLICT)
                .entity(ErrorResponse(code = "username_taken")).build()
        }

    private fun memberProfile(o: ProfileOutcome.Found) =
        MemberProfile(username = o.username, requireGroupAddConsent = o.requireGroupAddConsent, sealedName = o.sealedName)

    private fun profileNotFound() = Response.status(Response.Status.NOT_FOUND)
        .entity(ErrorResponse(code = "profile_not_found")).build()

    /** The active tenant's host: the reverse-proxy `X-Forwarded-Host`, else `Host`; first value, port stripped. */
    private fun activeHost(): String? =
        (headers.getHeaderString("X-Forwarded-Host") ?: headers.getHeaderString("Host"))
            ?.substringBefore(',')?.substringBefore(':')?.trim()?.lowercase()?.ifEmpty { null }
}
