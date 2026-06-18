package com.veyq.app.features.uploads.media

import com.veyq.app.generated.model.Blob as ApiBlob
import com.veyq.app.generated.model.CreateBucketRequest
import com.veyq.app.generated.model.MediaBucket as ApiBucket
import com.veyq.app.generated.model.MediaBucketList
import com.veyq.app.generated.model.NodeType
import com.veyq.app.generated.model.UpdateBucketRequest
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.content.MediaBucket
import com.veyq.app.shared.content.MediaBucketRepository
import com.veyq.app.shared.content.MediaRepository
import com.veyq.app.shared.content.NodeRepository
import com.veyq.app.shared.crypto.CryptoSuiteRegistry
import com.veyq.app.shared.security.ScopeAccessGuard
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Contribution buckets (ADR 0023). Each contributor has a per-Memory PRIMARY bucket plus optional SUB-buckets.
 * A member's primary bucket is SYNTHESIZED (id `primary:user:<id>`, name resolved client-side from membership);
 * a guest's primary + everyone's sub-buckets are persisted rows with sealed (operator-blind) names. The server
 * sees the contributor key + structure, never the names. Create/rename/delete + media moves are own-bucket only.
 */
@ApplicationScoped
class BucketHandler(
    private val nodes: NodeRepository,
    private val buckets: MediaBucketRepository,
    private val media: MediaRepository,
    private val guard: ScopeAccessGuard,
    private val suites: CryptoSuiteRegistry,
) {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()

    @Transactional
    fun listBuckets(auth: String?, scopeId: String, nodeId: String): MediaBucketList {
        guard.requireKeyHolder(auth, scopeId)
        requireMemoryNode(scopeId, nodeId)
        val persisted = buckets.listByNodeId(nodeId)
        val items = media.listByNodeId(nodeId)
        val ownerKeys = (items.map { it.createdBy } + persisted.map { it.ownerKey }).filter { it.isNotBlank() }.toSortedSet()
        val out = mutableListOf<ApiBucket>()
        for (ownerKey in ownerKeys) {
            val primaryCount = items.count { it.createdBy == ownerKey && it.bucketId == null }
            val persistedPrimary = persisted.firstOrNull { it.ownerKey == ownerKey && it.isPrimary }
            out += persistedPrimary?.let { toApi(it, primaryCount) }
                ?: ApiBucket(id = "primary:$ownerKey", ownerKey = ownerKey, primary = true, mediaCount = primaryCount, parentBucketId = null, sealedName = null)
            persisted.filter { it.ownerKey == ownerKey && !it.isPrimary }
                .forEach { b -> out += toApi(b, items.count { it.bucketId == b.id }) }
        }
        return MediaBucketList(buckets = out)
    }

    @Transactional
    fun createBucket(auth: String?, scopeId: String, nodeId: String, req: CreateBucketRequest): ApiBucket {
        val uid = guard.requireKeyHolder(auth, scopeId)
        requireMemoryNode(scopeId, nodeId)
        if (!suites.isKnown(req.sealedName.suiteId)) throw ApiException(400, "UNKNOWN_SUITE", "Unknown crypto suite")
        val ownerKey = "user:$uid"
        // A sub-bucket hangs under the caller's (synthesized) primary; the explicit parent, if given, must be it.
        req.parentBucketId?.takeIf { it.isNotBlank() }?.let {
            if (it != "primary:$ownerKey") throw ApiException(403, "NOT_YOUR_BUCKET", "Sub-buckets attach to your own primary")
        }
        val b = MediaBucket().apply {
            id = UUID.randomUUID().toString(); this.scopeId = scopeId; this.nodeId = nodeId
            this.ownerKey = ownerKey; parentBucketId = "primary:$ownerKey"
            sealedName = dec.decode(req.sealedName.bytes); suiteId = req.sealedName.suiteId
            isPrimary = false; createdAt = Instant.now()
        }
        buckets.persist(b)
        return toApi(b, 0)
    }

    @Transactional
    fun renameBucket(auth: String?, scopeId: String, nodeId: String, bucketId: String, req: UpdateBucketRequest): ApiBucket {
        val uid = guard.requireKeyHolder(auth, scopeId)
        if (!suites.isKnown(req.sealedName.suiteId)) throw ApiException(400, "UNKNOWN_SUITE", "Unknown crypto suite")
        val b = ownBucket(scopeId, nodeId, bucketId, "user:$uid")
        b.sealedName = dec.decode(req.sealedName.bytes); b.suiteId = req.sealedName.suiteId
        return toApi(b, media.countByBucketId(b.id).toInt())
    }

    @Transactional
    fun deleteBucket(auth: String?, scopeId: String, nodeId: String, bucketId: String) {
        val uid = guard.requireKeyHolder(auth, scopeId)
        val b = ownBucket(scopeId, nodeId, bucketId, "user:$uid")
        if (b.isPrimary) throw ApiException(400, "PRIMARY_BUCKET", "A primary bucket cannot be deleted")
        media.clearBucket(b.id) // its media fall back to the owner's primary bucket (bucket_id = null)
        buckets.delete(b)
    }

    private fun ownBucket(scopeId: String, nodeId: String, bucketId: String, ownerKey: String): MediaBucket {
        val b = buckets.findById(bucketId) ?: throw ApiException(404, "BUCKET_NOT_FOUND", "Bucket not found")
        if (b.scopeId != scopeId || b.nodeId != nodeId) throw ApiException(404, "BUCKET_NOT_FOUND", "Bucket not found here")
        if (b.ownerKey != ownerKey) throw ApiException(403, "NOT_YOUR_BUCKET", "You can only manage your own buckets")
        return b
    }

    private fun toApi(b: MediaBucket, mediaCount: Int): ApiBucket =
        ApiBucket(
            id = b.id, ownerKey = b.ownerKey, primary = b.isPrimary, mediaCount = mediaCount,
            parentBucketId = b.parentBucketId,
            sealedName = ApiBlob(suiteId = b.suiteId, bytes = enc.encodeToString(b.sealedName)),
        )

    private fun requireMemoryNode(scopeId: String, nodeId: String) {
        val node = nodes.findById(nodeId) ?: throw ApiException(404, "NODE_NOT_FOUND", "Node not found")
        if (node.scopeId != scopeId) throw ApiException(404, "NODE_NOT_FOUND", "Node not found in this scope")
        if (node.type != NodeType.MEMORY && node.type != NodeType.CHAPTER) {
            throw ApiException(400, "NOT_A_MEMORY", "Buckets exist only on a Memory or Chapter")
        }
    }
}
