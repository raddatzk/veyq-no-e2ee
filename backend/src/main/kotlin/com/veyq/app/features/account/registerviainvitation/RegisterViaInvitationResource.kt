package com.veyq.app.features.account.registerviainvitation

import com.veyq.app.generated.api.RegisterViaInvitationApi
import com.veyq.app.generated.model.RegisterViaInvitationRequest
import com.veyq.app.shared.security.refreshSessionCookie
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response

/** Thin REST entry implementing the generated RegisterViaInvitationApi; delegates to the handler. */
@ApplicationScoped
class RegisterViaInvitationResource(private val handler: RegisterViaInvitationHandler) : RegisterViaInvitationApi {

    override fun registerViaInvitation(inviteId: String, registerViaInvitationRequest: RegisterViaInvitationRequest): Response {
        val result = handler.registerViaInvitation(inviteId, registerViaInvitationRequest)
        return Response.ok(result.response)
            .header("Set-Cookie", refreshSessionCookie(result.issuedSession))
            .build()
    }
}
