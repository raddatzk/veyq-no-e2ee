package app.veyq.crypto

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real end-to-end against a running backend; opt-in via VEYQ_E2E=1 so plain `gradle test` no-ops it. */
class MemoryTreeE2ETest {
    private val apiBase = System.getenv("VEYQ_API_BASE") ?: "http://localhost:8080"

    private fun byId(nodes: JSONArray, id: String): JSONObject? {
        for (i in 0 until nodes.length()) {
            val n = nodes.getJSONObject(i)
            if (n.getString("id") == id) return n
        }
        return null
    }

    @Test
    fun buildsEncryptedFolderMemoryChapterTree() {
        if (System.getenv("VEYQ_E2E") != "1") return // opt-in: requires a live backend

        val pass = "correct horse battery staple"
        val (_, kit) = SetupServer.registerServerOwner(apiBase, "admin@example.com", "Admin", pass)
        val a = Login.login(apiBase, "admin@example.com", pass, kit.secretKey)
        val scope = ScopeContent.openPersonalScope(apiBase, a)
        val ck = ScopeContent.getOrCreateContentKey(apiBase, a, scope)

        val folder = MemoryTree.createNode(apiBase, a, scope.scopeId, ck, "FOLDER", "Urlaube", "urlaube", 0)
        val memory = MemoryTree.createNode(apiBase, a, scope.scopeId, ck, "MEMORY", "Italien 2026", "italien-2026", 0, folder.getString("id"))
        val chapter = MemoryTree.createNode(apiBase, a, scope.scopeId, ck, "CHAPTER", "Tag 1", "tag-1", 0, memory.getString("id"))

        assertTrue(folder.isNull("parentId"))
        assertEquals(folder.getString("id"), memory.getString("parentId"))
        assertEquals(memory.getString("id"), chapter.getString("parentId"))
        assertEquals(MemoryTree.slugTokenFor(ck, "urlaube"), folder.getString("slugToken"))

        // fetch the flat graph and reconstruct names + structure locally
        val nodes = MemoryTree.listNodes(apiBase, a, scope.scopeId)
        assertEquals(3, nodes.length())
        assertEquals("Urlaube", MemoryTree.decodeNodeName(ck, byId(nodes, folder.getString("id"))!!))
        assertEquals("Italien 2026", MemoryTree.decodeNodeName(ck, byId(nodes, memory.getString("id"))!!))
        assertEquals("Tag 1", MemoryTree.decodeNodeName(ck, byId(nodes, chapter.getString("id"))!!))

        // invariants enforced server-side: a duplicate sibling slug and a folder-in-a-memory are rejected
        var dupRejected = false
        try { MemoryTree.createNode(apiBase, a, scope.scopeId, ck, "FOLDER", "Urlaube 2", "urlaube", 1) } catch (e: Exception) { dupRejected = true }
        assertTrue(dupRejected, "duplicate sibling slug must be rejected")

        var containmentRejected = false
        try { MemoryTree.createNode(apiBase, a, scope.scopeId, ck, "FOLDER", "Nope", "nope", 1, memory.getString("id")) } catch (e: Exception) { containmentRejected = true }
        assertTrue(containmentRejected, "a folder inside a memory must be rejected")
    }
}
