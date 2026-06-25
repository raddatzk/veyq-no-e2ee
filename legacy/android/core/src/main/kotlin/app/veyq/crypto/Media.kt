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

    /** Per-media sensitive metadata sealed under the content key: capture time, optional GPS location, and a
     *  best-effort set of EXIF camera details (operator-blind — sealed with everything else). */
    data class MediaMeta(
        val takenAt: String, val lat: Double?, val lng: Double?,
        val cameraMake: String? = null, val cameraModel: String? = null, val lens: String? = null,
        val width: Int? = null, val height: Int? = null, val fileSize: Int? = null,
        val exif: Map<String, String>? = null,
    )

    /** Seal a MediaMeta into a sealedMeta blob (nonce || AEAD) for the create request. */
    fun sealMediaMeta(contentKey: ByteArray, meta: MediaMeta): JSONObject {
        val o = JSONObject().put("takenAt", meta.takenAt)
        meta.lat?.let { o.put("lat", it) }; meta.lng?.let { o.put("lng", it) }
        meta.cameraMake?.let { o.put("cameraMake", it) }; meta.cameraModel?.let { o.put("cameraModel", it) }
        meta.lens?.let { o.put("lens", it) }
        meta.width?.let { o.put("width", it) }; meta.height?.let { o.put("height", it) }; meta.fileSize?.let { o.put("fileSize", it) }
        meta.exif?.takeIf { it.isNotEmpty() }?.let { m -> o.put("exif", JSONObject().also { j -> m.forEach { (k, v) -> j.put(k, v) } }) }
        val nonce = V1.randomBytes(12)
        val sealed = nonce + V1.aeadSeal(contentKey, nonce, o.toString().toByteArray(Charsets.UTF_8), EMPTY)
        return JSONObject().put("suiteId", "v1").put("bytes", enc.encodeToString(sealed))
    }

    fun sealMediaMeta(contentKey: ByteArray, takenAt: String, lat: Double?, lng: Double?): JSONObject =
        sealMediaMeta(contentKey, MediaMeta(takenAt, lat, lng))

    /** Decrypt a media item's sealedMeta back to its fields (best-effort EXIF map included). */
    fun decodeMediaMeta(contentKey: ByteArray, media: JSONObject): MediaMeta {
        val sealed = dec.decode(media.getJSONObject("sealedMeta").getString("bytes"))
        val pt = V1.aeadOpen(contentKey, sealed.copyOfRange(0, 12), sealed.copyOfRange(12, sealed.size), EMPTY)
        val o = JSONObject(String(pt, Charsets.UTF_8))
        val exif = o.optJSONObject("exif")?.let { j -> j.keys().asSequence().associateWith { j.getString(it) } }
        return MediaMeta(
            o.getString("takenAt"),
            if (o.has("lat")) o.getDouble("lat") else null, if (o.has("lng")) o.getDouble("lng") else null,
            o.optString("cameraMake", null), o.optString("cameraModel", null), o.optString("lens", null),
            if (o.has("width")) o.getInt("width") else null, if (o.has("height")) o.getInt("height") else null,
            if (o.has("fileSize")) o.getInt("fileSize") else null, exif,
        )
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

    private val rawClient = java.net.http.HttpClient.newHttpClient()

    // Direct-to-store only (ADR 0021): every byte travels client↔object-store via short-lived presigned URLs;
    // the backend handles only the control plane (batch start/finalize/presign). The store is blind (AEAD
    // ciphertext), so a presigned URL discloses nothing. There is no proxied fallback — the store MUST presign.

    /** One result of a batch-start: a dedup hit (READY), an open upload (UPLOADING) with per-chunk presigned PUT
     *  URLs for the still-missing chunks, or a per-item ERROR. */
    data class BatchStart(
        val status: String, val blobId: String?, val uploadId: String?, val totalChunks: Int?,
        val receivedChunks: List<Int>, val presignedPuts: Map<Int, String>, val errorCode: String?,
    )

    /** A blob's presigned GET URLs (direct-from-store download), keyed by chunk index. */
    data class PresignedBlob(val blobId: String, val suiteId: String, val chunks: Map<Int, String>)

    /** Build one batch-start descriptor; pass [uploadId] to resume an existing open upload. */
    fun startItem(dedupToken: String, totalChunks: Int, byteSize: Long, uploadId: String? = null, suiteId: String = "v1"): JSONObject =
        JSONObject().put("dedupToken", dedupToken).put("totalChunks", totalChunks).put("byteSize", byteSize).put("suiteId", suiteId)
            .also { if (uploadId != null) it.put("uploadId", uploadId) }

    /** ONE round-trip to dedup/open (and presign) many blobs. Per-item best-effort. */
    fun batchStartBlobs(apiBase: String, session: Login.UnlockedSession, scopeId: String, items: List<JSONObject>): List<BatchStart> {
        val body = JSONObject().put("items", JSONArray(items)).toString()
        val resp = send("$apiBase/api/scopes/$scopeId/blob-uploads-batch", session.sessionToken, "POST", body)
        if (resp.statusCode() != 200) throw RuntimeException("batch start failed: ${resp.statusCode()}")
        val arr = JSONObject(resp.body()).getJSONArray("results")
        return (0 until arr.length()).map { parseBatchStart(arr.getJSONObject(it)) }
    }

    private fun parseBatchStart(j: JSONObject): BatchStart {
        val received = j.optJSONArray("receivedChunks")?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: emptyList()
        val presigned = HashMap<Int, String>()
        j.optJSONArray("presignedPuts")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { presigned[it.getInt("index")] = it.getString("url") } }
        return BatchStart(j.getString("status"), j.optString("blobId", null), j.optString("uploadId", null), if (j.has("totalChunks")) j.getInt("totalChunks") else null, received, presigned, j.optString("errorCode", null))
    }

    /** ONE round-trip to finalize many items (complete each item's uploads + attach as media). Returns the
     *  per-item result objects ({ok, media?, errorCode?}). */
    fun batchFinalizeMedia(apiBase: String, session: Login.UnlockedSession, scopeId: String, nodeId: String, items: List<JSONObject>): List<JSONObject> {
        val body = JSONObject().put("items", JSONArray(items)).toString()
        val resp = send("$apiBase/api/scopes/$scopeId/nodes/$nodeId/media-batch", session.sessionToken, "POST", body)
        if (resp.statusCode() != 200) throw RuntimeException("batch finalize failed: ${resp.statusCode()}")
        val arr = JSONObject(resp.body()).getJSONArray("results")
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    /** ONE round-trip to presign GET URLs for many blobs (a whole gallery page); keyed by blobId. */
    fun batchPresignGet(apiBase: String, session: Login.UnlockedSession, scopeId: String, blobIds: List<String>): Map<String, PresignedBlob> {
        val body = JSONObject().put("blobIds", JSONArray(blobIds)).toString()
        val resp = send("$apiBase/api/scopes/$scopeId/blob-downloads-batch", session.sessionToken, "POST", body)
        if (resp.statusCode() != 200) throw RuntimeException("batch presign failed: ${resp.statusCode()}")
        val arr = JSONObject(resp.body()).getJSONArray("results")
        val out = HashMap<String, PresignedBlob>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val chunks = HashMap<Int, String>()
            val ca = o.getJSONArray("chunks")
            for (k in 0 until ca.length()) ca.getJSONObject(k).let { chunks[it.getInt("index")] = it.getString("url") }
            out[o.getString("blobId")] = PresignedBlob(o.getString("blobId"), o.getString("suiteId"), chunks)
        }
        return out
    }

    /** PUT a sealed frame's raw bytes straight to a presigned object-store URL (no auth header, no JSON wrapper). */
    internal fun putPresigned(url: String, frame: ByteArray, index: Int) {
        val req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
            .PUT(java.net.http.HttpRequest.BodyPublishers.ofByteArray(frame)).build()
        val resp = rawClient.send(req, HttpResponse.BodyHandlers.discarding())
        if (resp.statusCode() !in 200..299) throw RuntimeException("presigned put chunk $index failed: ${resp.statusCode()}")
    }

    /** Fetch one presigned blob directly from the store, open each frame (AAD checks position), reassemble.
     *  Public so a gallery can presign a whole page in ONE round-trip then parallel-fetch each blob. */
    fun fetchBlobFrames(contentKey: ByteArray, ref: BlobRef, presigned: PresignedBlob): ByteArray {
        if (presigned.chunks.size != ref.totalChunks) throw RuntimeException("direct-to-store download unavailable for ${ref.blobId}")
        val out = java.io.ByteArrayOutputStream() // growable buffer, not `out += frame` (O(n^2) copying)
        for (i in 0 until ref.totalChunks) {
            val url = presigned.chunks[i] ?: throw RuntimeException("missing presigned url for chunk $i")
            val req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).GET().build()
            val r = rawClient.send(req, HttpResponse.BodyHandlers.ofByteArray())
            if (r.statusCode() !in 200..299) throw RuntimeException("presigned get chunk $i failed: ${r.statusCode()}")
            out.write(V1.openFrame(contentKey, i, ref.totalChunks, r.body()))
        }
        return out.toByteArray()
    }

    /** Download a finalized blob directly from the store (presign + sequential GET), open + concatenate frames. */
    fun downloadBlob(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: ByteArray, ref: BlobRef): ByteArray {
        val p = batchPresignGet(apiBase, session, scopeId, listOf(ref.blobId))[ref.blobId]
            ?: throw RuntimeException("blob ${ref.blobId} not available")
        return fetchBlobFrames(contentKey, ref, p)
    }

    /** Download many blobs for a gallery page: ONE batch-presign-get, then each blob fetched direct from the store. */
    fun downloadBlobsBatch(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: ByteArray, refs: List<BlobRef>): Map<String, ByteArray> {
        val presigned = batchPresignGet(apiBase, session, scopeId, refs.map { it.blobId })
        val out = HashMap<String, ByteArray>()
        for (ref in refs) {
            val p = presigned[ref.blobId] ?: throw RuntimeException("blob ${ref.blobId} not available")
            out[ref.blobId] = fetchBlobFrames(contentKey, ref, p)
        }
        return out
    }

    // ---- media layer (attach blobs to a Memory) ---------------------------------------------------------

    /**
     * The full client flow for ONE photo, batched + direct-to-store: the original and any client-generated
     * thumbnail/preview are all blobs uploaded the SAME way. ONE batch-start dedups/opens every blob and
     * presigns its missing chunks; the client seals each frame and PUTs it DIRECTLY to the store; ONE
     * batch-finalize completes the uploads + attaches the medium. Returns the created media item.
     */
    fun uploadMedia(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: ByteArray, nodeId: String, bytes: ByteArray, thumb: ByteArray? = null, preview: ByteArray? = null, takenAt: String, mimeType: String, lat: Double? = null, lng: Double? = null, meta: MediaMeta? = null, chunkSize: Int = DEFAULT_CHUNK_SIZE): JSONObject {
        data class Plan(val kind: String, val chunks: List<ByteArray>, val bytes: ByteArray)
        val plan = mutableListOf(Plan("original", chunkBytes(bytes, chunkSize), bytes))
        if (thumb != null) plan.add(Plan("THUMBNAIL", chunkBytes(thumb, chunkSize), thumb))
        if (preview != null) plan.add(Plan("PREVIEW", chunkBytes(preview, chunkSize), preview))

        // 1. ONE batch-start: dedup or open each blob, presigning every missing chunk.
        val starts = batchStartBlobs(apiBase, session, scopeId, plan.map { startItem(dedupTokenFor(contentKey, it.bytes), it.chunks.size, it.bytes.size.toLong()) })

        // 2. Seal + PUT every still-missing frame DIRECTLY to the store.
        for ((bi, s) in starts.withIndex()) {
            if (s.status == "ERROR") throw RuntimeException("upload start failed (${plan[bi].kind}): ${s.errorCode}")
            if (s.status != "UPLOADING") continue
            val have = s.receivedChunks.toSet()
            val chunks = plan[bi].chunks
            for (i in chunks.indices) if (i !in have) {
                val url = s.presignedPuts[i] ?: throw RuntimeException("direct-to-store unavailable: no upload URL for chunk $i")
                putPresigned(url, V1.sealFrame(contentKey, i, chunks.size, chunks[i], V1.randomBytes(12)), i)
            }
        }

        // 3. ONE batch-finalize: complete each blob's upload + attach the medium with its derivatives.
        fun refFor(s: BatchStart): JSONObject = if (s.status == "READY") JSONObject().put("blobId", s.blobId) else JSONObject().put("uploadId", s.uploadId)
        val derivJson = JSONArray()
        for ((i, p) in plan.withIndex()) if (i > 0) derivJson.put(JSONObject().put("kind", p.kind).put("blob", refFor(starts[i])))
        val sealedMeta = sealMediaMeta(contentKey, meta ?: MediaMeta(takenAt, lat, lng))
        val item = JSONObject().put("original", refFor(starts[0])).put("derivatives", derivJson).put("sealedMeta", sealedMeta).put("mimeType", mimeType)
        val results = batchFinalizeMedia(apiBase, session, scopeId, nodeId, listOf(item))
        val r = results.firstOrNull() ?: throw RuntimeException("media finalize returned no result")
        if (!r.optBoolean("ok", false)) throw RuntimeException("media finalize failed: ${r.optString("errorCode", "unknown")}")
        return r.getJSONObject("media")
    }

    /**
     * Patch a media item's server-visible highlight flag and/or its (re-sealed) sealedMeta. Both optional;
     * omitted fields are left unchanged. Backs the lightbox favourite toggle + capture-date edit.
     */
    fun updateMedia(apiBase: String, session: Login.UnlockedSession, scopeId: String, nodeId: String, mediaId: String, highlighted: Boolean? = null, sealedMeta: JSONObject? = null, bucketId: String? = null): JSONObject {
        val json = JSONObject()
        if (highlighted != null) json.put("highlighted", highlighted)
        if (sealedMeta != null) json.put("sealedMeta", sealedMeta)
        if (bucketId != null) json.put("bucketId", bucketId)
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
