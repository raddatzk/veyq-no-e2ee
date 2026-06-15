package com.veyq.app.features.memory.share

import com.veyq.app.generated.api.MemoryShareApi
import com.veyq.app.generated.api.PublicShareMediaApi
import com.veyq.app.generated.api.PublicShareTreeApi
import com.veyq.app.generated.model.CreateShareRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response

/** Owner-side share CRUD (authed). */
@ApplicationScoped
class ShareResource(private val handler: ShareHandler) : MemoryShareApi {
    override fun listShares(authorization: String, scopeId: String, nodeId: String): Response =
        Response.ok(handler.listShares(authorization, scopeId, nodeId)).build()

    override fun createShare(authorization: String, scopeId: String, nodeId: String, createShareRequest: CreateShareRequest): Response =
        Response.ok(handler.createShare(authorization, scopeId, nodeId, createShareRequest)).build()

    override fun revokeShare(authorization: String, scopeId: String, nodeId: String, shareId: String): Response {
        handler.revokeShare(authorization, scopeId, nodeId, shareId)
        return Response.noContent().build()
    }
}

/** Guest-side share tree (no auth). */
@ApplicationScoped
class PublicShareTreeResource(private val handler: PublicShareTreeHandler) : PublicShareTreeApi {
    override fun getPublicShare(shareId: String): Response = Response.ok(handler.getPublicShare(shareId)).build()
    override fun listShareNodes(shareId: String): Response = Response.ok(handler.listShareNodes(shareId)).build()
}

/** Guest-side share media + blob frames (no auth). */
@ApplicationScoped
class PublicShareMediaResource(private val handler: PublicShareMediaHandler) : PublicShareMediaApi {
    override fun listShareMedia(shareId: String, nodeId: String): Response = Response.ok(handler.listShareMedia(shareId, nodeId)).build()
    override fun getShareChunk(shareId: String, blobId: String, index: Int): Response = Response.ok(handler.getShareChunk(shareId, blobId, index)).build()
}
