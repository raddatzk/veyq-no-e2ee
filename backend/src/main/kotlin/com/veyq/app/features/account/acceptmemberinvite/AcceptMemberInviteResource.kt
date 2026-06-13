package com.veyq.app.features.account.acceptmemberinvite

import com.veyq.app.generated.api.AcceptInviteApi
import com.veyq.app.generated.model.AcceptMemberInviteRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response

/** Thin REST entry implementing the generated AcceptInviteApi; delegates to the handler. */
@ApplicationScoped
class AcceptMemberInviteResource(private val handler: AcceptMemberInviteHandler) : AcceptInviteApi {

    override fun acceptMemberInvite(authorization: String, inviteId: String, acceptMemberInviteRequest: AcceptMemberInviteRequest): Response =
        Response.ok(handler.acceptMemberInvite(authorization, inviteId, acceptMemberInviteRequest)).build()
}
