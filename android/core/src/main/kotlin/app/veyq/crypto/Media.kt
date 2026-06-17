package app.veyq.crypto

import org.json.JSONArray
import org.json.JSONObject
import java.net.http.HttpResponse
import java.util.Base64

/**
 * uc.uploads (Android) — ONE upload path, ONE download path for all bytes. Every file becomes a deduplicated
 * "blob": a set of AEAD frames (V1.sealFrame, AAD binds index+total) in the server's BlobStore (never the
 * DB). A small file is one frame, a large one many — both resumable, both opened frame-by-frame coming back.
 * A "media" attaches a stored blob to a Memory/Chapter with typed derivatives (thumbnail/preview), which are
 * themselves ordinary blobs uploaded the same way. The server stays blind: only ciphertext frames, a
 * per-scope dedup token, and low-sensitivity metadata (mime, size, takenAt).
 */
object Media {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()

    // 4 MiB plaintext frames: small files stay one frame, large ones chunk transparently.
    const val DEFAULT_CHUNK_SIZE = 4 * 1024 * 1024

    private val EMPTY = ByteArray(0)

    data class BlobRef(val blobId: String, val totalChunks: Int, val byteSize: Long)
    data class BlobTicket(val status: String, val blobId: String?, val uploadId: String?, val totalChunks: Int)

    /** Per-media sensitive metadata sealed under the content key: capture time + optional GPS location. */
    data class MediaMeta(val takenAt: String, val lat: Double?, val lng: Double?)

    /** Seal {takenAt, lat?, lng?} into a sealedMeta blob (nonce || AEAD) for the create request. */
    fun sealMediaMeta(contentKey: ByteArray, takenAt: String, lat: Double?, lng: Double?): JSONObject {
        val meta = JSONObject().put("takenAt", takenAt)
        if (lat != null) meta.put("lat", lat)
        if (lng != null) meta.put("lng", lng)
        val nonce = V1.randomBytes(12)
        val sealed = nonce + V1.aeadSeal(contentKey, nonce, meta.toString().toByteArray(Charsets.UTF_8), EMPTY)
        return JSONObject().put("suiteId", "v1").put("bytes", enc.encodeToString(sealed))
    }

    /** Decrypt a media item's sealedMeta back to {takenAt, lat?, lng?}. */
    fun decodeMediaMeta(contentKey: ByteArray, media: JSONObject): MediaMeta {
        val sealed = dec.decode(media.getJSONObject("sealedMeta").getString("bytes"))
        val pt = V1.aeadOpen(contentKey, sealed.copyOfRange(0, 12), sealed.copyOfRange(12, sealed.size), EMPTY)
        val obj = JSONObject(String(pt, Charsets.UTF_8))
        return MediaMeta(obj.getString("takenAt"), if (obj.has("lat")) obj.getDouble("lat") else null, if (obj.has("lng")) obj.getDouble("lng") else null)
    }

    private fun send(uri: String, token: String, method: String, body: String? = null): HttpResponse<String> =
        ApiClient.send("", token, uri, method, body)

    // ---- blob layer (the single upload/download path) ---------------------------------------------------

    /** Split a plaintext into fixed-size chunks (always ≥1 so an empty file still has one frame). */
    fun chunkBytes(bytes: ByteArray, chunkSize: Int = DEFAULT_CHUNK_SIZE): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var o = 0
        while (o < bytes.size) { out.add(bytes.copyOfRange(o, minOf(o + chunkSize, bytes.size))); o += chunkSize }
        return if (out.isEmpty()) listOf(ByteArray(0)) else out
    }

    /** The per-scope dedup token for some content (matches what the server keys on — a blind dedup token). */
    fun dedupTokenFor(contentKey: ByteArray, content: ByteArray): String = enc.encodeToString(V1.dedupToken(contentKey, content))

    /** Begin (or dedup) storing a blob. READY → already stored (attach blobId); UPLOADING → push the frames. */
    fun startBlob(apiBase: String, session: Login.UnlockedSession, scopeId: String, dedupToken: String, totalChunks: Int, byteSize: Long): BlobTicket {
        val body = JSONObject().put("dedupToken", dedupToken).put("totalChunks", totalChunks).put("byteSize", byteSize).toString()
        val resp = send("$apiBase/api/scopes/$scopeId/blobs", session.sessionToken, "POST", body)
        if (resp.statusCode() != 200) throw RuntimeException("start blob failed: ${resp.statusCode()}")
        val j = JSONObject(resp.body())
        return BlobTicket(j.getString("status"), j.optString("blobId", null), j.optString("uploadId", null), j.getInt("totalChunks"))
    }

    /** Seal one plaintext chunk into a frame and PUT it (idempotent — re-PUT is the basis of resume). */
    fun putChunk(apiBase: String, session: Login.UnlockedSession, scopeId: String, uploadId: String, index: Int, total: Int, contentKey: ByteArray, plain: ByteArray) {
        val frame = V1.sealFrame(contentKey, index, total, plain, V1.randomBytes(12))
        val body = JSONObject().put("frame", JSONObject().put("suiteId", "v1").put("bytes", enc.encodeToString(frame))).toString()
        val resp = send("$apiBase/api/scopes/$scopeId/blob-uploads/$uploadId/chunks/$index", session.sessionToken, "PUT", body)
        if (resp.statusCode() != 204) throw RuntimeException("put chunk $index failed: ${resp.statusCode()}")
    }

    /** Which chunk indices have landed (drives resume). */
    fun getProgress(apiBase: String, session: Login.UnlockedSession, scopeId: String, uploadId: String): JSONObject {
        val resp = send("$apiBase/api/scopes/$scopeId/blob-uploads/$uploadId", session.sessionToken, "GET")
        if (resp.statusCode() != 200) throw RuntimeException("upload progress failed: ${resp.statusCode()}")
        return JSONObject(resp.body())
    }

    /** Finalize the upload once every chunk has landed; promotes the frames to a content blob. */
    fun completeBlob(apiBase: String, session: Login.UnlockedSession, scopeId: String, uploadId: String): BlobRef {
        val resp = send("$apiBase/api/scopes/$scopeId/blob-uploads/$uploadId/complete", session.sessionToken, "POST")
        if (resp.statusCode() != 200) throw RuntimeException("complete blob failed: ${resp.statusCode()}")
        val j = JSONObject(resp.body())
        return BlobRef(j.getString("blobId"), j.getInt("totalChunks"), j.getLong("byteSize"))
    }

    /**
     * Upload arbitrary content as a deduplicated blob. Dedups first (skips the bytes entirely if the scope
     * already holds them); otherwise opens an upload and pushes only the still-missing frames, so a retried
     * call resumes a half-done upload rather than restarting it.
     */
    fun uploadBlob(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: ByteArray, bytes: ByteArray, chunkSize: Int = DEFAULT_CHUNK_SIZE): BlobRef {
        val chunks = chunkBytes(bytes, chunkSize)
        val ticket = startBlob(apiBase, session, scopeId, dedupTokenFor(contentKey, bytes), chunks.size, bytes.size.toLong())
        if (ticket.status == "READY") return BlobRef(ticket.blobId!!, ticket.totalChunks, bytes.size.toLong())
        val uploadId = ticket.uploadId!!
        val arr = getProgress(apiBase, session, scopeId, uploadId).getJSONArray("receivedChunks")
        val have = (0 until arr.length()).map { arr.getInt(it) }.toSet()
        for (i in chunks.indices) if (i !in have) putChunk(apiBase, session, scopeId, uploadId, i, chunks.size, contentKey, chunks[i])
        return completeBlob(apiBase, session, scopeId, uploadId)
    }

    /** Download a finalized blob: pull each frame, open it (AAD checks position), concatenate the plaintext. */
    fun downloadBlob(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: ByteArray, ref: BlobRef): ByteArray {
        val out = java.io.ByteArrayOutputStream() // accumulate into a growable buffer, not `out += frame` (O(n^2) copying)
        for (i in 0 until ref.totalChunks) {
            val resp = send("$apiBase/api/scopes/$scopeId/blobs/${ref.blobId}/chunks/$i", session.sessionToken, "GET")
            if (resp.statusCode() != 200) throw RuntimeException("get chunk $i failed: ${resp.statusCode()}")
            val frame = dec.decode(JSONObject(resp.body()).getJSONObject("frame").getString("bytes"))
            out.write(V1.openFrame(contentKey, i, ref.totalChunks, frame))
        }
        return out.toByteArray()
    }

    // ---- media layer (attach blobs to a Memory) ---------------------------------------------------------

    /** Attach a stored blob (+ derivatives) to a Memory/Chapter node. The metadata is pre-sealed by the caller. */
    fun createMedia(apiBase: String, session: Login.UnlockedSession, scopeId: String, nodeId: String, originalBlobId: String, sealedMeta: JSONObject, mimeType: String, derivatives: List<Pair<String, String>> = emptyList()): JSONObject {
        val derivJson = JSONArray().apply { derivatives.forEach { put(JSONObject().put("kind", it.first).put("blobId", it.second)) } }
        val json = JSONObject().put("originalBlobId", originalBlobId).put("sealedMeta", sealedMeta).put("mimeType", mimeType).put("derivatives", derivJson).toString()
        val resp = send("$apiBase/api/scopes/$scopeId/nodes/$nodeId/media", session.sessionToken, "POST", json)
        if (resp.statusCode() != 200) throw RuntimeException("create media failed: ${resp.statusCode()}")
        return JSONObject(resp.body())
    }

    /**
     * The full client flow: upload the original bytes (chunked + deduped transparently) and any
     * client-generated thumbnail through the SAME blob path, then attach them as a media. The thumbnail is a
     * typed derivative — no special API.
     */
    fun uploadMedia(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: ByteArray, nodeId: String, bytes: ByteArray, thumb: ByteArray? = null, preview: ByteArray? = null, takenAt: String, mimeType: String, lat: Double? = null, lng: Double? = null, chunkSize: Int = DEFAULT_CHUNK_SIZE): JSONObject {
        val original = uploadBlob(apiBase, session, scopeId, contentKey, bytes, chunkSize)
        val derivatives = mutableListOf<Pair<String, String>>()
        if (thumb != null) {
            val t = uploadBlob(apiBase, session, scopeId, contentKey, thumb, chunkSize)
            derivatives.add("THUMBNAIL" to t.blobId)
        }
        if (preview != null) {
            val p = uploadBlob(apiBase, session, scopeId, contentKey, preview, chunkSize)
            derivatives.add("PREVIEW" to p.blobId)
        }
        val sealedMeta = sealMediaMeta(contentKey, takenAt, lat, lng)
        return createMedia(apiBase, session, scopeId, nodeId, original.blobId, sealedMeta, mimeType, derivatives)
    }

    /**
     * Patch a media item's server-visible highlight flag and/or its (re-sealed) sealedMeta. Both optional;
     * omitted fields are left unchanged. Backs the lightbox favourite toggle + capture-date edit.
     */
    fun updateMedia(apiBase: String, session: Login.UnlockedSession, scopeId: String, nodeId: String, mediaId: String, highlighted: Boolean? = null, sealedMeta: JSONObject? = null): JSONObject {
        val json = JSONObject()
        if (highlighted != null) json.put("highlighted", highlighted)
        if (sealedMeta != null) json.put("sealedMeta", sealedMeta)
        val resp = send("$apiBase/api/scopes/$scopeId/nodes/$nodeId/media/$mediaId", session.sessionToken, "PATCH", json.toString())
        if (resp.statusCode() != 200) throw RuntimeException("update media failed: ${resp.statusCode()}")
        return JSONObject(resp.body())
    }

    /** List a Memory's media in upload order (the client sorts by decrypted takenAt for the gallery). */
    fun listMedia(apiBase: String, session: Login.UnlockedSession, scopeId: String, nodeId: String): List<JSONObject> {
        val resp = send("$apiBase/api/scopes/$scopeId/nodes/$nodeId/media", session.sessionToken, "GET")
        if (resp.statusCode() != 200) throw RuntimeException("list media failed: ${resp.statusCode()}")
        val arr = JSONObject(resp.body()).getJSONArray("media")
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }
}
