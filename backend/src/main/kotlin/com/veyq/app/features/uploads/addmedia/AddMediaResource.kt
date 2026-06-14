package com.veyq.app.features.uploads.addmedia

import com.veyq.app.generated.api.MediaApi
import com.veyq.app.generated.api.MediaDedupApi
import com.veyq.app.generated.model.CreateMediaRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response

/** Thin REST entry implementing the generated MediaApi; delegates to the handler. */
@ApplicationScoped
class AddMediaResource(private val handler: AddMediaHandler) : MediaApi {

    override fun listMedia(authorization: String, scopeId: String, nodeId: String): Response =
        Response.ok(handler.listMedia(authorization, scopeId, nodeId)).build()

    override fun addMedia(authorization: String, scopeId: String, nodeId: String, createMediaRequest: CreateMediaRequest): Response =
        Response.ok(handler.addMedia(authorization, scopeId, nodeId, createMediaRequest)).build()
}

/** Separate resource for the dedup pre-check — a distinct class-@Path can't share AddMediaResource. */
@ApplicationScoped
class MediaDedupResource(private val handler: AddMediaHandler) : MediaDedupApi {

    override fun checkMediaBlob(authorization: String, scopeId: String, dedupToken: String): Response =
        Response.ok(handler.checkMediaBlob(authorization, scopeId, dedupToken)).build()
}
