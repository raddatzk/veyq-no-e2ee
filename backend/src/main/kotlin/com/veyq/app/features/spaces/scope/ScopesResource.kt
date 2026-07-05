package com.veyq.app.features.spaces.scope

import com.veyq.app.generated.api.ScopesApi
import com.veyq.app.generated.model.AddGroupMemberRequest
import com.veyq.app.generated.model.CreateGroupRequest
import com.veyq.app.generated.model.CreatePersonalScopeRequest
import com.veyq.app.generated.model.ErrorResponse
import com.veyq.app.generated.model.MyTenant
import com.veyq.app.generated.model.ScopeCreated
import com.veyq.app.generated.model.ScopeKind
import com.veyq.app.generated.model.ScopeMember
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

    override fun createGroup(createGroupRequest: CreateGroupRequest): Response =
        when (val outcome = handler.createGroup(
            identity.principal.name, activeHost(), createGroupRequest.encryptedName, createGroupRequest.wrappedGrant,
        )) {
            is CreateGroupOutcome.Created ->
                Response.status(Response.Status.CREATED)
                    .entity(ScopeCreated(scopeId = UUID.fromString(outcome.scopeId), kind = ScopeKind.GROUP)).build()
            CreateGroupOutcome.NoTenant ->
                Response.status(Response.Status.NOT_FOUND).entity(ErrorResponse(code = "tenant_not_found")).build()
        }

    override fun listMyScopes(): Response =
        when (val outcome = handler.listMine(identity.principal.name, activeHost())) {
            is ListScopesOutcome.Scopes ->
                Response.ok(outcome.rows.map {
                    ScopeSummary(
                        id = UUID.fromString(it.id), kind = ScopeKind.valueOf(it.kind),
                        tenantId = UUID.fromString(it.tenantId), wrappedGrant = it.wrappedGrant,
                        encryptedName = it.encryptedName,
                    )
                }).build()
            ListScopesOutcome.NoTenant ->
                Response.status(Response.Status.NOT_FOUND).entity(ErrorResponse(code = "tenant_not_found")).build()
        }

    override fun addGroupMember(scopeId: UUID, addGroupMemberRequest: AddGroupMemberRequest): Response =
        when (val outcome = handler.addGroupMember(identity.principal.name, scopeId.toString(), addGroupMemberRequest.username)) {
            is AddGroupMemberOutcome.Added -> Response.status(Response.Status.CREATED).entity(
                ScopeMember(
                    userId = UUID.fromString(outcome.row.userId), username = outcome.row.username,
                    sealedName = outcome.row.sealedName, email = outcome.row.email, role = outcome.row.role,
                    identityPublicKey = outcome.row.identityPublicKey, hasGrant = outcome.row.hasGrant,
                ),
            ).build()
            AddGroupMemberOutcome.NotOwner ->
                Response.status(Response.Status.FORBIDDEN).entity(ErrorResponse(code = "not_scope_owner")).build()
            AddGroupMemberOutcome.ScopeNotFound ->
                Response.status(Response.Status.NOT_FOUND).entity(ErrorResponse(code = "scope_not_found")).build()
            AddGroupMemberOutcome.MemberNotFound ->
                Response.status(Response.Status.NOT_FOUND).entity(ErrorResponse(code = "member_not_found")).build()
            AddGroupMemberOutcome.AlreadyMember ->
                Response.status(Response.Status.CONFLICT).entity(ErrorResponse(code = "already_member")).build()
        }

    override fun listScopeMembers(scopeId: UUID): Response =
        when (val outcome = handler.listMembers(identity.principal.name, scopeId.toString())) {
            is ListMembersOutcome.Members -> Response.ok(
                outcome.rows.map {
                    ScopeMember(
                        userId = UUID.fromString(it.userId), username = it.username, sealedName = it.sealedName,
                        email = it.email, role = it.role, identityPublicKey = it.identityPublicKey, hasGrant = it.hasGrant,
                    )
                },
            ).build()
            ListMembersOutcome.Forbidden ->
                Response.status(Response.Status.FORBIDDEN).entity(ErrorResponse(code = "not_scope_member")).build()
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
