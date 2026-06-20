package com.veyq.app.features.admin.serverusers

import com.veyq.app.generated.api.ServerAdminApi
import com.veyq.app.generated.model.SetServerRoleRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response

/** Thin REST entry implementing the generated ServerAdminApi; delegates to the handler. */
@ApplicationScoped
class ServerAdminResource(private val handler: ServerAdminHandler) : ServerAdminApi {

    override fun listServerUsers(authorization: String): Response =
        Response.ok(handler.listUsers(authorization)).build()

    override fun setServerRole(authorization: String, userId: String, setServerRoleRequest: SetServerRoleRequest): Response =
        Response.ok(handler.setServerRole(authorization, userId, setServerRoleRequest)).build()
}
