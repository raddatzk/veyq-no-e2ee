package com.veyq.app.features.spaces.scope

import com.veyq.app.generated.api.ScopesApi
import com.veyq.app.generated.model.AddGroupMemberRequest
import com.veyq.app.generated.model.CreateGroupRequest
import com.veyq.app.generated.model.CreatePersonalScopeRequest
import com.veyq.app.generated.model.ErrorResponse
import com.veyq.app.generated.model.MyTenant
import com.veyq.app.generated.model.RotateScopeRequest
import com.veyq.app.generated.model.ScopeCreated
import com.veyq.app.generated.model.ScopeEpochLink
import com.veyq.app.generated.model.ScopeKind
import com.veyq.app.generated.model.ScopeMember
import com.veyq.app.generated.model.ScopeRotated
import com.veyq.app.generated.model.ScopeSummary
import com.veyq.app.generated.model.SetMemberRoleRequest
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
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
    // The grace window (cr-0090) — used to derive `purgeAfter = deletedAt + retention` for a pending-deletion group.
    @param:ConfigProperty(name = "veyq.scope.delete.retention", defaultValue = "P30D") private val deleteRetention: Duration,
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
                        tenantId = UUID.fromString(it.tenantId), role = it.role,
                        currentEpoch = it.currentEpoch, grantedEpoch = it.grantedEpoch,
                        wrappedGrant = it.wrappedGrant, encryptedName = it.encryptedName,
                        deletedAt = it.deletedAt?.let { d -> OffsetDateTime.ofInstant(d, ZoneOffset.UTC) },
                        purgeAfter = it.deletedAt?.let { d -> OffsetDateTime.ofInstant(d.plus(deleteRetention), ZoneOffset.UTC) },
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

    override fun setMemberRole(scopeId: UUID, userId: UUID, setMemberRoleRequest: SetMemberRoleRequest): Response =
        when (val outcome = handler.setMemberRole(identity.principal.name, scopeId.toString(), userId.toString(), setMemberRoleRequest.role.value)) {
            is SetRoleOutcome.Ok -> Response.ok(
                ScopeMember(
                    userId = UUID.fromString(outcome.row.userId), username = outcome.row.username,
                    sealedName = outcome.row.sealedName, email = outcome.row.email, role = outcome.row.role,
                    identityPublicKey = outcome.row.identityPublicKey, hasGrant = outcome.row.hasGrant,
                ),
            ).build()
            SetRoleOutcome.NotOwner -> forbidden("not_scope_owner")
            SetRoleOutcome.ScopeNotFound, SetRoleOutcome.MemberNotFound -> notFound("member_not_found")
            SetRoleOutcome.LastOwner -> conflict("last_owner")
        }

    override fun removeMember(scopeId: UUID, userId: UUID): Response =
        when (handler.removeMember(identity.principal.name, scopeId.toString(), userId.toString())) {
            RemoveMemberOutcome.Ok -> Response.noContent().build()
            RemoveMemberOutcome.NotOwner -> forbidden("not_scope_owner")
            RemoveMemberOutcome.ScopeNotFound, RemoveMemberOutcome.MemberNotFound -> notFound("member_not_found")
            RemoveMemberOutcome.LastOwner -> conflict("last_owner")
        }

    override fun rotateScope(scopeId: UUID, rotateScopeRequest: RotateScopeRequest): Response =
        when (val outcome = handler.rotateScope(
            identity.principal.name, scopeId.toString(), rotateScopeRequest.wrappedPrev,
            rotateScopeRequest.grants.map { it.userId.toString() to it.wrappedGrant },
        )) {
            is RotateOutcome.Ok -> Response.status(Response.Status.CREATED).entity(ScopeRotated(epoch = outcome.epoch)).build()
            RotateOutcome.NotOwner -> forbidden("not_scope_owner")
            RotateOutcome.ScopeNotFound -> notFound("scope_not_found")
        }

    override fun listScopeEpochs(scopeId: UUID): Response =
        when (val outcome = handler.listEpochs(identity.principal.name, scopeId.toString())) {
            is EpochsOutcome.Links -> Response.ok(outcome.links.map { ScopeEpochLink(epoch = it.first, wrappedPrev = it.second) }).build()
            EpochsOutcome.Forbidden -> forbidden("not_scope_member")
            EpochsOutcome.ScopeNotFound -> notFound("scope_not_found")
        }

    override fun deleteGroup(scopeId: UUID): Response =
        when (handler.deleteGroup(identity.principal.name, scopeId.toString())) {
            DeleteGroupOutcome.Ok -> Response.noContent().build()
            DeleteGroupOutcome.NotOwner -> forbidden("not_scope_owner")
            DeleteGroupOutcome.ScopeNotFound -> notFound("scope_not_found")
            DeleteGroupOutcome.NotGroup -> conflict("not_group")
            DeleteGroupOutcome.AlreadyDeleted -> conflict("scope_already_deleted")
        }

    override fun restoreGroup(scopeId: UUID): Response =
        when (handler.restoreGroup(identity.principal.name, scopeId.toString())) {
            RestoreGroupOutcome.Ok -> Response.noContent().build()
            RestoreGroupOutcome.NotOwner -> forbidden("not_scope_owner")
            RestoreGroupOutcome.ScopeNotFound -> notFound("scope_not_found")
            RestoreGroupOutcome.NotDeleted -> conflict("not_deleted")
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

    private fun forbidden(code: String) = Response.status(Response.Status.FORBIDDEN).entity(ErrorResponse(code = code)).build()
    private fun notFound(code: String) = Response.status(Response.Status.NOT_FOUND).entity(ErrorResponse(code = code)).build()
    private fun conflict(code: String) = Response.status(Response.Status.CONFLICT).entity(ErrorResponse(code = code)).build()

    /** The active tenant's host: the reverse-proxy `X-Forwarded-Host`, else `Host`; first value, port stripped. */
    private fun activeHost(): String? =
        (headers.getHeaderString("X-Forwarded-Host") ?: headers.getHeaderString("Host"))
            ?.substringBefore(',')?.substringBefore(':')?.trim()?.lowercase()?.ifEmpty { null }
}
