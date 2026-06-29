package com.veyq.app.features.spaces.scope

import com.veyq.app.generated.api.ScopesApi
import com.veyq.app.generated.model.CreatePersonalScopeRequest
import com.veyq.app.generated.model.ErrorResponse
import com.veyq.app.generated.model.MyTenant
import com.veyq.app.generated.model.ScopeCreated
import com.veyq.app.generated.model.ScopeKind
import com.veyq.app.generated.model.ScopeSummary
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.Response
import java.util.UUID

/**
 * HTTP adapter for a member's scopes (en.spaces.personal-scope, cr-0026). Implements the generated
 * [ScopesApi]; thin (ADR 0001) — delegates to [ScopesHandler]. Authentication is central (`@Authenticated`):
 * a missing/invalid token is a generic 401 before this runs. The active tenant is resolved from the request
 * Host (the reverse-proxy `X-Forwarded-Host`, else `Host`). Operator-blind: both endpoints handle only the
 * **caller's own** scopes (userId from the `SecurityIdentity` principal) and the server stores/returns only
 * ciphertext grants, never a scope key.
 */
@Authenticated
class ScopesResource(
    private val handler: ScopesHandler,
    private val identity: SecurityIdentity,
) : ScopesApi {

    @Context
    lateinit var headers: HttpHeaders

    override fun createPersonalScope(createPersonalScopeRequest: CreatePersonalScopeRequest): Response =
        when (val outcome = handler.createPersonal(
            identity.principal.name, activeHost(), createPersonalScopeRequest.wrappedGrant,
        )) {
            is CreatePersonalOutcome.Created ->
                Response.status(Response.Status.CREATED)
                    .entity(ScopeCreated(scopeId = UUID.fromString(outcome.scopeId), kind = ScopeKind.PERSONAL)).build()
            CreatePersonalOutcome.NoTenant ->
                Response.status(Response.Status.NOT_FOUND).entity(ErrorResponse(code = "tenant_not_found")).build()
            CreatePersonalOutcome.AlreadyExists ->
                Response.status(Response.Status.CONFLICT).entity(ErrorResponse(code = "personal_scope_exists")).build()
        }

    override fun listMyScopes(): Response =
        when (val outcome = handler.listMine(identity.principal.name, activeHost())) {
            is ListScopesOutcome.Scopes ->
                Response.ok(outcome.rows.map {
                    ScopeSummary(
                        id = UUID.fromString(it.id), kind = ScopeKind.valueOf(it.kind),
                        tenantId = UUID.fromString(it.tenantId), wrappedGrant = it.wrappedGrant,
                    )
                }).build()
            ListScopesOutcome.NoTenant ->
                Response.status(Response.Status.NOT_FOUND).entity(ErrorResponse(code = "tenant_not_found")).build()
        }

    override fun listMyTenants(): Response =
        Response.ok(
            handler.myTenants(identity.principal.name).map {
                MyTenant(tenantId = UUID.fromString(it.tenantId), name = it.name, domain = it.domain, role = it.role)
            },
        ).build()

    /** The active tenant's host: the reverse-proxy `X-Forwarded-Host`, else `Host`; first value, port stripped. */
    private fun activeHost(): String? =
        (headers.getHeaderString("X-Forwarded-Host") ?: headers.getHeaderString("Host"))
            ?.substringBefore(',')?.substringBefore(':')?.trim()?.lowercase()?.ifEmpty { null }
}
