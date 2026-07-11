package com.veyq.client

import com.veyq.crypto.V1
import java.util.Base64
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * cr-0028 + cr-0048 — en.memory.media + en.memory.derivatives (android) data-layer tests, the twin of Apple's
 * `MediaTests`. A stateful, **derivative-aware** [MediaDispatcher] serves the batch control plane (several
 * blobs per media, role-tagged read-back) with `mem://…` presigned URLs, and an in-memory [FakeBlobTransport]
 * stores PUT bodies + replays them on GET — so addMedia → openMedia round-trips the real native v1 chunk
 * seal/open + the per-blob K_blob wrap (under K_mem) without a network or MinIO. The lossy pixel stage is behind
 * an injected [StubDeriver] (parity by property, not bytes), so the wiring (dimension math, role/batch assembly,
 * per-blob wraps, tier selection) is unit-tested here with **no `android.graphics` import**; the real Bitmap
 * decode is a manual emulator round-trip. Operator-blind: the "store" only holds ciphertext frames; the backend
 * dispatcher only ciphertext wraps + opaque metadata + the role tags.
 */
class MediaTest {
    private val scopeId = "11111111-1111-1111-1111-111111111111"
    private val nodeId = "22222222-2222-2222-2222-222222222222"
    private lateinit var server: MockWebServer

    @BeforeTest fun setUp() { server = MockWebServer() }
    @AfterTest fun tearDown() { server.shutdown() }
    private fun baseUrl(): String = server.url("").toString().trimEnd('/')

    private fun client(d: MediaDispatcher, blobs: BlobTransport, deriver: MediaDeriver = StubDeriver()): VeyqClient {
        server.dispatcher = d
        server.start()
        val session = Session().apply { set("access-1", "refresh-1", "fam") }
        return VeyqClient(VeyqInstance(baseUrl()), session = session,
                          deviceSecurity = InMemoryDeviceSecurityStore(), blobTransport = blobs, mediaDeriver = deriver)
    }

    // --- longest-edge target math (pure) ---

    @Test fun scaleToMaxEdgeCapsLongestEdgePreservingAspect() {
        assertEquals(400 to 200, scaleToMaxEdge(4000, 2000, 400)) // landscape
        assertEquals(200 to 400, scaleToMaxEdge(2000, 4000, 400)) // portrait
        assertEquals(400 to 400, scaleToMaxEdge(1000, 1000, 400)) // square
    }

    @Test fun scaleToMaxEdgeNeverUpscalesAndKeepsEachSideAtLeastOnePx() {
        assertEquals(300 to 150, scaleToMaxEdge(300, 150, 400)) // already within cap
        assertEquals(400 to 400, scaleToMaxEdge(400, 400, 400)) // exactly at cap
        assertEquals(400 to 1, scaleToMaxEdge(2048, 3, 400))    // rounds to ≥ 1px
        assertEquals(0 to 0, scaleToMaxEdge(0, 0, 400))         // degenerate
    }

    @Test fun tierSpecsArePinnedFromLegacy() {
        assertEquals(DerivativeSpec(512, 80), DerivativeSpec.THUMBNAIL)
        assertEquals(DerivativeSpec(2048, 85), DerivativeSpec.PREVIEW)
    }

    // --- add-media: derivative generation + role/batch assembly ---

    @Test fun imageUploadsOriginalThumbnailPreviewInOneBatchRoleTagged() {
        val dispatcher = MediaDispatcher()
        val deriver = StubDeriver()
        val c = client(dispatcher, FakeBlobTransport(), deriver)

        val (blobId, mediaId) = c.addMedia(scopeId, nodeId, ByteArray(32) { 7 }, ByteArray(64) { 5 }, "image/png")
        assertTrue(blobId.isNotEmpty() && mediaId.isNotEmpty())

        assertEquals(3, dispatcher.startItemCount)                                 // original + 2 derivatives, one batch
        assertEquals(listOf(512 to 80, 2048 to 85), deriver.imageCalls)            // pinned tier specs (thumbnail 512, cr-0070)
        assertEquals(0, deriver.posterCalls)
        assertEquals(listOf("THUMBNAIL", "PREVIEW"), dispatcher.lastCompleteDerivativeKinds)

        val item = c.listMedia(scopeId, nodeId).single()
        assertEquals(listOf(MediaDerivativeKindName.THUMBNAIL, MediaDerivativeKindName.PREVIEW), item.derivatives.map { it.kind.value })
        assertTrue(item.gridRef.blobId != item.original.blobId)                    // grid is never the original
        assertTrue(item.gridRef.blobId != item.lightboxRef.blobId)
    }

    @Test fun videoDerivesOnePosterReusedForBothTiers() {
        val dispatcher = MediaDispatcher()
        val deriver = StubDeriver()
        val memoryKey = ByteArray(32) { 9 }
        val c = client(dispatcher, FakeBlobTransport(), deriver)

        c.addMedia(scopeId, nodeId, memoryKey, ByteArray(40) { 3 }, "video/mp4")

        assertEquals(1, deriver.posterCalls)                                       // decoded once
        assertTrue(deriver.imageCalls.isEmpty())
        assertEquals(3, dispatcher.startItemCount)                                 // original + thumbnail + preview (same poster)
        assertEquals(listOf("THUMBNAIL", "PREVIEW"), dispatcher.lastCompleteDerivativeKinds)

        val item = c.listMedia(scopeId, nodeId).single()
        assertContentEquals(StubDeriver.POSTER, c.openMedia(scopeId, memoryKey, item, VeyqClient.MediaTier.GRID))
        assertContentEquals(StubDeriver.POSTER, c.openMedia(scopeId, memoryKey, item, VeyqClient.MediaTier.LIGHTBOX))
    }

    @Test fun nonImageNonVideoAttachesOriginalOnly() {
        val dispatcher = MediaDispatcher()
        val deriver = StubDeriver()
        val c = client(dispatcher, FakeBlobTransport(), deriver)

        c.addMedia(scopeId, nodeId, ByteArray(32) { 1 }, byteArrayOf(1, 2, 3), "application/pdf")

        assertEquals(0, deriver.imageCalls.size + deriver.posterCalls)
        assertEquals(1, dispatcher.startItemCount)                                 // original only
        assertEquals(emptyList(), dispatcher.lastCompleteDerivativeKinds)          // no role tags
        val item = c.listMedia(scopeId, nodeId).single()
        assertTrue(item.derivatives.isEmpty())
        assertEquals(item.original.blobId, item.gridRef.blobId)                    // tiers fall back to the original
        assertEquals(item.original.blobId, item.lightboxRef.blobId)
    }

    @Test fun storeUnavailableWhenNoPresign() {
        val dispatcher = MediaDispatcher().apply { presign = false }
        val c = client(dispatcher, FakeBlobTransport())
        assertFailsWith<VeyqError.StoreUnavailable> {
            c.addMedia(scopeId, nodeId, ByteArray(32) { 1 }, ByteArray(10) { 9 }, "application/octet-stream")
        }
    }

    // --- read-back: operator-blind round-trip + per-blob wraps + tier selection ---

    @Test fun originalRoundTripsThroughChunkedAEADUnderItsOwnKBlob() {
        val dispatcher = MediaDispatcher()
        val blobs = FakeBlobTransport()
        val c = client(dispatcher, blobs)
        val memoryKey = ByteArray(32) { 7 }

        // 600 KiB ⇒ 3 chunks at CHUNK_SIZE = 256 KiB (original-only: octet-stream)
        val data = ByteArray(600 * 1024) { ((it * 31 + 7) and 0xff).toByte() }
        c.addMedia(scopeId, nodeId, memoryKey, data, "application/octet-stream")

        // control plane: declared 3 chunks; the store holds 3 ciphertext frames (each = chunk + nonce + tag)
        assertEquals(3, dispatcher.lastChunkCount)
        assertEquals(3, blobs.objects.size)
        val firstFrame = blobs.objects.values.first { it.size == VeyqClient.CHUNK_SIZE + 12 + 16 }
        assertTrue(!firstFrame.copyOfRange(0, VeyqClient.CHUNK_SIZE).contentEquals(data.copyOfRange(0, VeyqClient.CHUNK_SIZE)))

        // the wrap the backend saw opens back to a real 32-byte K_blob under K_mem with the 'blob-key' context
        val blobKey = V1.symUnwrap(memoryKey, Base64.getDecoder().decode(dispatcher.lastWrap!!), "blob-key")
        assertEquals(32, blobKey.size)

        val item = c.listMedia(scopeId, nodeId).single()
        assertContentEquals(data, c.openMedia(scopeId, memoryKey, item))
    }

    @Test fun eachTierIsSealedUnderItsOwnKBlobAndPicksTheRightBytes() {
        val dispatcher = MediaDispatcher()
        val c = client(dispatcher, FakeBlobTransport())
        val memoryKey = ByteArray(32) { 4 }
        val original = ByteArray(70) { 8 }

        c.addMedia(scopeId, nodeId, memoryKey, original, "image/jpeg")

        val item = c.listMedia(scopeId, nodeId).single()
        // every tier keeps its OWN K_blob — the three wraps are distinct (per-blob keys, enabler decision 1)
        val wraps = setOf(item.original.wrappedBlobKey, item.gridRef.wrappedBlobKey, item.lightboxRef.wrappedBlobKey)
        assertEquals(3, wraps.size)

        // grid = thumbnail bytes, lightbox = preview bytes, original = the pristine bytes
        assertContentEquals(StubDeriver.image(512), c.openMedia(scopeId, memoryKey, item, VeyqClient.MediaTier.GRID))
        assertContentEquals(StubDeriver.image(2048), c.openMedia(scopeId, memoryKey, item, VeyqClient.MediaTier.LIGHTBOX))
        assertContentEquals(original, c.openMedia(scopeId, memoryKey, item, VeyqClient.MediaTier.ORIGINAL))
    }

    // --- doubles ---

    /** A stub deriver: returns tiny distinct "JPEG" bytes tagged by the requested longest edge (and a fixed
     *  poster marker), and records calls — so the wiring is tested without a real pixel decode. */
    private class StubDeriver : MediaDeriver {
        val imageCalls = mutableListOf<Pair<Int, Int>>()
        var posterCalls = 0
        override fun deriveImage(data: ByteArray, maxEdge: Int, quality: Int): ByteArray {
            imageCalls.add(maxEdge to quality); return image(maxEdge)
        }
        override fun deriveVideoPoster(data: ByteArray, quality: Int): ByteArray { posterCalls++; return POSTER }
        companion object {
            fun image(maxEdge: Int): ByteArray = "img:$maxEdge".toByteArray()
            val POSTER: ByteArray = "poster".toByteArray()
        }
    }

    /** In-memory object store for the byte transport: PUT stores the body under the URL, GET replays it. */
    private class FakeBlobTransport : BlobTransport {
        val objects = LinkedHashMap<String, ByteArray>()
        override fun put(url: String, body: ByteArray) { objects[url] = body }
        override fun get(url: String): ByteArray = objects[url] ?: throw VeyqError.BlobTransferFailed
    }

    private object MediaDerivativeKindName { const val THUMBNAIL = "THUMBNAIL"; const val PREVIEW = "PREVIEW" }

    /** A stateful, derivative-aware dispatcher for the media batch control plane. One media = an original blob
     *  plus a derivative blob per role tag; stores only ciphertext wraps + opaque metadata + the role tags. */
    private class MediaDispatcher : Dispatcher() {
        private class Blob(val storageKey: String, val chunkCount: Int, val byteSize: Long, val wrap: String)
        private class Media(val mediaId: String, val blobId: String, val derivatives: List<Pair<String, String>>)
        private val sessions = HashMap<String, Blob>()
        private val blobs = HashMap<String, Blob>()
        private val mediaByNode = HashMap<String, MutableList<Media>>()
        var presign = true
        @Volatile var startItemCount = 0
        @Volatile var lastChunkCount = 0
        @Volatile var lastWrap: String? = null
        @Volatile var lastCompleteDerivativeKinds: List<String> = emptyList()

        @Synchronized override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path ?: ""
            val method = request.method ?: ""
            val body = if (method == "POST") request.body.readUtf8() else ""
            val items = if (body.isNotEmpty()) Json.parseToJsonElement(body).jsonObject["items"]?.jsonArray else null

            if (path.endsWith("/blob-uploads-batch")) {
                startItemCount = items!!.size
                val results = items.map { it.jsonObject }.map { item ->
                    val chunkCount = item["chunkCount"]!!.jsonPrimitive.content.toInt()
                    val byteSize = item["byteSize"]!!.jsonPrimitive.content.toLong()
                    val wrap = item["wrappedBlobKey"]!!.jsonPrimitive.content
                    lastChunkCount = chunkCount
                    lastWrap = wrap
                    val storageKey = "sk${sessions.size}"
                    val uploadId = UUID.randomUUID().toString()
                    sessions[uploadId] = Blob(storageKey, chunkCount, byteSize, wrap)
                    val puts = if (presign) {
                        val urls = (0 until chunkCount).joinToString(",") { """{"index":$it,"url":"mem://$storageKey/$it"}""" }
                        ""","presignedPuts":[$urls]"""
                    } else ""
                    """{"uploadId":"$uploadId"$puts}"""
                }
                return json(200, """{"results":[${results.joinToString(",")}]}""")
            }
            if (path.endsWith("/media-batch")) {
                val item = items!!.single().jsonObject
                val originalUploadId = item["originalUploadId"]!!.jsonPrimitive.content
                val node = item["nodeId"]!!.jsonPrimitive.content
                val derivativeItems = item["derivatives"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
                lastCompleteDerivativeKinds = derivativeItems.map { it["kind"]!!.jsonPrimitive.content }
                val original = sessions[originalUploadId] ?: return json(200, """{"results":[{"error":"upload_not_found"}]}""")
                val blobId = UUID.randomUUID().toString()
                blobs[blobId] = original
                val derivatives = derivativeItems.map { d ->
                    val kind = d["kind"]!!.jsonPrimitive.content
                    val s = sessions[d["uploadId"]!!.jsonPrimitive.content]
                        ?: return json(200, """{"results":[{"error":"upload_not_found"}]}""")
                    val dBlobId = UUID.randomUUID().toString()
                    blobs[dBlobId] = s
                    kind to dBlobId
                }
                val mediaId = UUID.randomUUID().toString()
                mediaByNode.getOrPut(node) { mutableListOf() }.add(Media(mediaId, blobId, derivatives))
                return json(200, """{"results":[{"blobId":"$blobId","mediaId":"$mediaId"}]}""")
            }
            if (path.endsWith("/blob-downloads-batch")) {
                val blobId = items!!.single().jsonObject["blobId"]!!.jsonPrimitive.content
                val b = blobs[blobId] ?: return json(200, """{"results":[{"error":"upload_not_found"}]}""")
                val urls = (0 until b.chunkCount).joinToString(",") { """{"index":$it,"url":"mem://${b.storageKey}/$it"}""" }
                return json(200, """{"results":[{"presignedGets":[$urls]}]}""")
            }
            if (path.substringBefore("?").endsWith("/media") && method == "GET") { // listNodeMedia now sends ?sinceDelete&state
                val node = path.substringBefore("?").removeSuffix("/media").substringAfterLast("/")
                val rows = (mediaByNode[node] ?: emptyList()).mapNotNull { m ->
                    val b = blobs[m.blobId] ?: return@mapNotNull null
                    val derivativeJson = m.derivatives.mapNotNull { (kind, dBlobId) ->
                        blobs[dBlobId]?.let { db ->
                            """{"kind":"$kind","blobId":"$dBlobId","chunkCount":${db.chunkCount},"byteSize":${db.byteSize},"wrappedBlobKey":"${db.wrap}"}"""
                        }
                    }
                    val derivativesField = if (derivativeJson.isNotEmpty()) ""","derivatives":[${derivativeJson.joinToString(",")}]""" else ""
                    """{"mediaId":"${m.mediaId}","blobId":"${m.blobId}","chunkCount":${b.chunkCount},"byteSize":${b.byteSize},"wrappedBlobKey":"${b.wrap}","updatedAt":"2026-01-01T00:00:00Z"$derivativesField}"""
                }
                return json(200, """{"media":[${rows.joinToString(",")}],"mediaRevision":0,"deleteRevision":0}""") // MediaListPage wrapper
            }
            return MockResponse().setResponseCode(404)
        }

        private fun json(code: Int, body: String) =
            MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    }
}
