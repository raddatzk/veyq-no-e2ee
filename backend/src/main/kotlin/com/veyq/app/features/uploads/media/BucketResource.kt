package com.veyq.app.features.uploads.media

import com.veyq.app.generated.api.MemoryBucketApi
import com.veyq.app.generated.model.CreateBucketRequest
import com.veyq.app.generated.model.UpdateBucketRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response

/** Thin REST entry implementing the generated MemoryBucketApi; delegates to BucketHandler (ADR 0023). */
@ApplicationScoped
class BucketResource(private val handler: BucketHandler) : MemoryBucketApi {

    override fun listBuckets(authorization: String, scopeId: String, nodeId: String): Response =
        Response.ok(handler.listBuckets(authorization, scopeId, nodeId)).build()

    override fun createBucket(authorization: String, scopeId: String, nodeId: String, createBucketRequest: CreateBucketRequest): Response =
        Response.ok(handler.createBucket(authorization, scopeId, nodeId, createBucketRequest)).build()

    override fun renameBucket(authorization: String, scopeId: String, nodeId: String, bucketId: String, updateBucketRequest: UpdateBucketRequest): Response =
        Response.ok(handler.renameBucket(authorization, scopeId, nodeId, bucketId, updateBucketRequest)).build()

    override fun deleteBucket(authorization: String, scopeId: String, nodeId: String, bucketId: String): Response {
        handler.deleteBucket(authorization, scopeId, nodeId, bucketId)
        return Response.noContent().build()
    }
}
