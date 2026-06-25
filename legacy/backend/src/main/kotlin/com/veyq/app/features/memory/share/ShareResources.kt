package com.veyq.app.features.memory.share

import com.veyq.app.generated.api.MemoryShareApi
import com.veyq.app.generated.api.PublicShareContributeApi
import com.veyq.app.generated.api.PublicShareMediaApi
import com.veyq.app.generated.api.PublicShareTreeApi
import com.veyq.app.generated.model.BatchFinalizeMediaRequest
import com.veyq.app.generated.model.BatchPresignGetRequest
import com.veyq.app.generated.model.BatchStartBlobsRequest
import com.veyq.app.generated.model.CreateMediaRequest
import com.veyq.app.generated.model.CreateShareRequest
import com.veyq.app.generated.model.StartBlobRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response

/** Owner-side share CRUD (authed). */
@ApplicationScoped
class ShareResource(private val handler: ShareHandler) : MemoryShareApi {
    override fun listShares(authorization: String, scopeId: String, nodeId: String): Response =
        Response.ok(handler.listShares(authorization, scopeId, nodeId)).build()

    override fun createShare(authorization: String, scopeId: String, nodeId: String, createShareRequest: CreateShareRequest): Response =
        Response.ok(handler.createShare(authorization, scopeId, nodeId, createShareRequest)).build()

    override fun updateShare(authorization: String, scopeId: String, nodeId: String, shareId: String, updateShareRequest: com.veyq.app.generated.model.UpdateShareRequest): Response =
        Response.ok(handler.updateShare(authorization, scopeId, nodeId, shareId, updateShareRequest)).build()

    override fun revokeShare(authorization: String, scopeId: String, nodeId: String, shareId: String): Response {
        handler.revokeShare(authorization, scopeId, nodeId, shareId)
        return Response.noContent().build()
    }
}

/** Guest-side share tree + journal (no auth). */
@ApplicationScoped
class PublicShareTreeResource(private val handler: PublicShareTreeHandler) : PublicShareTreeApi {
    override fun getPublicShare(shareId: String): Response = Response.ok(handler.getPublicShare(shareId)).build()
    override fun listShareNodes(shareId: String): Response = Response.ok(handler.listShareNodes(shareId)).build()
    override fun getShareJournal(shareId: String, nodeId: String): Response = Response.ok(handler.getShareJournal(shareId, nodeId)).build()
    override fun getShareJournalLive(shareId: String, nodeId: String): Response = Response.ok(handler.getShareJournalLive(shareId, nodeId)).build()
    override fun listShareTracks(shareId: String, nodeId: String): Response = Response.ok(handler.listShareTracks(shareId, nodeId)).build()
    override fun listSharePlaylists(shareId: String, nodeId: String): Response = Response.ok(handler.listSharePlaylists(shareId, nodeId)).build()
    override fun listShareParticipants(shareId: String, nodeId: String): Response = Response.ok(handler.listShareParticipants(shareId, nodeId)).build()
}

/** Guest-side contribution: blob upload + media create (no auth, CONTRIBUTOR/UPLOADER shares only). */
@ApplicationScoped
class PublicShareContributeResource(private val handler: PublicShareContributeHandler) : PublicShareContributeApi {
    override fun startShareBlob(shareId: String, startBlobRequest: StartBlobRequest, xVeyqShareUploadToken: String?): Response =
        Response.ok(handler.startShareBlob(shareId, xVeyqShareUploadToken, startBlobRequest)).build()

    override fun getShareUpload(shareId: String, uploadId: String, xVeyqShareUploadToken: String?): Response =
        Response.ok(handler.getShareUpload(shareId, uploadId, xVeyqShareUploadToken)).build()

    override fun completeShareBlob(shareId: String, uploadId: String, xVeyqShareUploadToken: String?): Response =
        Response.ok(handler.completeShareBlob(shareId, uploadId, xVeyqShareUploadToken)).build()

    override fun createShareMedia(shareId: String, nodeId: String, createMediaRequest: CreateMediaRequest, xVeyqShareUploadToken: String?): Response =
        Response.ok(handler.createShareMedia(shareId, nodeId, xVeyqShareUploadToken, createMediaRequest)).build()

    override fun batchStartShareBlobs(shareId: String, batchStartBlobsRequest: BatchStartBlobsRequest, xVeyqShareUploadToken: String?): Response =
        Response.ok(handler.batchStartShareBlobs(shareId, xVeyqShareUploadToken, batchStartBlobsRequest)).build()

    override fun batchFinalizeShareMedia(shareId: String, nodeId: String, batchFinalizeMediaRequest: BatchFinalizeMediaRequest, xVeyqShareUploadToken: String?): Response =
        Response.ok(handler.batchFinalizeShareMedia(shareId, nodeId, xVeyqShareUploadToken, batchFinalizeMediaRequest)).build()
}

/** Guest-side share media + blob frames (no auth). */
@ApplicationScoped
class PublicShareMediaResource(private val handler: PublicShareMediaHandler) : PublicShareMediaApi {
    override fun listShareMedia(shareId: String, nodeId: String): Response = Response.ok(handler.listShareMedia(shareId, nodeId)).build()
    override fun batchPresignShareGet(shareId: String, batchPresignGetRequest: BatchPresignGetRequest): Response =
        Response.ok(handler.batchPresignShareGet(shareId, batchPresignGetRequest)).build()
}
