package app.veyq.crypto

import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * uc.memory.browse (Android) — navigate the encrypted tree. The server is blind to names/paths, so path
 * resolution is a CLIENT operation: walk one level per path segment (GET /nodes?parentId=…), matching each
 * slug to a child by its blinded slug token. The node's TYPE comes from the node (server-visible structure),
 * not the URL — so /urlaube/italien-2026 stays clean and the client learns folder/memory/chapter per step.
 */
object Browse {
    private val client = HttpClient.newHttpClient()

    /** List the direct children of a level: a node id, or the scope root when parentId is null. */
    fun listChildren(apiBase: String, session: Login.UnlockedSession, scopeId: String, parentId: String? = null): List<JSONObject> {
        val slot = URLEncoder.encode(parentId ?: "root", "UTF-8")
        val req = HttpRequest.newBuilder(URI.create("$apiBase/api/scopes/$scopeId/nodes?parentId=$slot"))
            .header("Authorization", "Bearer ${session.sessionToken}").GET().build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) throw RuntimeException("list children failed: ${resp.statusCode()}")
        val arr = JSONObject(resp.body()).getJSONArray("nodes")
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    /** The whole node graph of a scope (GET /nodes, no parent filter) — drives client-side search. */
    fun listNodes(apiBase: String, session: Login.UnlockedSession, scopeId: String): List<JSONObject> {
        val req = HttpRequest.newBuilder(URI.create("$apiBase/api/scopes/$scopeId/nodes"))
            .header("Authorization", "Bearer ${session.sessionToken}").GET().build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) throw RuntimeException("list nodes failed: ${resp.statusCode()}")
        val arr = JSONObject(resp.body()).getJSONArray("nodes")
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    /**
     * Resolve a clean slug path to the chain of nodes, one per segment, by matching each slug to a child's
     * per-parent slug token. Throws if a segment doesn't exist. Each node carries its own type.
     */
    fun resolvePath(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: ByteArray, segments: List<String>): List<JSONObject> {
        val chain = mutableListOf<JSONObject>()
        var parentId: String? = null   // null → root
        var parentSlot = scopeId       // the root's parent slot is the scope id (the server's sentinel)
        for (slug in segments) {
            val children = listChildren(apiBase, session, scopeId, parentId)
            val token = MemoryTree.slugTokenFor(contentKey, parentSlot, slug)
            val node = children.firstOrNull { it.getString("slugToken") == token }
                ?: throw RuntimeException("path segment not found: $slug")
            chain.add(node)
            parentId = node.getString("id")
            parentSlot = node.getString("id")
        }
        return chain
    }
}
