package app.veyq.crypto

import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.text.Normalizer
import java.util.Base64

/**
 * uc.memory.curate (Android) — PATCH /nodes/{id}: rename (re-seal name + recomputed per-parent slug token,
 * retrying on collision), move (slug token recomputed under the new parent), set cover, reorder. The server
 * stays blind to names.
 */
object Curate {
    private val client = HttpClient.newHttpClient()
    private val enc = Base64.getUrlEncoder().withoutPadding()

    fun renameNode(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: ByteArray, node: JSONObject, newName: String): JSONObject {
        val slot = if (node.isNull("parentId")) scopeId else node.getString("parentId")
        val name = newName.trim()
        val base = slugify(name)
        for (attempt in 0 until 25) {
            val slug = if (attempt == 0) base else "$base-${attempt + 1}"
            val sealed = MemoryTree.resealNode(contentKey, JSONObject().put("name", name).put("slug", slug))
            val body = JSONObject()
                .put("sealedContent", JSONObject().put("suiteId", "v1").put("bytes", enc.encodeToString(sealed)))
                .put("slugToken", MemoryTree.slugTokenFor(contentKey, slot, slug)).toString()
            val resp = patch(apiBase, session, scopeId, node.getString("id"), body)
            if (resp.statusCode() == 200) return JSONObject(resp.body())
            if (resp.statusCode() != 409 || !resp.body().contains("SLUG_EXISTS")) throw RuntimeException("rename failed: ${resp.statusCode()}")
        }
        throw RuntimeException("rename slug conflict")
    }

    fun moveNode(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: ByteArray, node: JSONObject, newParentId: String): JSONObject {
        val name = MemoryTree.decodeNode(contentKey, node).getString("name")
        val slot = if (newParentId == "root") scopeId else newParentId
        val base = slugify(name)
        for (attempt in 0 until 25) {
            val slug = if (attempt == 0) base else "$base-${attempt + 1}"
            val body = JSONObject().put("newParentId", newParentId).put("slugToken", MemoryTree.slugTokenFor(contentKey, slot, slug))
            if (attempt > 0) body.put("sealedContent", JSONObject().put("suiteId", "v1").put("bytes", enc.encodeToString(MemoryTree.resealNode(contentKey, JSONObject().put("name", name).put("slug", slug)))))
            val resp = patch(apiBase, session, scopeId, node.getString("id"), body.toString())
            if (resp.statusCode() == 200) return JSONObject(resp.body())
            if (resp.statusCode() != 409 || !resp.body().contains("SLUG_EXISTS")) throw RuntimeException("move failed: ${resp.statusCode()}")
        }
        throw RuntimeException("move slug conflict")
    }

    fun setCover(apiBase: String, session: Login.UnlockedSession, scopeId: String, nodeId: String, coverMediaId: String): JSONObject {
        val resp = patch(apiBase, session, scopeId, nodeId, JSONObject().put("coverMediaId", coverMediaId).toString())
        if (resp.statusCode() != 200) throw RuntimeException("set cover failed: ${resp.statusCode()}")
        return JSONObject(resp.body())
    }

    fun reorderNode(apiBase: String, session: Login.UnlockedSession, scopeId: String, nodeId: String, orderIndex: Int): JSONObject {
        val resp = patch(apiBase, session, scopeId, nodeId, JSONObject().put("orderIndex", orderIndex).toString())
        if (resp.statusCode() != 200) throw RuntimeException("reorder failed: ${resp.statusCode()}")
        return JSONObject(resp.body())
    }

    private fun patch(apiBase: String, session: Login.UnlockedSession, scopeId: String, nodeId: String, body: String): HttpResponse<String> {
        val req = HttpRequest.newBuilder(URI.create("$apiBase/api/scopes/$scopeId/nodes/$nodeId"))
            .header("Authorization", "Bearer ${session.sessionToken}").header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(body)).build()
        return client.send(req, HttpResponse.BodyHandlers.ofString())
    }

    private fun slugify(name: String): String {
        val allowed = (('a'..'z') + ('0'..'9')).toSet()
        val folded = Normalizer.normalize(name, Normalizer.Form.NFKD).replace("\\p{M}".toRegex(), "").lowercase()
        return folded.map { if (it in allowed) it else '-' }.joinToString("").split("-").filter { it.isNotEmpty() }.joinToString("-").ifEmpty { "item" }
    }
}
