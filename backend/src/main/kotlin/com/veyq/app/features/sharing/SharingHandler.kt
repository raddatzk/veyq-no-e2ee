package com.veyq.app.features.sharing

import com.veyq.app.features.content.NodeEntity
import com.veyq.app.features.media.CompleteItem
import com.veyq.app.features.media.CompleteItemResult
import com.veyq.app.features.media.DerivativeDeclare
import com.veyq.app.features.media.MediaHandler
import com.veyq.app.features.media.UploadDeclare
import com.veyq.app.features.media.UploadItemResult
import com.veyq.app.features.spaces.scope.ScopeEntity
import com.veyq.app.shared.blobstore.BlobStore
import com.veyq.app.shared.blobstore.BlobStore.Companion.chunkKey
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant

/** A presigned GET for one chunk (index in chunk order). */
data class PresignRef(val index: Int, val url: String)

/** One contributor upload to complete: the original session + role-tagged derivatives (the shared node is server-set). */
data class ShareCompleteItem(
    val originalUploadId: String,
    val derivatives: List<DerivativeDeclare>,
    val wrappedMeta: String?,
    val wrappedExif: String?,
)

sealed interface CreateShareOutcome {
    data class Created(val shareId: String) : CreateShareOutcome
    /** No such node, or the caller holds no grant for its scope (a 404, no reveal). */
    object NoNode : CreateShareOutcome
    /** The node is not a MEMORY in a **personal** scope (group/tenant sharing deferred, ADR 0029 §6) — a 422. */
    object IllegalScope : CreateShareOutcome
}

sealed interface ResolveShareOutcome {
    data class Found(val share: ShareEntity, val subtree: SubtreeData, val identities: List<ShareIdentityEntity>) : ResolveShareOutcome
    /** Unknown, disabled, or expired — a non-enumerable 404 (identical in every case). */
    object NotFound : ResolveShareOutcome
}

sealed interface CreateIdentityOutcome {
    data class Created(val identityId: String, val contributorToken: String) : CreateIdentityOutcome
    /** Unknown/disabled/expired share — a 404. */
    object NotFound : CreateIdentityOutcome
    /** The share does not accept contributions (not CONTRIBUTOR/UPLOADER) — a 403. */
    object NotContributable : CreateIdentityOutcome
}

sealed interface ShareDeleteOutcome {
    object Done : ShareDeleteOutcome
    /** No such share/media, or the media is not owned by this identity — a non-enumerable 404. */
    object NotFound : ShareDeleteOutcome
    /** The share does not accept contributions, or the contributor token is invalid — a 403. */
    object NotContributable : ShareDeleteOutcome
}

sealed interface ShareUploadOutcome {
    data class Results(val items: List<UploadItemResult>) : ShareUploadOutcome
    object NotFound : ShareUploadOutcome
    /** Not a CONTRIBUTOR share, or the contributor token is invalid — a 403. */
    object NotContributable : ShareUploadOutcome
    /** Per-share rate limit exceeded — a 429. */
    object RateLimited : ShareUploadOutcome
}

sealed interface ShareCompleteOutcome {
    data class Results(val items: List<CompleteItemResult>) : ShareCompleteOutcome
    object NotFound : ShareCompleteOutcome
    object NotContributable : ShareCompleteOutcome
}

sealed interface ShareBlobOutcome {
    data class Found(val gets: List<PresignRef>) : ShareBlobOutcome
    /** Unknown/disabled/expired share, or the blob is not under the shared subtree — a 404. */
    object NotFound : ShareBlobOutcome
    /** The store cannot presign (the in-memory dev store) — a 503. */
    object StoreUnavailable : ShareBlobOutcome
}

sealed interface RevokeShareOutcome {
    object Done : RevokeShareOutcome
    object NotFound : RevokeShareOutcome
}

/**
 * Use-case logic for public viewer shares (cr-0100, ADR 0029). Two authorization surfaces:
 *  - **Curator** (create/list/revoke): authenticated + gated to a **personal-scope MEMORY the caller owns**.
 *  - **Guest** (resolve/blob): unauthenticated, keyed only on share state (`enabled` + `expiresAt`) — a
 *    parallel authz path, never a relaxed scope filter. The blob GET is authorized structurally (the blob is
 *    attached under the shared subtree), and bytes go **direct-from-store** so the operator never learns which
 *    media a viewer opens. A disabled/expired/unknown share is a non-enumerable 404 in every case.
 */
@ApplicationScoped
class SharingHandler(
    private val store: SharingStore,
    private val blobStore: BlobStore,
    private val mediaHandler: MediaHandler,
    private val rateLimiter: ShareRateLimiter,
) {

    fun create(
        userId: String, nodeId: String, role: String, wrap: String, sealedName: String, expiresAt: Instant?,
    ): CreateShareOutcome {
        val node = store.findNode(nodeId) ?: return CreateShareOutcome.NoNode
        if (!store.isMember(userId, node.scopeId)) return CreateShareOutcome.NoNode // no reveal
        // v1: only a MEMORY in a PERSONAL scope may be public-shared (ADR 0029 §6, ADR 0030).
        if (node.kind != NodeEntity.MEMORY) return CreateShareOutcome.IllegalScope
        if (store.scopeKind(node.scopeId) != ScopeEntity.PERSONAL) return CreateShareOutcome.IllegalScope
        val shareId = store.create(userId, node.scopeId, nodeId, role, wrap, sealedName, expiresAt)
        return CreateShareOutcome.Created(shareId)
    }

    fun list(userId: String): List<ShareEntity> = store.listByOwner(userId)

    fun revoke(userId: String, shareId: String): RevokeShareOutcome =
        if (store.revoke(userId, shareId)) RevokeShareOutcome.Done else RevokeShareOutcome.NotFound

    /**
     * Resolve a share for a guest. For a **viewable** role (VIEWER/CONTRIBUTOR) the full sealed subtree + the
     * contributor identities cross; for an **UPLOADER** (write-only drop-box, ADR 0031) the resolve is gated to
     * `media: []` + the **root node only** and **no identities** — the uploader is structurally handed nothing to
     * see (no gallery, no other guests). The `canView` gate is the server-side enforcement of "cannot see others".
     */
    fun resolve(shareId: String): ResolveShareOutcome {
        val share = activeShare(shareId) ?: return ResolveShareOutcome.NotFound
        if (canView(share.role)) {
            return ResolveShareOutcome.Found(share, store.subtree(share.scopeId, share.nodeId), store.listIdentities(shareId))
        }
        // UPLOADER: the minimal capability — no nodes, no media, no identities. The client seals its uploads under
        // `K_mem` (from the share `wrap`) and shows the title (from `sealedName`); it needs nothing from the subtree.
        // Returning even the root node would hand the uploader that node's `K_mem`-decryptable `wrappedMeta`.
        return ResolveShareOutcome.Found(share, SubtreeData(emptyList(), emptyList()), emptyList())
    }

    // --- contributor / uploader (cr-0101, cr-0102) -----------------------------

    /** Name yourself on a contributable share (CONTRIBUTOR/UPLOADER): seal-name in, an anonymous identity + token out. */
    fun createIdentity(shareId: String, sealedDisplayName: String): CreateIdentityOutcome {
        val share = activeShare(shareId) ?: return CreateIdentityOutcome.NotFound
        if (!canUpload(share.role)) return CreateIdentityOutcome.NotContributable
        val (identityId, token) = store.createIdentity(shareId, sealedDisplayName)
        return CreateIdentityOutcome.Created(identityId, token)
    }

    /** Start a contributor upload — gated on the share (contributable + enabled) + a valid token, then presign. */
    fun startUploads(shareId: String, contributorToken: String, declares: List<UploadDeclare>): ShareUploadOutcome {
        val share = activeShare(shareId) ?: return ShareUploadOutcome.NotFound
        if (!canUpload(share.role)) return ShareUploadOutcome.NotContributable
        if (store.findIdentity(shareId, contributorToken) == null) return ShareUploadOutcome.NotContributable
        if (!rateLimiter.allow(shareId)) return ShareUploadOutcome.RateLimited
        return ShareUploadOutcome.Results(mediaHandler.startForScope(share.scopeId, declares))
    }

    /** Complete a contributor upload — attach to the share's own Memory node, stamped with the identity. */
    fun completeUploads(shareId: String, contributorToken: String, items: List<ShareCompleteItem>): ShareCompleteOutcome {
        val share = activeShare(shareId) ?: return ShareCompleteOutcome.NotFound
        if (!canUpload(share.role)) return ShareCompleteOutcome.NotContributable
        val identity = store.findIdentity(shareId, contributorToken) ?: return ShareCompleteOutcome.NotContributable
        val completeItems = items.map {
            CompleteItem(
                nodeId = share.nodeId, // contributors attach to the shared Memory root — server-set, not client-chosen
                originalUploadId = it.originalUploadId,
                derivatives = it.derivatives,
                wrappedMeta = it.wrappedMeta,
                wrappedExif = it.wrappedExif,
            )
        }
        return ShareCompleteOutcome.Results(mediaHandler.completeForNode(share.scopeId, completeItems, identity.id))
    }

    /**
     * Delete one of your own contributions (cr-0102 delete-own). Gated on the share (contributable + enabled) +
     * a valid token; the store authorizes the delete by a **single-row ownership** check (the media's
     * `share_identity_id` equals the token's identity), never gallery filtering, and reclaims its blobs. A
     * foreign/unknown media is a non-enumerable 404.
     */
    fun deleteMedia(shareId: String, contributorToken: String, mediaId: String): ShareDeleteOutcome {
        val share = activeShare(shareId) ?: return ShareDeleteOutcome.NotFound
        if (!canUpload(share.role)) return ShareDeleteOutcome.NotContributable
        val identity = store.findIdentity(shareId, contributorToken) ?: return ShareDeleteOutcome.NotContributable
        val reclaimKeys = store.deleteContributedMedia(share.scopeId, mediaId, identity.id)
            ?: return ShareDeleteOutcome.NotFound
        blobStore.delete(reclaimKeys) // best-effort store purge of the original + derivative objects
        return ShareDeleteOutcome.Done
    }

    fun resolveBlob(shareId: String, blobId: String): ShareBlobOutcome {
        val share = activeShare(shareId) ?: return ShareBlobOutcome.NotFound
        // A write-only role has NO download capability (ADR 0031) — an UPLOADER share serves no blob, even for an
        // in-subtree id. This makes "cannot see" a real server gate, not just id-secrecy (the resolve returns none).
        if (!canView(share.role)) return ShareBlobOutcome.NotFound
        val blob = store.authorizedBlob(share.scopeId, share.nodeId, blobId) ?: return ShareBlobOutcome.NotFound
        if (!blobStore.canPresign) return ShareBlobOutcome.StoreUnavailable
        val gets = (0 until blob.chunkCount).map { i -> PresignRef(i, blobStore.presignGet(chunkKey(blob.storageKey, i))!!) }
        return ShareBlobOutcome.Found(gets)
    }

    /** Roles whose guest resolve serves the sealed gallery (VIEWER/CONTRIBUTOR). UPLOADER is write-only → false. */
    private fun canView(role: String) = role == ShareEntity.VIEWER || role == ShareEntity.CONTRIBUTOR

    /** Roles that accept anonymous contributions (identity + upload + delete-own): CONTRIBUTOR/UPLOADER. */
    private fun canUpload(role: String) = role == ShareEntity.CONTRIBUTOR || role == ShareEntity.UPLOADER

    /** A share that exists, is enabled, and has not expired — else null (the single non-enumerable 404 gate). */
    private fun activeShare(shareId: String): ShareEntity? {
        val share = store.find(shareId) ?: return null
        if (!share.enabled) return null
        val expiry = share.expiresAt
        if (expiry != null && !expiry.isAfter(Instant.now())) return null
        return share
    }
}
