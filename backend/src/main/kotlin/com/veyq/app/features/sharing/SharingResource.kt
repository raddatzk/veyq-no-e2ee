package com.veyq.app.features.sharing

import com.veyq.app.features.media.DerivativeDeclare
import com.veyq.app.features.media.UploadDeclare
import com.veyq.app.generated.api.SharingApi
import com.veyq.app.generated.model.CreateShareIdentityRequest
import com.veyq.app.generated.model.CreateShareRequest
import com.veyq.app.generated.model.ErrorResponse
import com.veyq.app.generated.model.PresignedUrl
import com.veyq.app.generated.model.PublicShare
import com.veyq.app.generated.model.ShareBlobDownload
import com.veyq.app.generated.model.ShareBlobUploadResultItem
import com.veyq.app.generated.model.ShareBlobUploadsRequest
import com.veyq.app.generated.model.ShareBlobUploadsResponse
import com.veyq.app.generated.model.ShareCreated
import com.veyq.app.generated.model.ShareIdentity
import com.veyq.app.generated.model.ShareIdentityCreated
import com.veyq.app.generated.model.ShareMedia
import com.veyq.app.generated.model.ShareMediaBatchRequest
import com.veyq.app.generated.model.ShareMediaBatchResponse
import com.veyq.app.generated.model.ShareMediaDerivative
import com.veyq.app.generated.model.ShareMediaResultItem
import com.veyq.app.generated.model.ShareNode
import com.veyq.app.generated.model.ShareRole
import com.veyq.app.generated.model.ShareSummary
import com.veyq.app.generated.model.NodeKind
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.PermitAll
import jakarta.ws.rs.core.Response
import java.time.ZoneOffset
import java.util.UUID

/**
 * HTTP adapter for public viewer shares (uc.sharing.share-public-viewer, cr-0100). Implements the generated
 * [SharingApi]; thin (ADR 0001) — delegates to [SharingHandler]. The class is `@Authenticated` (curator
 * create/list/revoke); the two **guest** ops (`resolveShare`, `resolveShareBlob`) override with `@PermitAll`
 * so an account-less viewer reaches them — gated only by share state (a non-enumerable 404), never by auth.
 * Operator-blind: only ciphertext wraps + opaque metadata + presigned URLs cross.
 */
@Authenticated
class SharingResource(
    private val handler: SharingHandler,
    private val identity: SecurityIdentity,
) : SharingApi {

    override fun createShare(createShareRequest: CreateShareRequest): Response =
        when (val o = handler.create(
            userId = identity.principal.name,
            nodeId = createShareRequest.nodeId.toString(),
            role = createShareRequest.role.value, // VIEWER | CONTRIBUTOR (cr-0101)
            wrap = createShareRequest.wrap,
            sealedName = createShareRequest.sealedName,
            expiresAt = createShareRequest.expiresAt?.toInstant(),
        )) {
            is CreateShareOutcome.Created -> Response.status(Response.Status.CREATED)
                .entity(ShareCreated(shareId = o.shareId)).build()
            CreateShareOutcome.CapabilityDisabled -> error(422, "capability_disabled")
            CreateShareOutcome.NoNode -> error(Response.Status.NOT_FOUND, "node_not_found")
            CreateShareOutcome.IllegalScope -> error(422, "illegal_scope")
        }

    override fun listShares(nodeId: UUID?): Response {
        val me = identity.principal.name
        // Without nodeId: the caller's own shares. With nodeId: every share on that Memory visible to a scope
        // member (cr-0104) — `mine` tells the UI which the caller may revoke vs merely see / adopt.
        val shares = if (nodeId != null) handler.listForNode(me, nodeId.toString()) else handler.list(me)
        return Response.ok(shares.map { s ->
            ShareSummary(
                shareId = s.id,
                nodeId = UUID.fromString(s.nodeId),
                role = ShareRole.decode(s.role),
                sealedName = s.sealedName,
                enabled = s.enabled,
                mine = s.ownerAccount == me,
                createdAt = s.createdAt.atOffset(ZoneOffset.UTC),
                expiresAt = s.expiresAt?.atOffset(ZoneOffset.UTC),
            )
        }).build()
    }

    override fun revokeShare(shareId: String): Response =
        when (handler.revoke(identity.principal.name, shareId)) {
            RevokeShareOutcome.Done -> Response.noContent().build()
            RevokeShareOutcome.NotFound -> error(Response.Status.NOT_FOUND, "share_not_found")
        }

    override fun reactivateShare(shareId: String): Response =
        when (handler.reactivate(identity.principal.name, shareId)) {
            ReactivateShareOutcome.Done -> Response.noContent().build()
            ReactivateShareOutcome.NotFound -> error(Response.Status.NOT_FOUND, "share_not_found")
        }

    @PermitAll
    override fun resolveShare(shareId: String): Response =
        when (val o = handler.resolve(shareId)) {
            is ResolveShareOutcome.Found -> Response.ok(
                PublicShare(
                    role = ShareRole.decode(o.share.role),
                    wrap = o.share.wrap,
                    sealedName = o.share.sealedName,
                    nodes = o.subtree.nodes.map(::toShareNode),
                    media = o.subtree.media.map(::toShareMedia),
                    identities = o.identities.map { ShareIdentity(identityId = UUID.fromString(it.id), sealedDisplayName = it.sealedDisplayName) },
                ),
            ).build()
            ResolveShareOutcome.NotFound -> error(Response.Status.NOT_FOUND, "share_not_found")
        }

    @PermitAll
    override fun resolveShareBlob(shareId: String, blobId: UUID): Response =
        when (val o = handler.resolveBlob(shareId, blobId.toString())) {
            is ShareBlobOutcome.Found -> Response.ok(
                ShareBlobDownload(presignedGets = o.gets.map { PresignedUrl(index = it.index, url = it.url) }),
            ).build()
            ShareBlobOutcome.NotFound -> error(Response.Status.NOT_FOUND, "share_not_found")
            ShareBlobOutcome.StoreUnavailable -> error(503, "store_unavailable")
        }

    private fun toShareNode(n: ShareNodeRow) = ShareNode(
        id = UUID.fromString(n.id),
        kind = NodeKind.decode(n.kind),
        parentId = n.parentId?.let(UUID::fromString),
        orderIndex = n.orderIndex,
        wrappedContentKey = n.wrappedContentKey,
        wrappedMeta = n.wrappedMeta,
    )

    private fun toShareMedia(m: ShareMediaRow) = ShareMedia(
        nodeId = UUID.fromString(m.nodeId),
        mediaId = UUID.fromString(m.mediaId),
        blobId = UUID.fromString(m.blobId),
        chunkCount = m.chunkCount,
        byteSize = m.byteSize,
        wrappedBlobKey = m.wrappedBlobKey,
        manifest = m.manifest,
        derivatives = m.derivatives.map { d ->
            ShareMediaDerivative(
                kind = ShareMediaDerivative.Kind.valueOf(d.kind),
                blobId = UUID.fromString(d.blobId),
                chunkCount = d.chunkCount,
                byteSize = d.byteSize,
                wrappedBlobKey = d.wrappedBlobKey,
                manifest = d.manifest,
            )
        },
        wrappedMeta = m.wrappedMeta,
        identityId = m.identityId?.let(UUID::fromString),
    )

    // --- contributor guest ops (cr-0101; @PermitAll — account-less, gated only by share state + token) ---

    @PermitAll
    override fun createShareIdentity(shareId: String, createShareIdentityRequest: CreateShareIdentityRequest): Response =
        when (val o = handler.createIdentity(shareId, createShareIdentityRequest.sealedDisplayName)) {
            is CreateIdentityOutcome.Created -> Response.status(Response.Status.CREATED)
                .entity(ShareIdentityCreated(identityId = UUID.fromString(o.identityId), contributorToken = o.contributorToken)).build()
            CreateIdentityOutcome.NotContributable -> error(403, "share_not_contributable")
            CreateIdentityOutcome.NotFound -> error(Response.Status.NOT_FOUND, "share_not_found")
        }

    @PermitAll
    override fun startShareBlobUploads(shareId: String, shareBlobUploadsRequest: ShareBlobUploadsRequest): Response {
        val declares = shareBlobUploadsRequest.items.map { UploadDeclare(it.chunkCount, it.byteSize, it.wrappedBlobKey) }
        return when (val o = handler.startUploads(shareId, shareBlobUploadsRequest.contributorToken, declares)) {
            is ShareUploadOutcome.Results -> Response.ok(
                ShareBlobUploadsResponse(
                    results = o.items.map {
                        ShareBlobUploadResultItem(
                            uploadId = it.uploadId?.let(UUID::fromString),
                            presignedPuts = it.presignedPuts?.map { p -> PresignedUrl(index = p.index, url = p.url) },
                            error = it.error,
                        )
                    },
                ),
            ).build()
            ShareUploadOutcome.NotContributable -> error(403, "share_not_contributable")
            ShareUploadOutcome.NotFound -> error(Response.Status.NOT_FOUND, "share_not_found")
            ShareUploadOutcome.RateLimited -> error(429, "rate_limited")
        }
    }

    @PermitAll
    override fun completeShareMedia(shareId: String, shareMediaBatchRequest: ShareMediaBatchRequest): Response {
        val items = shareMediaBatchRequest.items.map { item ->
            ShareCompleteItem(
                originalUploadId = item.originalUploadId.toString(),
                derivatives = item.derivatives.orEmpty().map { DerivativeDeclare(it.kind.value, it.uploadId.toString()) },
                wrappedMeta = item.wrappedMeta,
                wrappedExif = item.wrappedExif,
            )
        }
        return when (val o = handler.completeUploads(shareId, shareMediaBatchRequest.contributorToken, items)) {
            is ShareCompleteOutcome.Results -> Response.ok(
                ShareMediaBatchResponse(
                    results = o.items.map { ShareMediaResultItem(blobId = it.blobId?.let(UUID::fromString), mediaId = it.mediaId?.let(UUID::fromString), error = it.error) },
                ),
            ).build()
            ShareCompleteOutcome.NotContributable -> error(403, "share_not_contributable")
            ShareCompleteOutcome.NotFound -> error(Response.Status.NOT_FOUND, "share_not_found")
        }
    }

    @PermitAll
    override fun deleteShareMedia(shareId: String, mediaId: UUID, xContributorToken: String): Response =
        when (handler.deleteMedia(shareId, xContributorToken, mediaId.toString())) {
            ShareDeleteOutcome.Done -> Response.noContent().build()
            ShareDeleteOutcome.NotContributable -> error(403, "share_not_contributable")
            ShareDeleteOutcome.NotFound -> error(Response.Status.NOT_FOUND, "share_media_not_found")
        }

    private fun error(status: Response.Status, code: String): Response =
        Response.status(status).entity(ErrorResponse(code = code)).build()

    private fun error(status: Int, code: String): Response =
        Response.status(status).entity(ErrorResponse(code = code)).build()
}
