package com.veyq.app.features.admin.invitemembersroles

import com.veyq.app.generated.api.MemberListApi
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response

/** Thin REST entry implementing the generated MemberListApi; delegates to the handler. */
@ApplicationScoped
class MemberListResource(private val handler: MemberListHandler) : MemberListApi {

    override fun listTenantMembers(authorization: String, tenantId: String): Response =
        Response.ok(handler.listTenantMembers(authorization, tenantId)).build()
}
