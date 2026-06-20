package com.veyq.app.features.account.emailchange

import com.veyq.app.generated.api.EmailChangeConfirmApi
import com.veyq.app.generated.api.EmailChangeRequestApi
import com.veyq.app.generated.model.EmailChangeConfirmRequest
import com.veyq.app.generated.model.EmailChangeRequestRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response

// One Resource per generated tag — each tag carries its own class-level @Path, so a single class implementing
// both would apply every class-@Path to every method (duplicate routes). See the jaxrs-spec codegen gotcha.

@ApplicationScoped
class EmailChangeRequestResource(private val handler: EmailChangeHandler) : EmailChangeRequestApi {
    override fun requestEmailChange(authorization: String, emailChangeRequestRequest: EmailChangeRequestRequest): Response {
        handler.requestChange(authorization, emailChangeRequestRequest)
        return Response.noContent().build()
    }
}

@ApplicationScoped
class EmailChangeConfirmResource(private val handler: EmailChangeHandler) : EmailChangeConfirmApi {
    override fun confirmEmailChange(emailChangeConfirmRequest: EmailChangeConfirmRequest): Response =
        Response.ok(handler.confirm(emailChangeConfirmRequest)).build()
}
