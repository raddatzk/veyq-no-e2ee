package app.veyq.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real end-to-end against a running backend; opt-in via VEYQ_E2E=1 so plain `gradle test` no-ops it. */
class BrowseE2ETest {
    private val apiBase = System.getenv("VEYQ_API_BASE") ?: "http://localhost:8080"

    @Test
    fun resolvesACleanSlugPathClientSide() {
        if (System.getenv("VEYQ_E2E") != "1") return // opt-in: requires a live backend

        val pass = "correct horse battery staple"
        val (_, kit) = SetupServer.registerServerOwner(apiBase, "admin@example.com", "Admin", pass)
        val a = Login.login(apiBase, "admin@example.com", pass, kit.secretKey)
        val scope = ScopeContent.openPersonalScope(apiBase, a)
        val ck = ScopeContent.getOrCreateContentKey(apiBase, a, scope)

        val folder = MemoryTree.createNode(apiBase, a, scope.scopeId, ck, "FOLDER", "Urlaube", "urlaube", 0)
        val memory = MemoryTree.createNode(apiBase, a, scope.scopeId, ck, "MEMORY", "Italien 2026", "italien-2026", 0, folder.getString("id"))
        MemoryTree.createNode(apiBase, a, scope.scopeId, ck, "CHAPTER", "Tag 1", "tag-1", 0, memory.getString("id"))
        MemoryTree.createNode(apiBase, a, scope.scopeId, ck, "FOLDER", "Arbeit", "arbeit", 1)

        // level listing: the root has two folders; the folder has one memory
        assertEquals(2, Browse.listChildren(apiBase, a, scope.scopeId).size)
        assertEquals(1, Browse.listChildren(apiBase, a, scope.scopeId, folder.getString("id")).size)

        // resolve the clean path — each resolved node knows its own type
        val chain = Browse.resolvePath(apiBase, a, scope.scopeId, ck, listOf("urlaube", "italien-2026", "tag-1"))
        assertEquals(listOf("FOLDER", "MEMORY", "CHAPTER"), chain.map { it.getString("type") })
        assertEquals(listOf("Urlaube", "Italien 2026", "Tag 1"), chain.map { MemoryTree.decodeNode(ck, it).getString("name") })

        // a non-existent segment fails
        var resolveFailed = false
        try { Browse.resolvePath(apiBase, a, scope.scopeId, ck, listOf("urlaube", "does-not-exist")) } catch (e: Exception) { resolveFailed = true }
        assertTrue(resolveFailed, "a non-existent path segment must fail to resolve")
    }
}
