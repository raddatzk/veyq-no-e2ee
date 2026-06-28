package com.veyq.client

import com.veyq.api.content.model.NodeKind
import com.veyq.crypto.V1
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * cr-0027 phase 6 — en.memory.tree (android) data-layer tests, the twin of Apple's `ContentTreeTests`.
 * A stateful [NodesDispatcher] serves /api/scopes/{id}/nodes: POST captures the ciphertext a create sends and
 * serves it back in the list, so `listTree` round-trips the name (sealed under K_SC) and `openMemory` recovers
 * the per-Memory K_mem with the real native v1 crypto. Operator-blind: only ciphertext crosses the wire.
 */
class ContentTreeTest {
    private val scopeId = "11111111-1111-1111-1111-111111111111"
    private val scopeKey = ByteArray(32) { 9 }
    private lateinit var server: MockWebServer

    @BeforeTest fun setUp() { server = MockWebServer() }
    @AfterTest fun tearDown() { server.shutdown() }
    private fun baseUrl(): String = server.url("").toString().trimEnd('/')

    private fun client(d: NodesDispatcher): VeyqClient {
        server.dispatcher = d
        server.start()
        val session = Session().apply { set("access-1", "refresh-1", "fam") }
        return VeyqClient(VeyqInstance(baseUrl()), session = session, deviceSecurity = InMemoryDeviceSecurityStore())
    }

    @Test fun createListDecryptRoundTrip() {
        val c = client(NodesDispatcher())

        c.createFolder(scopeId, scopeKey, "Trips")
        val (memId, memKey) = c.createMemory(scopeId, scopeKey, "Italien 2026")
        assertEquals(32, memKey.size)

        val tree = c.listTree(scopeId, scopeKey)
        assertEquals(2, tree.size)

        val folder = tree.first { it.kind == NodeKind.FOLDER }
        assertEquals("Trips", folder.name)            // name opened under K_SC
        assertNull(folder.wrappedContentKey)          // a folder carries no content key

        val memory = tree.first { it.kind == NodeKind.MEMORY }
        assertEquals(memId, memory.id)
        assertEquals("Italien 2026", memory.name)
        // the wrapped K_mem opens back to exactly the key createMemory returned (end-to-end)
        assertContentEquals(memKey, c.openMemory(scopeKey, memory))

        // operator-blind: the slug is a blinded MAC under K_SC over the root parent slot (the scope id) + slug
        val expected = Base64.getEncoder().encodeToString(V1.slugToken(scopeKey, scopeId, "italien-2026"))
        assertEquals(expected, memSlug)
    }

    @Test fun renameRecomputesSlugThenDelete() {
        val d = NodesDispatcher()
        val c = client(d)
        c.createFolder(scopeId, scopeKey, "Trips")
        var node = c.listTree(scopeId, scopeKey).first()

        c.renameNode(scopeId, scopeKey, node, "Reisen")
        node = c.listTree(scopeId, scopeKey).first()
        assertEquals("Reisen", node.name)
        assertEquals(Base64.getEncoder().encodeToString(V1.slugToken(scopeKey, scopeId, "reisen")), d.lastSlug)

        c.deleteNode(scopeId, node.id)
        assertTrue(c.listTree(scopeId, scopeKey).isEmpty())
    }

    // captured by the dispatcher for the operator-blind slug assertion
    private var memSlug: String? = null

    /** Serves /api/scopes/{id}/nodes: POST appends a node (UUID assigned), GET lists, PATCH mutates, DELETE removes. */
    private inner class NodesDispatcher : Dispatcher() {
        private val nodes = LinkedHashMap<String, Node>()
        private var seq = 0
        @Volatile var lastSlug: String? = null

        private inner class Node(
            val id: String, val parentId: String?, val kind: String,
            var encryptedName: String, var slugToken: String, val orderIndex: Int, val wrappedContentKey: String?,
        )

        @Synchronized override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path ?: ""
            val method = request.method ?: ""
            if (path.endsWith("/nodes") && method == "GET") return json(200, "[${nodes.values.joinToString(",") { it.toJson() }}]")
            if (path.endsWith("/nodes") && method == "POST") {
                val b = request.body.readUtf8()
                seq += 1
                val id = "aaaaaaaa-0000-0000-0000-%012d".format(seq)
                val kind = field(b, "kind")!!
                val slug = field(b, "slugToken")!!
                if (kind == "MEMORY") memSlug = slug
                nodes[id] = Node(id, field(b, "parentId"), kind, field(b, "encryptedName")!!, slug,
                                 field(b, "orderIndex")?.toIntOrNull() ?: 0, field(b, "wrappedContentKey"))
                return json(201, """{"nodeId":"$id"}""")
            }
            val nodeId = path.substringAfterLast("/")
            if (method == "PATCH") {
                val b = request.body.readUtf8()
                lastSlug = field(b, "slugToken")
                nodes[nodeId]?.let { n ->
                    field(b, "encryptedName")?.let { n.encryptedName = it }
                    field(b, "slugToken")?.let { n.slugToken = it }
                }
                return MockResponse().setResponseCode(204)
            }
            if (method == "DELETE") { nodes.remove(nodeId); return MockResponse().setResponseCode(204) }
            return MockResponse().setResponseCode(404)
        }

        private fun field(body: String, name: String): String? =
            "\"$name\":\"([^\"]*)\"".toRegex().find(body)?.groupValues?.get(1)

        private fun Node.toJson(): String = buildString {
            append("""{"id":"$id","kind":"$kind","encryptedName":"$encryptedName","slugToken":"$slugToken","orderIndex":$orderIndex""")
            parentId?.let { append(""","parentId":"$it"""") }
            wrappedContentKey?.let { append(""","wrappedContentKey":"$it"""") }
            append("}")
        }

        private fun json(code: Int, body: String) =
            MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    }
}
