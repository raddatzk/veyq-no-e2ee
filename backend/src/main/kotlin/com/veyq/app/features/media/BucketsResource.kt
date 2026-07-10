package com.veyq.app.features.media

import com.veyq.app.generated.api.BucketsApi
import com.veyq.app.generated.model.BucketListResponse
import com.veyq.app.generated.model.BucketSummary
import com.veyq.app.generated.model.CreateBucketRequest
import com.veyq.app.generated.model.ErrorResponse
import com.veyq.app.generated.model.MoveMediaRequest
import com.veyq.app.generated.model.RenameBucketRequest
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.core.Response
import java.util.UUID

/**
 * HTTP adapter for uploader buckets (en.memory.buckets, ADR 0034, cr-0113). Implements the generated [BucketsApi];
 * thin (ADR 0001) — delegates to [MediaHandler]. `@Authenticated`; the caller (the `SecurityIdentity` principal)
 * must hold a grant for the scope (a non-member gets a 404). Member-only: the caller's owner_key is
 * `user:{userId}`, so create/rename/delete/move are gated to their OWN buckets (`owner_key == caller`).
 * Operator-blind — opaque owner_key + bucket structure + sealed names cross, never a cleartext name or content.
 */
@Authenticated
class BucketsResource(
    private val handler: MediaHandler,
    private val identity: SecurityIdentity,
) : BucketsApi {

    override fun listNodeBuckets(scopeId: UUID, nodeId: UUID): Response =
        when (val o = handler.listBuckets(identity.principal.name, scopeId.toString(), nodeId.toString())) {
            is ListBucketsOutcome.Ok -> Response.ok(BucketListResponse(buckets = o.buckets.map(::toSummary))).build()
            ListBucketsOutcome.NoNode -> error(Response.Status.NOT_FOUND, "node_not_found")
        }

    override fun createBucket(scopeId: UUID, nodeId: UUID, createBucketRequest: CreateBucketRequest): Response =
        when (val o = handler.createBucket(identity.principal.name, scopeId.toString(), nodeId.toString(), createBucketRequest.sealedName)) {
            is CreateBucketOutcome.Ok -> Response.ok(toSummary(o.bucket)).build()
            CreateBucketOutcome.NoNode -> error(Response.Status.NOT_FOUND, "node_not_found")
        }

    override fun renameBucket(scopeId: UUID, nodeId: UUID, bucketId: UUID, renameBucketRequest: RenameBucketRequest): Response =
        when (val o = handler.renameBucket(identity.principal.name, scopeId.toString(), nodeId.toString(), bucketId.toString(), renameBucketRequest.sealedName)) {
            is RenameBucketOutcome.Ok -> Response.ok(toSummary(o.bucket)).build()
            RenameBucketOutcome.Forbidden -> error(Response.Status.FORBIDDEN, "illegal_target")
            RenameBucketOutcome.NoBucket -> error(Response.Status.NOT_FOUND, "bucket_not_found")
        }

    override fun deleteBucket(scopeId: UUID, nodeId: UUID, bucketId: UUID): Response =
        when (handler.deleteBucket(identity.principal.name, scopeId.toString(), nodeId.toString(), bucketId.toString())) {
            DeleteBucketOutcome.Done -> Response.noContent().build()
            DeleteBucketOutcome.Forbidden -> error(Response.Status.FORBIDDEN, "illegal_target")
            DeleteBucketOutcome.NoNode -> error(Response.Status.NOT_FOUND, "node_not_found")
        }

    override fun moveBucketMedia(scopeId: UUID, nodeId: UUID, moveMediaRequest: MoveMediaRequest): Response =
        when (
            handler.moveMedia(
                identity.principal.name, scopeId.toString(), nodeId.toString(),
                moveMediaRequest.mediaIds.map { it.toString() }, moveMediaRequest.targetBucketId?.toString(),
            )
        ) {
            MoveMediaOutcome.Done -> Response.noContent().build()
            MoveMediaOutcome.Forbidden -> error(Response.Status.FORBIDDEN, "illegal_target")
            MoveMediaOutcome.NoNode -> error(Response.Status.NOT_FOUND, "node_not_found")
        }

    private fun toSummary(b: BucketRow) = BucketSummary(
        bucketId = UUID.fromString(b.bucketId),
        ownerKey = b.ownerKey,
        isPrimary = b.isPrimary,
        mediaCount = b.mediaCount,
        parentBucketId = b.parentBucketId?.let(UUID::fromString),
        sealedName = b.sealedName,
    )

    private fun error(status: Response.Status, code: String): Response =
        Response.status(status).entity(ErrorResponse(code = code)).build()
}
