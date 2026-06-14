package com.veyq.app.features.uploads.resumableupload

import com.veyq.app.generated.api.UploadChunkApi
import com.veyq.app.generated.api.UploadCompleteApi
import com.veyq.app.generated.api.UploadCreateApi
import com.veyq.app.generated.api.UploadStatusApi
import com.veyq.app.generated.model.CreateUploadRequest
import com.veyq.app.generated.model.PutChunkRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response

// One thin resource per tag (one operation per tag for the /api/uploads/{id} family — see the spec note on
// the greedy-@Path codegen gotcha). All delegate to the single ResumableUploadHandler.

@ApplicationScoped
class UploadCreateResource(private val handler: ResumableUploadHandler) : UploadCreateApi {
    override fun createUpload(authorization: String, scopeId: String, nodeId: String, createUploadRequest: CreateUploadRequest): Response =
        Response.ok(handler.createUpload(authorization, scopeId, nodeId, createUploadRequest)).build()
}

@ApplicationScoped
class UploadStatusResource(private val handler: ResumableUploadHandler) : UploadStatusApi {
    override fun getUpload(authorization: String, uploadId: String): Response =
        Response.ok(handler.getUpload(authorization, uploadId)).build()
}

@ApplicationScoped
class UploadCompleteResource(private val handler: ResumableUploadHandler) : UploadCompleteApi {
    override fun completeUpload(authorization: String, uploadId: String): Response =
        Response.ok(handler.completeUpload(authorization, uploadId)).build()
}

@ApplicationScoped
class UploadChunkResource(private val handler: ResumableUploadHandler) : UploadChunkApi {
    override fun putChunk(authorization: String, uploadId: String, index: Int, putChunkRequest: PutChunkRequest): Response {
        handler.putChunk(authorization, uploadId, index, putChunkRequest)
        return Response.noContent().build()
    }

    override fun getChunk(authorization: String, uploadId: String, index: Int): Response =
        Response.ok(handler.getChunk(authorization, uploadId, index)).build()
}
