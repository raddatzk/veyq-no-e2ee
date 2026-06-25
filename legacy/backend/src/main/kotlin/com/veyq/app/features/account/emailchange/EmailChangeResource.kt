package com.veyq.app.features.account.emailchange

import com.veyq.app.generated.api.EmailApi
import com.veyq.app.generated.model.EmailChangeConfirmRequest
import com.veyq.app.generated.model.EmailChangeRequestRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.Response

@ApplicationScoped
class EmailChangeResource(private val handler: EmailChangeHandler) : EmailApi {
    @Context
    lateinit var httpHeaders: HttpHeaders

    override fun requestEmailChange(emailChangeRequestRequest: EmailChangeRequestRequest): Response {
        handler.requestChange(authorization(), emailChangeRequestRequest)
        return Response.noContent().build()
    }

    override fun confirmEmailChange(emailChangeConfirmRequest: EmailChangeConfirmRequest): Response =
        Response.ok(handler.confirm(emailChangeConfirmRequest)).build()

    private fun authorization(): String? = httpHeaders.getHeaderString(HttpHeaders.AUTHORIZATION)
}
