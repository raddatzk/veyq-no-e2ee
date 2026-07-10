package com.veyq.app.features.admin.tenant

import com.veyq.app.generated.api.TenantsApi
import com.veyq.app.generated.model.AcceptInviteRequest
import com.veyq.app.generated.model.AcceptPreviewRequest
import com.veyq.app.generated.model.AcceptTermsRequest
import com.veyq.app.generated.model.CreateTenantRequest
import com.veyq.app.generated.model.ErrorResponse
import com.veyq.app.generated.model.InviteRole
import com.veyq.app.generated.model.InviteStatus
import com.veyq.app.generated.model.LocalizedMarkdown
import com.veyq.app.generated.model.MemberInviteRequest
import com.veyq.app.generated.model.MemberInviteState
import com.veyq.app.generated.model.PendingMemberInvite
import com.veyq.app.generated.model.ReassignOwnerRequest
import com.veyq.app.generated.model.InvitePreview
import com.veyq.app.generated.model.RegisterAndAcceptRequest
import com.veyq.app.generated.model.TenantActivated
import com.veyq.app.generated.model.TenantActivatedWithUser
import com.veyq.app.generated.model.TenantCreated
import com.veyq.app.generated.model.TenantInviteState
import com.veyq.app.generated.model.TenantStatus
import com.veyq.app.generated.model.TenantSummary
import com.veyq.app.generated.model.TenantTerms
import com.veyq.app.generated.model.TenantTermsUpdate
import com.veyq.app.shared.identity.ServerRoles.SERVER_OWNER
import com.veyq.app.shared.identity.WrapData
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.PermitAll
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
class TenantsResource(
    private val handler: TenantHandler,
    private val acceptHandler: TenantAcceptHandler,
    private val termsHandler: TenantTermsHandler,
    private val identity: SecurityIdentity,
) : TenantsApi {

    override fun createTenant(createTenantRequest: CreateTenantRequest): Response =
        when (val outcome = handler.create(
            createTenantRequest.tenantName,
            createTenantRequest.domain,
            createTenantRequest.ownerEmail,
        )) {
            is CreateTenantOutcome.Created -> Response.status(Response.Status.CREATED)
                .entity(TenantCreated(UUID.fromString(outcome.tenantId), TenantStatus.PENDING, outcome.expiresAt.utc(), outcome.bootstrapSecret))
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

    // Member invite (cr-0077): NOT Server-Owner — the tenant OWNER admits members. @Authenticated overrides the
    // class gate; the owner-membership check lives in the handler (403 not_tenant_owner).
    @Authenticated
    override fun inviteTenantMember(tenantId: UUID, memberInviteRequest: MemberInviteRequest): Response =
        when (val outcome = handler.inviteMember(identity.principal.name, tenantId.toString(), memberInviteRequest.email)) {
            is MemberInviteOutcome.Done -> Response.status(Response.Status.CREATED).entity(
                MemberInviteState(outcome.invite.ownerEmail, InviteStatus.valueOf(outcome.invite.status), outcome.invite.expiresAt.utc(), outcome.invite.bootstrapSecret!!),
            ).build()
            MemberInviteOutcome.NotFound -> notFound()
            MemberInviteOutcome.NotOwner -> Response.status(Response.Status.FORBIDDEN)
                .entity(ErrorResponse(code = "not_tenant_owner")).build()
            MemberInviteOutcome.NotActive -> conflict("tenant_not_active")
            MemberInviteOutcome.AlreadyMember -> conflict("already_member")
        }

    // Member-invite management (cr-0082) — owner-gated tracking of pending member invites: list / re-issue link /
    // revoke. All @Authenticated (not Server-Owner); the OWNER-membership check lives in the handler (403).
    @Authenticated
    override fun listMemberInvites(tenantId: UUID): Response =
        when (val outcome = handler.listMemberInvites(identity.principal.name, tenantId.toString())) {
            is MemberInvitesOutcome.Listed -> Response.ok(
                outcome.invites.map {
                    PendingMemberInvite(UUID.fromString(it.inviteId), it.email, InviteStatus.valueOf(it.status), it.expiresAt.utc())
                },
            ).build()
            MemberInvitesOutcome.NotFound -> notFound()
            MemberInvitesOutcome.NotOwner -> notTenantOwner()
        }

    @Authenticated
    override fun reissueMemberInvite(tenantId: UUID, inviteId: UUID): Response =
        when (val outcome = handler.reissueMemberInvite(identity.principal.name, tenantId.toString(), inviteId.toString())) {
            is ReissueOutcome.Done -> Response.ok(
                MemberInviteState(outcome.invite.ownerEmail, InviteStatus.valueOf(outcome.invite.status), outcome.invite.expiresAt.utc(), outcome.invite.bootstrapSecret!!),
            ).build()
            ReissueOutcome.NotFound -> notFound()
            ReissueOutcome.NotOwner -> notTenantOwner()
            ReissueOutcome.InviteNotFound -> inviteNotFound()
        }

    @Authenticated
    override fun revokeMemberInvite(tenantId: UUID, inviteId: UUID): Response =
        when (handler.revokeMemberInvite(identity.principal.name, tenantId.toString(), inviteId.toString())) {
            RevokeMemberInviteOutcome.Done -> Response.noContent().build()
            RevokeMemberInviteOutcome.NotFound -> notFound()
            RevokeMemberInviteOutcome.NotOwner -> notTenantOwner()
            RevokeMemberInviteOutcome.InviteNotFound -> inviteNotFound()
        }

    // Remove a member (cr-0091): the ACL-revoke cascade + orphan rule. @Authenticated (not Server-Owner); the
    // owner-gate + last-owner guard live in the handler (403 not_tenant_owner / 409 last_owner).
    @Authenticated
    override fun removeTenantMember(tenantId: UUID, userId: UUID): Response =
        when (handler.removeMember(identity.principal.name, tenantId.toString(), userId.toString())) {
            RemoveTenantMemberOutcome.Ok -> Response.noContent().build()
            RemoveTenantMemberOutcome.NotOwner -> notTenantOwner()
            RemoveTenantMemberOutcome.MemberNotFound -> memberNotFound()
            RemoveTenantMemberOutcome.LastOwner -> conflict("last_owner")
        }

    // --- Slice B (cr-0010): invite acceptance / activation. Method-level annotations override the class
    // @RolesAllowed: preview + register are @PermitAll (secret-gated), accept is @Authenticated (any user;
    // the email-match is a domain check, not the Server-Owner role).
    @PermitAll
    override fun previewTenantInvite(acceptPreviewRequest: AcceptPreviewRequest): Response =
        when (val outcome = acceptHandler.preview(acceptPreviewRequest.bootstrapSecret)) {
            is PreviewOutcome.Found -> Response.ok(
                InvitePreview(
                    tenantId = UUID.fromString(outcome.tenantId),
                    tenantName = outcome.tenantName,
                    domain = outcome.domain,
                    ownerEmail = outcome.ownerEmail,
                    hasAccount = outcome.hasAccount,
                    role = InviteRole.valueOf(outcome.role),
                ),
            ).build()
            PreviewOutcome.NotFound -> inviteNotFound()
            PreviewOutcome.NoLongerValid -> inviteGone()
        }

    @Authenticated
    override fun acceptTenantInvite(acceptInviteRequest: AcceptInviteRequest): Response =
        when (val outcome = acceptHandler.accept(
            identity.principal.name,
            acceptInviteRequest.bootstrapSecret,
            acceptInviteRequest.grant,
            acceptInviteRequest.username,
            acceptInviteRequest.sealedName,
        )) {
            is AcceptOutcome.Activated ->
                Response.ok(TenantActivated(UUID.fromString(outcome.tenantId), TenantStatus.ACTIVE)).build()
            AcceptOutcome.NotFound -> inviteNotFound()
            AcceptOutcome.NoLongerValid -> inviteGone()
            AcceptOutcome.NotInvitedOwner -> Response.status(Response.Status.FORBIDDEN)
                .entity(ErrorResponse(code = "not_invited_owner")).build()
            AcceptOutcome.AlreadyActive -> conflict("tenant_not_pending")
            AcceptOutcome.UsernameTaken -> conflict("username_taken")
            AcceptOutcome.MissingGrant -> badRequest()
        }

    @PermitAll
    override fun registerAndAcceptTenantInvite(registerAndAcceptRequest: RegisterAndAcceptRequest): Response {
        val a = registerAndAcceptRequest.account
        val account = NewAccount(
            suiteId = a.suiteId.value,
            identityPublicKey = a.identityPublicKey,
            wrappedIdentityPrivateKey = a.wrappedIdentityPrivateKey,
            authPublicKey = a.authPublicKey,
            recoveryMasterKeyTag = a.recoveryMasterKeyTag,
            wraps = a.masterKeyWraps.map { WrapData(it.method.value, it.wrappedKey, it.kdfParams) },
        )
        return when (val outcome = acceptHandler.registerAndAccept(
            registerAndAcceptRequest.bootstrapSecret, account, registerAndAcceptRequest.grant,
            registerAndAcceptRequest.username, registerAndAcceptRequest.sealedName,
        )) {
            is RegisterAcceptOutcome.Activated -> Response.status(Response.Status.CREATED)
                .entity(TenantActivatedWithUser(UUID.fromString(outcome.tenantId), TenantStatus.ACTIVE, UUID.fromString(outcome.userId)))
                .build()
            RegisterAcceptOutcome.NotFound -> inviteNotFound()
            RegisterAcceptOutcome.NoLongerValid -> inviteGone()
            RegisterAcceptOutcome.EmailTaken -> conflict("email_taken")
            RegisterAcceptOutcome.UsernameTaken -> conflict("username_taken")
            RegisterAcceptOutcome.MissingGrant -> badRequest()
        }
    }

    // --- Terms of service (cr-0114, ADR 0035). All @Authenticated (not Server-Owner): read/accept are
    // member-facing, update is tenant-owner-gated in the handler (403 not_tenant_owner). A non-member read/accept
    // is 404 (indistinguishable). ---
    @Authenticated
    override fun getTenantTerms(tenantId: UUID): Response =
        when (val o = termsHandler.read(identity.principal.name, tenantId.toString())) {
            is TermsReadOutcome.Ok -> Response.ok(TenantTerms(o.version, o.acceptedVersion, o.terms?.toDto())).build()
            TermsReadOutcome.NotFound -> notFound()
        }

    @Authenticated
    override fun updateTenantTerms(tenantId: UUID, tenantTermsUpdate: TenantTermsUpdate): Response =
        when (val o = termsHandler.update(
            identity.principal.name, tenantId.toString(), tenantTermsUpdate.terms.de, tenantTermsUpdate.terms.en,
        )) {
            is TermsUpdateOutcome.Ok -> Response.ok(TenantTerms(o.version, o.acceptedVersion, o.terms?.toDto())).build()
            TermsUpdateOutcome.NotFound -> notFound()
            TermsUpdateOutcome.NotOwner -> notTenantOwner()
        }

    @Authenticated
    override fun acceptTenantTerms(tenantId: UUID, acceptTermsRequest: AcceptTermsRequest): Response =
        when (val o = termsHandler.accept(identity.principal.name, tenantId.toString(), acceptTermsRequest.version)) {
            is TermsAcceptOutcome.Ok -> Response.ok(TenantTerms(o.version, o.acceptedVersion, o.terms?.toDto())).build()
            TermsAcceptOutcome.NotFound -> notFound()
            TermsAcceptOutcome.VersionChanged -> conflict("terms_version_changed")
        }

    private fun TermsContent.toDto() = LocalizedMarkdown(de, en)

    private fun inviteNotFound() = Response.status(Response.Status.NOT_FOUND)
        .entity(ErrorResponse(code = "invite_not_found")).build()

    private fun inviteGone() = Response.status(Response.Status.GONE)
        .entity(ErrorResponse(code = "invite_no_longer_valid")).build()

    private fun InviteOutcome.toResponse(): Response = when (this) {
        is InviteOutcome.Done -> Response.ok(
            TenantInviteState(invite.ownerEmail, InviteStatus.valueOf(invite.status), invite.expiresAt.utc(), invite.bootstrapSecret!!),
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

    private fun notTenantOwner() = Response.status(Response.Status.FORBIDDEN)
        .entity(ErrorResponse(code = "not_tenant_owner")).build()

    private fun memberNotFound() = Response.status(Response.Status.NOT_FOUND)
        .entity(ErrorResponse(code = "member_not_found")).build()

    private fun badRequest() = Response.status(Response.Status.BAD_REQUEST)
        .entity(ErrorResponse(code = "invalid_request")).build()

    private fun conflict(code: String) = Response.status(Response.Status.CONFLICT)
        .entity(ErrorResponse(code = code)).build()
}
