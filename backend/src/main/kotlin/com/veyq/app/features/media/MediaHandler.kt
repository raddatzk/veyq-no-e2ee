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

    fun complete(userId: String, scopeId: String, items: List<Pair<String, String>>): CompleteOutcome {
        if (!store.isMember(userId, scopeId)) return CompleteOutcome.NoScope
        return CompleteOutcome.Results(items.map { (uploadId, nodeId) -> completeOne(scopeId, uploadId, nodeId) })
    }

    private fun completeOne(scopeId: String, uploadId: String, nodeId: String): CompleteItemResult {
        val session = store.findSession(scopeId, uploadId)
            ?: return CompleteItemResult(null, null, "upload_not_found")
        val node = store.findNode(scopeId, nodeId)
            ?: return CompleteItemResult(null, null, "node_not_found")
        if (node.kind == NodeEntity.FOLDER) return CompleteItemResult(null, null, "illegal_target")

        val manifest = if (blobStore.canPresign) {
            val keys = (0 until session.chunkCount).map { chunkKey(session.storageKey, it) }
            val sizes = keys.map { blobStore.stat(it) }
            if (sizes.any { it == null } || sizes.filterNotNull().sum() > session.byteSize) {
                blobStore.delete(keys) // declare-then-verify failed: purge the staged objects
                return CompleteItemResult(null, null, "budget_exceeded")
            }
            sizes.filterNotNull()
        } else {
            null // dev store: no transport, trust the declaration (the byte path is e2e-only)
        }
        val (blobId, mediaId) = store.attach(session, nodeId, manifest)
        return CompleteItemResult(blobId, mediaId, null)
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
