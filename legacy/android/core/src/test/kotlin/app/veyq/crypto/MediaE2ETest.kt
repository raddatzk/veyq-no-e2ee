package app.veyq.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real end-to-end against a running backend; opt-in via VEYQ_E2E=1 so plain `gradle test` no-ops it.
 * One upload/download path for small + large + thumbnail, plus dedup, resume, and gallery ordering.
 */
class MediaE2ETest {
    private val apiBase = System.getenv("VEYQ_API_BASE") ?: "http://localhost:8080"

    @Test
    fun unifiedUploadDownloadDedupResumeAndGallery() {
        if (System.getenv("VEYQ_E2E") != "1") return // opt-in: requires a live backend

        val pass = "correct horse battery staple"
        val (_, kit) = SetupServer.registerServerOwner(apiBase, "admin@example.com", "Admin", pass)
        val a = Login.login(apiBase, "admin@example.com", pass, kit.secretKey)
        val scope = ScopeContent.openPersonalScope(apiBase, a)
        val ck = ScopeContent.getOrCreateContentKey(apiBase, a, scope)
        val memory = MemoryTree.createNode(apiBase, a, scope.scopeId, ck, "MEMORY", "Italien 2026", "italien-2026", 0)
        val memId = memory.getString("id")

        // a small photo (one frame) + its thumbnail + preview, all through the SAME upload path
        val photo = ByteArray(200) { ((it * 13 + 1) and 0xff).toByte() }
        val thumb = ByteArray(40) { ((it * 5 + 2) and 0xff).toByte() }
        val preview = ByteArray(80) { ((it * 9 + 6) and 0xff).toByte() }
        val m1 = Media.uploadMedia(apiBase, a, scope.scopeId, ck, memId, photo, thumb, preview, takenAt = "2026-03-15T12:00:00Z", mimeType = "image/jpeg")
        assertEquals(1, m1.getJSONObject("original").getInt("totalChunks"))
        val kinds = (0 until m1.getJSONArray("derivatives").length()).map { m1.getJSONArray("derivatives").getJSONObject(it).getString("kind") }.sorted()
        assertEquals(listOf("PREVIEW", "THUMBNAIL"), kinds) // grid uses the thumbnail, the lightbox the preview
        val origBlobId = m1.getJSONObject("original").getString("blobId")

        // a large "video" split into many frames — same call, chunked transparently
        val chunkSize = 10
        val video = ByteArray(35) { ((it * 7 + 3) and 0xff).toByte() }
        val m2 = Media.uploadMedia(apiBase, a, scope.scopeId, ck, memId, video, thumb = null, takenAt = "2026-01-01T08:00:00Z", mimeType = "video/mp4", chunkSize = chunkSize)
        assertEquals(4, m2.getJSONObject("original").getInt("totalChunks"))
        assertEquals(0, m2.getJSONArray("derivatives").length())

        // both download byte-identical through the SAME (direct-from-store) frame path
        fun ref(item: org.json.JSONObject) = item.getJSONObject("original").let { Media.BlobRef(it.getString("blobId"), it.getInt("totalChunks"), it.getLong("byteSize")) }
        assertContentEquals(photo, Media.downloadBlob(apiBase, a, scope.scopeId, ck, ref(m1)))
        assertContentEquals(video, Media.downloadBlob(apiBase, a, scope.scopeId, ck, ref(m2)))

        // dedup: a batch-start for identical bytes returns the existing blob (READY), no frames sent
        val dedup = Media.batchStartBlobs(apiBase, a, scope.scopeId, listOf(Media.startItem(Media.dedupTokenFor(ck, photo), 1, photo.size.toLong())))[0]
        assertEquals("READY", dedup.status)
        assertEquals(origBlobId, dedup.blobId)

        // resume: open an upload, PUT only chunk 0 direct-to-store, then a resume batch-start sees it received
        // and re-presigns ONLY the missing chunks (1, 2)
        val partial = ByteArray(25) { ((it * 3 + 9) and 0xff).toByte() }
        val pchunks = Media.chunkBytes(partial, chunkSize)
        assertEquals(3, pchunks.size)
        val open = Media.batchStartBlobs(apiBase, a, scope.scopeId, listOf(Media.startItem(Media.dedupTokenFor(ck, partial), pchunks.size, partial.size.toLong())))[0]
        val partialUploadId = open.uploadId!!
        Media.putPresigned(open.presignedPuts[0]!!, V1.sealFrame(ck, 0, pchunks.size, pchunks[0], V1.randomBytes(12)), 0)
        val resumed = Media.batchStartBlobs(apiBase, a, scope.scopeId, listOf(Media.startItem(Media.dedupTokenFor(ck, partial), pchunks.size, partial.size.toLong(), partialUploadId)))[0]
        assertEquals(listOf(0), resumed.receivedChunks.sorted())
        assertEquals(listOf(1, 2), resumed.presignedPuts.keys.sorted())

        // the gallery lists both media in upload order (photo then video; the client sorts by decrypted takenAt)
        val gallery = Media.listMedia(apiBase, a, scope.scopeId, memId)
        assertEquals(listOf(m1.getString("id"), m2.getString("id")), gallery.map { it.getString("id") })
        assertEquals("THUMBNAIL", gallery[0].getJSONArray("derivatives").getJSONObject(0).getString("kind"))
        // sealed metadata round-trips: the decrypted capture time matches what we uploaded
        assertEquals("2026-03-15T12:00:00Z", Media.decodeMediaMeta(ck, gallery[0]).takenAt)

        // anti-reorder still holds: a frame sealed at index 0 must not open at index 1
        val frame0 = V1.sealFrame(ck, 0, pchunks.size, pchunks[0], V1.randomBytes(12))
        var reorderRejected = false
        try { V1.openFrame(ck, 1, pchunks.size, frame0) } catch (e: Exception) { reorderRejected = true }
        assertTrue(reorderRejected, "a frame must not open at the wrong index")
    }
}
