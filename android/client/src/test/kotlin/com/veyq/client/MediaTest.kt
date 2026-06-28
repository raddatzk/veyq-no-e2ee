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
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * cr-0028 phase 6 — en.memory.media (android) data-layer tests, the twin of Apple's `MediaTests`. A stateful
 * [MediaDispatcher] serves the batch control plane with `mem://…` presigned URLs, and an in-memory
 * [FakeBlobTransport] stores PUT bodies + replays them on GET — so addMedia → openMedia round-trips the real
 * native v1 chunk seal/open + the K_blob wrap (under K_mem) without a network or MinIO. Operator-blind: the
 * "store" only ever holds ciphertext frames; the backend dispatcher only ciphertext wraps + opaque metadata.
 */
class MediaTest {
    private val scopeId = "11111111-1111-1111-1111-111111111111"
    private val nodeId = "22222222-2222-2222-2222-222222222222"
    private lateinit var server: MockWebServer

    @BeforeTest fun setUp() { server = MockWebServer() }
    @AfterTest fun tearDown() { server.shutdown() }
    private fun baseUrl(): String = server.url("").toString().trimEnd('/')

    private fun client(d: MediaDispatcher, blobs: BlobTransport): VeyqClient {
        server.dispatcher = d
        server.start()
        val session = Session().apply { set("access-1", "refresh-1", "fam") }
        return VeyqClient(VeyqInstance(baseUrl()), session = session,
                          deviceSecurity = InMemoryDeviceSecurityStore(), blobTransport = blobs)
    }

    @Test fun addThenOpenRoundTripsBytes() {
        val dispatcher = MediaDispatcher()
        val blobs = FakeBlobTransport()
        val c = client(dispatcher, blobs)
        val memoryKey = ByteArray(32) { 7 }

        // 600 KiB ⇒ 3 chunks at CHUNK_SIZE = 256 KiB
        val data = ByteArray(600 * 1024) { ((it * 31 + 7) and 0xff).toByte() }

        val (blobId, mediaId) = c.addMedia(scopeId, nodeId, memoryKey, data)
        assertTrue(blobId.isNotEmpty())
        assertTrue(mediaId.isNotEmpty())

        // control plane: declared 3 chunks; the store holds 3 ciphertext frames (each = chunk + nonce + tag)
        assertEquals(3, dispatcher.lastChunkCount)
        assertEquals(3, blobs.objects.size)
        val firstFrame = blobs.objects.values.first { it.size == VeyqClient.CHUNK_SIZE + 12 + 16 }
        assertTrue(!firstFrame.copyOfRange(0, VeyqClient.CHUNK_SIZE).contentEquals(data.copyOfRange(0, VeyqClient.CHUNK_SIZE)))

        // the wrap the backend saw opens back to a real 32-byte K_blob under K_mem with the 'blob-key' context
        val blobKey = V1.symUnwrap(memoryKey, Base64.getDecoder().decode(dispatcher.lastWrap!!), "blob-key")
        assertEquals(32, blobKey.size)

        // read-back round-trips to the original plaintext
        val items = c.listMedia(scopeId, nodeId)
        assertEquals(1, items.size)
        assertContentEquals(data, c.openMedia(scopeId, memoryKey, items[0]))
    }

    @Test fun storeUnavailableWhenNoPresign() {
        val dispatcher = MediaDispatcher().apply { presign = false }
        val c = client(dispatcher, FakeBlobTransport())
        assertFailsWith<VeyqError.StoreUnavailable> {
            c.addMedia(scopeId, nodeId, ByteArray(32) { 1 }, ByteArray(10) { 9 })
        }
    }

    /** In-memory object store for the byte transport: PUT stores the body under the URL, GET replays it. */
    private class FakeBlobTransport : BlobTransport {
        val objects = LinkedHashMap<String, ByteArray>()
        override fun put(url: String, body: ByteArray) { objects[url] = body }
        override fun get(url: String): ByteArray = objects[url] ?: throw VeyqError.BlobTransferFailed
    }

    /** Stateful dispatcher for the media batch control plane (length-1 lists this slice). */
    private class MediaDispatcher : Dispatcher() {
        private val sessions = HashMap<String, Session4>()
        private val blobs = HashMap<String, Session4>()
        private val mediaByNode = HashMap<String, MutableList<Pair<String, String>>>()
        var presign = true
        @Volatile var lastChunkCount = 0
        @Volatile var lastWrap: String? = null

        private class Session4(val storageKey: String, val chunkCount: Int, val byteSize: Long, val wrap: String)

        @Synchronized override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path ?: ""
            val method = request.method ?: ""
            val body = if (method == "POST") request.body.readUtf8() else ""

            if (path.endsWith("/blob-uploads-batch")) {
                val chunkCount = intField(body, "chunkCount") ?: 0
                val byteSize = longField(body, "byteSize") ?: 0
                val wrap = strField(body, "wrappedBlobKey") ?: ""
                lastChunkCount = chunkCount
                lastWrap = wrap
                val storageKey = "sk${sessions.size}"
                val uploadId = UUID.randomUUID().toString()
                sessions[uploadId] = Session4(storageKey, chunkCount, byteSize, wrap)
                val puts = if (presign) {
                    val urls = (0 until chunkCount).joinToString(",") { """{"index":$it,"url":"mem://$storageKey/$it"}""" }
                    ""","presignedPuts":[$urls]"""
                } else ""
                return json(200, """{"results":[{"uploadId":"$uploadId"$puts}]}""")
            }
            if (path.endsWith("/media-batch")) {
                val uploadId = strField(body, "uploadId") ?: ""
                val node = strField(body, "nodeId") ?: ""
                val s = sessions[uploadId] ?: return json(200, """{"results":[{"error":"upload_not_found"}]}""")
                val blobId = UUID.randomUUID().toString()
                val mediaId = UUID.randomUUID().toString()
                blobs[blobId] = s
                mediaByNode.getOrPut(node) { mutableListOf() }.add(mediaId to blobId)
                return json(200, """{"results":[{"blobId":"$blobId","mediaId":"$mediaId"}]}""")
            }
            if (path.endsWith("/blob-downloads-batch")) {
                val blobId = strField(body, "blobId") ?: ""
                val b = blobs[blobId] ?: return json(200, """{"results":[{"error":"upload_not_found"}]}""")
                val urls = (0 until b.chunkCount).joinToString(",") { """{"index":$it,"url":"mem://${b.storageKey}/$it"}""" }
                return json(200, """{"results":[{"presignedGets":[$urls]}]}""")
            }
            if (path.endsWith("/media") && method == "GET") {
                val node = path.removeSuffix("/media").substringAfterLast("/")
                val rows = (mediaByNode[node] ?: emptyList()).mapNotNull { (mediaId, blobId) ->
                    blobs[blobId]?.let { b ->
                        """{"mediaId":"$mediaId","blobId":"$blobId","chunkCount":${b.chunkCount},"byteSize":${b.byteSize},"wrappedBlobKey":"${b.wrap}"}"""
                    }
                }
                return json(200, "[${rows.joinToString(",")}]")
            }
            return MockResponse().setResponseCode(404)
        }

        private fun strField(body: String, name: String): String? =
            "\"$name\":\"([^\"]*)\"".toRegex().find(body)?.groupValues?.get(1)
        private fun intField(body: String, name: String): Int? =
            "\"$name\":(\\d+)".toRegex().find(body)?.groupValues?.get(1)?.toInt()
        private fun longField(body: String, name: String): Long? =
            "\"$name\":(\\d+)".toRegex().find(body)?.groupValues?.get(1)?.toLong()

        private fun json(code: Int, body: String) =
            MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    }
}
