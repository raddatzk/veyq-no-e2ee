package com.veyq.app.features.admin.setupserver

import com.veyq.app.generated.api.SetupServerApi
import com.veyq.app.generated.model.CreateServerOwnerRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response

/** Thin REST entry implementing the generated SetupServerApi; delegates to the handler. */
@ApplicationScoped
class SetupServerResource(private val handler: SetupServerHandler) : SetupServerApi {

    override fun getSetupStatus(): Response =
        Response.ok(handler.status()).build()

    override fun createServerOwner(createServerOwnerRequest: CreateServerOwnerRequest): Response =
        Response.ok(handler.createServerOwner(createServerOwnerRequest)).build()
}
