package com.veyq.app.features.admin.createtenantinviteowner

import com.veyq.app.features.account.acceptmemberinvite.AcceptMemberInviteHandler
import com.veyq.app.features.account.registerviainvitation.RegisterViaInvitationHandler
import com.veyq.app.features.admin.invitemembersroles.EmailInviteHandler
import com.veyq.app.features.admin.invitemembersroles.MemberInviteHandler
import com.veyq.app.generated.api.TenantInvitesApi
import com.veyq.app.generated.model.AcceptMemberInviteRequest
import com.veyq.app.generated.model.ClaimTenantOwnershipRequest
import com.veyq.app.generated.model.EmailInviteRequest
import com.veyq.app.generated.model.GetInviteMaterialRequest
import com.veyq.app.generated.model.IssueMemberInviteRequest
import com.veyq.app.generated.model.IssueOwnerInviteRequest
import com.veyq.app.generated.model.RegisterViaInvitationRequest
import com.veyq.app.shared.security.refreshSessionCookie
import com.veyq.app.shared.tenancy.CurrentTenantResolver
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response

/** Thin REST entry implementing the generated TenantInvitesApi; delegates to invite handlers. */
@ApplicationScoped
class TenantInvitesResource(
    private val provisioning: TenantProvisioningHandler,
    private val memberInvites: MemberInviteHandler,
    private val emailInvites: EmailInviteHandler,
    private val registration: RegisterViaInvitationHandler,
    private val acceptInvite: AcceptMemberInviteHandler,
    private val currentTenant: CurrentTenantResolver,
) : TenantInvitesApi {

    override fun issueOwnerInvite(authorization: String, issueOwnerInviteRequest: IssueOwnerInviteRequest): Response =
        Response.ok(provisioning.issueOwnerInvite(authorization, currentTenant.currentTenantId(), issueOwnerInviteRequest)).build()

    override fun issueMemberInvite(authorization: String, issueMemberInviteRequest: IssueMemberInviteRequest): Response =
        Response.ok(memberInvites.issueMemberInvite(authorization, currentTenant.currentTenantId(), issueMemberInviteRequest)).build()

    override fun emailMemberInvite(authorization: String, emailInviteRequest: EmailInviteRequest): Response {
        emailInvites.emailMemberInvite(authorization, currentTenant.currentTenantId(), emailInviteRequest)
        return Response.noContent().build()
    }

    override fun getTenantInvite(inviteId: String): Response =
        Response.ok(provisioning.getTenantInvite(inviteId)).build()

    override fun getInviteMaterial(inviteId: String, getInviteMaterialRequest: GetInviteMaterialRequest): Response =
        Response.ok(provisioning.getInviteMaterial(inviteId, getInviteMaterialRequest)).build()

    override fun claimTenantOwnership(authorization: String, inviteId: String, claimTenantOwnershipRequest: ClaimTenantOwnershipRequest): Response =
        Response.ok(provisioning.claimTenantOwnership(authorization, inviteId, claimTenantOwnershipRequest)).build()

    override fun registerViaInvitation(inviteId: String, registerViaInvitationRequest: RegisterViaInvitationRequest): Response {
        val result = registration.registerViaInvitation(inviteId, registerViaInvitationRequest)
        return Response.ok(result.response)
            .header("Set-Cookie", refreshSessionCookie(result.issuedSession))
            .build()
    }

    override fun acceptMemberInvite(authorization: String, inviteId: String, acceptMemberInviteRequest: AcceptMemberInviteRequest): Response =
        Response.ok(acceptInvite.acceptMemberInvite(authorization, inviteId, acceptMemberInviteRequest)).build()
}
