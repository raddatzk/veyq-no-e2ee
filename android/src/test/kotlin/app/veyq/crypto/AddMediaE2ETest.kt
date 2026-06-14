package app.veyq.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Real end-to-end against a running backend; opt-in via VEYQ_E2E=1 so plain `gradle test` no-ops it. */
class AddMediaE2ETest {
    private val apiBase = System.getenv("VEYQ_API_BASE") ?: "http://localhost:8080"

    @Test
    fun sealsMediaAndOrdersByTakenAt() {
        if (System.getenv("VEYQ_E2E") != "1") return // opt-in: requires a live backend

        val pass = "correct horse battery staple"
        val (_, kit) = SetupServer.registerServerOwner(apiBase, "admin@example.com", "Admin", pass)
        val a = Login.login(apiBase, "admin@example.com", pass, kit.secretKey)
        val scope = ScopeContent.openPersonalScope(apiBase, a)
        val ck = ScopeContent.getOrCreateContentKey(apiBase, a, scope)
        val memory = MemoryTree.createNode(apiBase, a, scope.scopeId, ck, "MEMORY", "Italien 2026", "italien-2026", 0)
        val memId = memory.getString("id")

        val photoLater = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0x00, 0x10, 0x20)
        val photoEarlier = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x01, 0x02)
        val thumb = byteArrayOf(1, 2, 3, 4)
        AddMedia.addMedia(apiBase, a, scope.scopeId, ck, memId, photoLater, thumb, "2026-03-15T12:00:00Z", "image/jpeg")
        AddMedia.addMedia(apiBase, a, scope.scopeId, ck, memId, photoEarlier, null, "2026-01-01T08:00:00Z", "image/png")

        // ordered by takenAt (earliest first); bytes + thumbnail round-trip byte-identical
        val items = AddMedia.listMedia(apiBase, a, scope.scopeId, memId)
        assertEquals(listOf("image/png", "image/jpeg"), items.map { it.getString("mimeType") })
        assertContentEquals(photoEarlier, AddMedia.decodeBlob(ck, items[0].getJSONObject("sealedBlob")))
        assertContentEquals(photoLater, AddMedia.decodeBlob(ck, items[1].getJSONObject("sealedBlob")))
        assertContentEquals(thumb, AddMedia.decodeBlob(ck, items[1].getJSONObject("sealedThumb")))
        assertFalse(AddMedia.hasThumb(items[0]))
    }
}
