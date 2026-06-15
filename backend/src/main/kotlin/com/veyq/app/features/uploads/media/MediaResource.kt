package com.veyq.app.features.uploads.media

import com.veyq.app.generated.api.MediaApi
import com.veyq.app.generated.model.CreateMediaRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response

/** Thin REST entry implementing the generated MediaApi; delegates to the handler. */
@ApplicationScoped
class MediaResource(private val handler: MediaHandler) : MediaApi {

    override fun listMedia(authorization: String, scopeId: String, nodeId: String): Response =
        Response.ok(handler.listMedia(authorization, scopeId, nodeId)).build()

    override fun createMedia(authorization: String, scopeId: String, nodeId: String, createMediaRequest: CreateMediaRequest): Response =
        Response.ok(handler.createMedia(authorization, scopeId, nodeId, createMediaRequest)).build()

    override fun deleteMedia(authorization: String, scopeId: String, nodeId: String, mediaId: String): Response {
        handler.deleteMedia(authorization, scopeId, nodeId, mediaId)
        return Response.noContent().build()
    }
}
