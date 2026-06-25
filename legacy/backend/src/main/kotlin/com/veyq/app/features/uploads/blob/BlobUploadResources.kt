package com.veyq.app.features.uploads.blob

import com.veyq.app.generated.api.BlobBatchDownloadApi
import com.veyq.app.generated.api.BlobBatchStartApi
import com.veyq.app.generated.api.BlobChunkPresignApi
import com.veyq.app.generated.api.BlobCompleteApi
import com.veyq.app.generated.api.BlobStartApi
import com.veyq.app.generated.api.BlobUploadStatusApi
import com.veyq.app.generated.model.BatchPresignGetRequest
import com.veyq.app.generated.model.BatchStartBlobsRequest
import com.veyq.app.generated.model.StartBlobRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response

// One thin resource per tag — the /blobs and /blob-uploads/{id} routes each have a distinct class-@Path, so
// a single class can't implement several of these generated APIs (greedy-@Path / routing conflict). All
// delegate to the one BlobUploadHandler.

@ApplicationScoped
class BlobStartResource(private val handler: BlobUploadHandler) : BlobStartApi {
    override fun startBlob(authorization: String, scopeId: String, startBlobRequest: StartBlobRequest): Response =
        Response.ok(handler.startBlob(authorization, scopeId, startBlobRequest)).build()
}

@ApplicationScoped
class BlobUploadStatusResource(private val handler: BlobUploadHandler) : BlobUploadStatusApi {
    override fun getBlobUpload(authorization: String, scopeId: String, uploadId: String): Response =
        Response.ok(handler.getBlobUpload(authorization, scopeId, uploadId)).build()
}

@ApplicationScoped
class BlobCompleteResource(private val handler: BlobUploadHandler) : BlobCompleteApi {
    override fun completeBlobUpload(authorization: String, scopeId: String, uploadId: String): Response =
        Response.ok(handler.completeBlobUpload(authorization, scopeId, uploadId)).build()
}

@ApplicationScoped
class BlobChunkPresignResource(private val handler: BlobUploadHandler) : BlobChunkPresignApi {
    override fun getBlobPresignedChunks(authorization: String, scopeId: String, blobId: String): Response =
        Response.ok(handler.getBlobPresignedChunks(authorization, scopeId, blobId)).build()
}

@ApplicationScoped
class BlobBatchStartResource(private val handler: BlobUploadHandler) : BlobBatchStartApi {
    override fun batchStartBlobs(authorization: String, scopeId: String, batchStartBlobsRequest: BatchStartBlobsRequest): Response =
        Response.ok(handler.batchStartBlobs(authorization, scopeId, batchStartBlobsRequest)).build()
}

@ApplicationScoped
class BlobBatchDownloadResource(private val handler: BlobUploadHandler) : BlobBatchDownloadApi {
    override fun batchPresignGet(authorization: String, scopeId: String, batchPresignGetRequest: BatchPresignGetRequest): Response =
        Response.ok(handler.batchPresignGet(authorization, scopeId, batchPresignGetRequest)).build()
}
