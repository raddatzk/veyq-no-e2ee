package com.veyq.app.features.admin.tenant

import com.veyq.app.generated.api.TenantsApi
import com.veyq.app.generated.model.CreateTenantRequest
import com.veyq.app.generated.model.ErrorResponse
import com.veyq.app.generated.model.InviteStatus
import com.veyq.app.generated.model.ReassignOwnerRequest
import com.veyq.app.generated.model.TenantCreated
import com.veyq.app.generated.model.TenantInviteState
import com.veyq.app.generated.model.TenantStatus
import com.veyq.app.generated.model.TenantSummary
import com.veyq.app.shared.identity.ServerRoles.SERVER_OWNER
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.core.Response
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * HTTP adapter for Server-Owner tenant administration (uc.admin.create-tenant Slice A, cr-0009). Implements
 * the generated [TenantsApi]; thin (ADR 0001) — delegates to [TenantHandler] and maps outcomes to status codes.
 * Field validation (name/domain/email) is enforced by @Valid at the boundary (400) before this runs. The
 * Server-Owner gate is the class-level **@RolesAllowed** (cr-0012): a missing/invalid token → 401, a non-owner
 * → 403, both produced centrally by the security ExceptionMappers — so this adapter no longer reads the token,
 * carries no `unauthorized()`/`forbidden()`, and only sees already-authorized calls.
 */
@RolesAllowed(SERVER_OWNER)
class TenantsResource(private val handler: TenantHandler) : TenantsApi {

    override fun createTenant(createTenantRequest: CreateTenantRequest): Response =
        when (val outcome = handler.create(
            createTenantRequest.tenantName,
            createTenantRequest.domain,
            createTenantRequest.ownerEmail,
        )) {
            is CreateTenantOutcome.Created -> Response.status(Response.Status.CREATED)
                .entity(TenantCreated(UUID.fromString(outcome.tenantId), TenantStatus.PENDING, outcome.expiresAt.utc()))
                .build()
            CreateTenantOutcome.DomainTaken -> conflict("domain_taken")
        }

    override fun listTenants(): Response =
        Response.ok(handler.list().map { it.toSummary() }).build()

    override fun resendTenantInvite(tenantId: UUID): Response =
        handler.resendInvite(tenantId.toString()).toResponse()

    override fun reassignTenantInvite(tenantId: UUID, reassignOwnerRequest: ReassignOwnerRequest): Response =
        handler.reassignInvite(tenantId.toString(), reassignOwnerRequest.ownerEmail).toResponse()

    override fun revokeTenant(tenantId: UUID): Response =
        when (handler.revoke(tenantId.toString())) {
            RevokeOutcome.Done -> Response.noContent().build()
            RevokeOutcome.NotFound -> notFound()
            RevokeOutcome.NotPending -> conflict("tenant_not_pending")
        }

    private fun InviteOutcome.toResponse(): Response = when (this) {
        is InviteOutcome.Done -> Response.ok(
            TenantInviteState(invite.ownerEmail, InviteStatus.valueOf(invite.status), invite.expiresAt.utc()),
        ).build()
        InviteOutcome.NotFound -> notFound()
        InviteOutcome.NotPending -> conflict("tenant_not_pending")
    }

    private fun TenantView.toSummary() = TenantSummary(
        tenantId = UUID.fromString(id),
        name = name,
        domain = domain,
        ownerEmail = ownerEmail,
        status = TenantStatus.valueOf(status),
        inviteStatus = inviteStatus?.let { InviteStatus.valueOf(it) },
        inviteExpiresAt = inviteExpiresAt?.utc(),
    )

    private fun Instant.utc() = atOffset(ZoneOffset.UTC)

    private fun notFound() = Response.status(Response.Status.NOT_FOUND)
        .entity(ErrorResponse(code = "tenant_not_found")).build()

    private fun conflict(code: String) = Response.status(Response.Status.CONFLICT)
        .entity(ErrorResponse(code = code)).build()
}
