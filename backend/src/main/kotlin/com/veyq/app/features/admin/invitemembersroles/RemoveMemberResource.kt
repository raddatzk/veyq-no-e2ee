package com.veyq.app.features.admin.invitemembersroles

import com.veyq.app.generated.api.MemberRemovalApi
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response

/** Thin REST entry implementing the generated MemberRemovalApi; delegates to the handler. */
@ApplicationScoped
class RemoveMemberResource(private val handler: RemoveMemberHandler) : MemberRemovalApi {

    override fun removeMember(authorization: String, tenantId: String, userId: String): Response =
        Response.ok(handler.removeMember(authorization, tenantId, userId)).build()
}
