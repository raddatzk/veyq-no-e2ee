package com.veyq.app.features.admin.invitemembersroles

import com.veyq.app.generated.api.DirectoryApi
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response

/** Thin REST entry implementing the generated DirectoryApi; delegates to the handler. */
@ApplicationScoped
class DirectoryResource(private val handler: DirectoryHandler) : DirectoryApi {

    override fun lookupDirectoryEntry(authorization: String, email: String): Response =
        Response.ok(handler.lookupDirectoryEntry(authorization, email)).build()
}
