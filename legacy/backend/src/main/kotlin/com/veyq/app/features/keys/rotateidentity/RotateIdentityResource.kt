package com.veyq.app.features.keys.rotateidentity

import com.veyq.app.generated.api.IdentityRotationApi
import com.veyq.app.generated.model.RotateIdentityRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response

/** Thin REST entry implementing the generated IdentityRotationApi; delegates to the handler. */
@ApplicationScoped
class RotateIdentityResource(private val handler: RotateIdentityHandler) : IdentityRotationApi {

    override fun rotateIdentity(authorization: String, rotateIdentityRequest: RotateIdentityRequest): Response =
        Response.ok(handler.rotateIdentity(authorization, rotateIdentityRequest)).build()
}
