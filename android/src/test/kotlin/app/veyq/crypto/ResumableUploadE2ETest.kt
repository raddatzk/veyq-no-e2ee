package app.veyq.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real end-to-end against a running backend; opt-in via VEYQ_E2E=1 so plain `gradle test` no-ops it. */
class ResumableUploadE2ETest {
    private val apiBase = System.getenv("VEYQ_API_BASE") ?: "http://localhost:8080"

    @Test
    fun interruptAndResumeAChunkedUpload() {
        if (System.getenv("VEYQ_E2E") != "1") return // opt-in: requires a live backend

        val pass = "correct horse battery staple"
        val (_, kit) = SetupServer.registerServerOwner(apiBase, "admin@example.com", "Admin", pass)
        val a = Login.login(apiBase, "admin@example.com", pass, kit.secretKey)
        val scope = ScopeContent.openPersonalScope(apiBase, a)
        val ck = ScopeContent.getOrCreateContentKey(apiBase, a, scope)
        val memory = MemoryTree.createNode(apiBase, a, scope.scopeId, ck, "MEMORY", "Italien 2026", "italien-2026", 0)
        val memId = memory.getString("id")

        val video = ByteArray(35) { ((it * 7 + 3) and 0xff).toByte() }
        val chunks = ResumableUpload.chunkBytes(video, 10)
        assertEquals(4, chunks.size)

        val sess = ResumableUpload.createUpload(apiBase, a, scope.scopeId, memId, chunks.size, "video/mp4", video.size.toLong(), "2026-01-01T10:00:00Z")
        val uploadId = sess.getString("uploadId")

        // upload only the first two chunks, then "the connection drops"
        ResumableUpload.putChunk(apiBase, a, uploadId, 0, chunks.size, ck, chunks[0])
        ResumableUpload.putChunk(apiBase, a, uploadId, 1, chunks.size, ck, chunks[1])
        val mid = ResumableUpload.getProgress(apiBase, a, uploadId)
        assertEquals("OPEN", mid.getString("status"))
        val rec = mid.getJSONArray("receivedChunks").let { (0 until it.length()).map { i -> it.getInt(i) }.sorted() }
        assertEquals(listOf(0, 1), rec)

        // resume sends the missing chunks then finalizes
        val done = ResumableUpload.resumeUpload(apiBase, a, uploadId, ck, chunks)
        assertEquals("COMPLETED", done.getString("status"))

        // download + decrypt frame by frame, byte-identical
        val recovered = ResumableUpload.downloadResumable(apiBase, a, uploadId, ck, chunks.size)
        assertContentEquals(video, recovered)

        // anti-reorder: a frame sealed at index 0 must not open at index 1
        val frame0 = V1.sealFrame(ck, 0, chunks.size, chunks[0], V1.randomBytes(12))
        var reorderRejected = false
        try { V1.openFrame(ck, 1, chunks.size, frame0) } catch (e: Exception) { reorderRejected = true }
        assertTrue(reorderRejected, "a frame must not open at the wrong index")
    }
}
