package com.veyq.app.features.admin.invitemembersroles

import com.veyq.app.generated.api.MemberInvitesApi
import com.veyq.app.generated.model.IssueMemberInviteRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response

/** Thin REST entry implementing the generated MemberInvitesApi; delegates to the member-invite handler. */
@ApplicationScoped
class MemberInviteResource(private val handler: MemberInviteHandler) : MemberInvitesApi {

    override fun issueMemberInvite(authorization: String, tenantId: String, issueMemberInviteRequest: IssueMemberInviteRequest): Response =
        Response.ok(handler.issueMemberInvite(authorization, tenantId, issueMemberInviteRequest)).build()
}
