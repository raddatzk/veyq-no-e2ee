package com.veyq.app.features.media

import com.veyq.app.features.content.NodeEntity
import com.veyq.app.shared.blobstore.BlobStore
import com.veyq.app.shared.blobstore.BlobStore.Companion.chunkKey
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.UUID

/** A presigned URL for one chunk (index in chunk order). */
data class PresignedRef(val index: Int, val url: String)

/** One blob to start: declared shape + the K_blob wrap. */
data class UploadDeclare(val chunkCount: Int, val byteSize: Long, val wrappedBlobKey: String)

/** One derivative upload of a media to complete: its role + the upload session id. */
data class DerivativeDeclare(val kind: String, val uploadId: String)

/** One logical media to complete: the original upload + role-tagged derivative uploads, attached to a node. */
data class CompleteItem(val nodeId: String, val originalUploadId: String, val derivatives: List<DerivativeDeclare>)

/** Per-item result of `start`: success carries the session + presigned PUTs (null when the store can't presign). */
data class UploadItemResult(val uploadId: String?, val presignedPuts: List<PresignedRef>?, val error: String?)

/** Per-item result of `complete`. */
data class CompleteItemResult(val blobId: String?, val mediaId: String?, val error: String?)

/** Per-item result of `presign-download`. */
data class DownloadItemResult(val presignedGets: List<PresignedRef>?, val error: String?)

sealed interface StartOutcome {
    data class Results(val items: List<UploadItemResult>) : StartOutcome
    object NoScope : StartOutcome
}

sealed interface CompleteOutcome {
    data class Results(val items: List<CompleteItemResult>) : CompleteOutcome
    object NoScope : CompleteOutcome
}

sealed interface ListMediaOutcome {
    data class Rows(val rows: List<MediaRow>) : ListMediaOutcome
    object NoNode : ListMediaOutcome
}

sealed interface DownloadOutcome {
    data class Results(val items: List<DownloadItemResult>) : DownloadOutcome
    object NoScope : DownloadOutcome
}

/**
 * Use-case logic for media blobs (en.memory.media, cr-0028). Operator-blind, batch-shaped, per-item results.
 * Scope access = the caller holds a membership (a non-member sees a top-level 404, not a reveal). Direct-to-
 * store: bytes never flow through here — `start` presigns PUTs, `complete` stats the stored objects
 * (declare-then-verify), `download` presigns GETs.
 *
 * Two budget gates (ADR 0013 §3): the **declared** shape is capped at `start` (`budget_exceeded` per item),
 * and the **actual** object sizes are verified against the declaration at `complete` (purge + `budget_exceeded`
 * on excess). The in-memory dev store has no transport, so its `complete` trusts the declaration (manifest
 * null); the byte path is covered by the opt-in store e2e.
 */
@ApplicationScoped
class MediaHandler(
    private val store: MediaStore,
    private val blobStore: BlobStore,
    @param:ConfigProperty(name = "veyq.blob.max-bytes", defaultValue = "2147483648") private val maxBytes: Long,
    @param:ConfigProperty(name = "veyq.blob.max-chunks", defaultValue = "100000") private val maxChunks: Int,
) {

    fun start(userId: String, scopeId: String, declares: List<UploadDeclare>): StartOutcome {
        if (!store.isMember(userId, scopeId)) return StartOutcome.NoScope
        return StartOutcome.Results(declares.map { startOne(scopeId, it) })
    }

    private fun startOne(scopeId: String, d: UploadDeclare): UploadItemResult {
        if (d.chunkCount < 1 || d.chunkCount > maxChunks || d.byteSize < 0 || d.byteSize > maxBytes) {
            return UploadItemResult(uploadId = null, presignedPuts = null, error = "budget_exceeded")
        }
        val storageKey = UUID.randomUUID().toString()
        val uploadId = store.createSession(scopeId, storageKey, d.chunkCount, d.byteSize, d.wrappedBlobKey)
        // The session is metadata; presigned PUTs require a transport-capable store. With the dev store the
        // session still opens (puts = null) — the `directTransport` flag tells real clients not to upload.
        val puts = if (blobStore.canPresign) {
            (0 until d.chunkCount).map { i -> PresignedRef(i, blobStore.presignPut(chunkKey(storageKey, i))!!) }
        } else {
            null
        }
        return UploadItemResult(uploadId = uploadId, presignedPuts = puts, error = null)
    }

    fun complete(userId: String, scopeId: String, items: List<CompleteItem>): CompleteOutcome {
        if (!store.isMember(userId, scopeId)) return CompleteOutcome.NoScope
        return CompleteOutcome.Results(items.map { completeOne(scopeId, it) })
    }

    /**
     * Complete one logical media (original + derivative uploads). The item is **atomic**: the node + every
     * upload session are resolved first, then **every** blob is declare-then-verified, and only if all pass are
     * the blobs + media + derivative rows attached. On a budget failure the item's staged objects are purged.
     */
    private fun completeOne(scopeId: String, item: CompleteItem): CompleteItemResult {
        val node = store.findNode(scopeId, item.nodeId)
            ?: return CompleteItemResult(null, null, "node_not_found")
        if (node.kind == NodeEntity.FOLDER) return CompleteItemResult(null, null, "illegal_target")

        val original = store.findSession(scopeId, item.originalUploadId)
            ?: return CompleteItemResult(null, null, "upload_not_found")
        val derivSessions = item.derivatives.map { d ->
            val s = store.findSession(scopeId, d.uploadId)
                ?: return CompleteItemResult(null, null, "upload_not_found")
            d.kind to s
        }
        val allSessions = listOf(original) + derivSessions.map { it.second }

        // declare-then-verify every blob up front; a single budget failure fails (and purges) the whole item
        val manifests = HashMap<String, List<Long>?>()
        for (session in allSessions) {
            when (val v = verifyBlob(session)) {
                is BlobVerify.Ok -> manifests[session.id] = v.manifest
                BlobVerify.Budget -> {
                    purge(allSessions)
                    return CompleteItemResult(null, null, "budget_exceeded")
                }
            }
        }

        val (blobId, mediaId) = store.attach(
            original, item.nodeId, manifests[original.id],
            derivSessions.map { (kind, s) -> DerivativeAttach(kind, s, manifests[s.id]) },
        )
        return CompleteItemResult(blobId, mediaId, null)
    }

    private sealed interface BlobVerify {
        data class Ok(val manifest: List<Long>?) : BlobVerify
        object Budget : BlobVerify
    }

    /** Stat a session's stored objects against its declaration. Ok carries the manifest (null on the dev store). */
    private fun verifyBlob(session: UploadSessionEntity): BlobVerify {
        if (!blobStore.canPresign) return BlobVerify.Ok(null) // dev store: no transport, trust the declaration
        val keys = (0 until session.chunkCount).map { chunkKey(session.storageKey, it) }
        val sizes = keys.map { blobStore.stat(it) }
        if (sizes.any { it == null } || sizes.filterNotNull().sum() > session.byteSize) return BlobVerify.Budget
        return BlobVerify.Ok(sizes.filterNotNull())
    }

    /** Best-effort purge of every chunk object staged for the item's sessions (after a budget failure). */
    private fun purge(sessions: List<UploadSessionEntity>) {
        if (!blobStore.canPresign) return
        blobStore.delete(sessions.flatMap { s -> (0 until s.chunkCount).map { chunkKey(s.storageKey, it) } })
    }

    fun listMedia(userId: String, scopeId: String, nodeId: String): ListMediaOutcome {
        if (!store.isMember(userId, scopeId)) return ListMediaOutcome.NoNode
        store.findNode(scopeId, nodeId) ?: return ListMediaOutcome.NoNode
        return ListMediaOutcome.Rows(store.listNodeMedia(scopeId, nodeId))
    }

    fun download(userId: String, scopeId: String, blobIds: List<String>): DownloadOutcome {
        if (!store.isMember(userId, scopeId)) return DownloadOutcome.NoScope
        return DownloadOutcome.Results(blobIds.map { downloadOne(scopeId, it) })
    }

    private fun downloadOne(scopeId: String, blobId: String): DownloadItemResult {
        val blob = store.findBlob(scopeId, blobId)
            ?: return DownloadItemResult(presignedGets = null, error = "upload_not_found")
        if (!blobStore.canPresign) return DownloadItemResult(presignedGets = null, error = "store_unavailable")
        val gets = (0 until blob.chunkCount).map { i -> PresignedRef(i, blobStore.presignGet(chunkKey(blob.storageKey, i))!!) }
        return DownloadItemResult(presignedGets = gets, error = null)
    }
}
