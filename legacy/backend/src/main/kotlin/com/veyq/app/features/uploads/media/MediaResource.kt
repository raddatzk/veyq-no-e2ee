package com.veyq.app.features.uploads.media

import com.veyq.app.generated.api.MediaApi
import com.veyq.app.generated.api.MediaBatchApi
import com.veyq.app.generated.model.BatchFinalizeMediaRequest
import com.veyq.app.generated.model.CreateMediaRequest
import com.veyq.app.generated.model.UpdateMediaRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response

/** Thin REST entry implementing the generated MediaApi; delegates to the handler. */
@ApplicationScoped
class MediaResource(private val handler: MediaHandler) : MediaApi {

    override fun listMedia(authorization: String, scopeId: String, nodeId: String): Response =
        Response.ok(handler.listMedia(authorization, scopeId, nodeId)).build()

    override fun createMedia(authorization: String, scopeId: String, nodeId: String, createMediaRequest: CreateMediaRequest): Response =
        Response.ok(handler.createMedia(authorization, scopeId, nodeId, createMediaRequest)).build()

    override fun updateMedia(authorization: String, scopeId: String, nodeId: String, mediaId: String, updateMediaRequest: UpdateMediaRequest): Response =
        Response.ok(handler.updateMedia(authorization, scopeId, nodeId, mediaId, updateMediaRequest)).build()

    override fun deleteMedia(authorization: String, scopeId: String, nodeId: String, mediaId: String): Response {
        handler.deleteMedia(authorization, scopeId, nodeId, mediaId)
        return Response.noContent().build()
    }
}

/** Batched finalize (complete uploads + attach media in one round-trip); its own tag/class per the codegen path rule. */
@ApplicationScoped
class MediaBatchResource(private val handler: MediaHandler) : MediaBatchApi {
    override fun batchFinalizeMedia(authorization: String, scopeId: String, nodeId: String, batchFinalizeMediaRequest: BatchFinalizeMediaRequest): Response =
        Response.ok(handler.batchFinalizeMedia(authorization, scopeId, nodeId, batchFinalizeMediaRequest)).build()
}
