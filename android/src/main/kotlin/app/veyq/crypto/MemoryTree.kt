package app.veyq.crypto

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.text.Normalizer
import java.util.Base64

/**
 * uc.memory.create-tree (Android) — build the encrypted Memory tree: Folders, Memories and Chapters under a
 * scope. The name is sealed under the scope content key; the slug becomes a blinded slug token (AD-26) so
 * the server enforces per-parent uniqueness without seeing the name. Server holds only structure + ciphertext.
 */
object MemoryTree {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()
    private val client = HttpClient.newHttpClient()
    private val EMPTY = ByteArray(0)

    /** Create one node: the name is sealed under the content key, the slug becomes a blinded token. */
    fun createNode(
        apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: ByteArray,
        type: String, name: String, slug: String, orderIndex: Int, parentId: String? = null,
    ): JSONObject {
        val nonce = V1.randomBytes(12)
        val normalized = Normalizer.normalize(name, Normalizer.Form.NFC).toByteArray(Charsets.UTF_8)
        val sealed = nonce + V1.aeadSeal(contentKey, nonce, normalized, EMPTY)
        val parentPart = if (parentId == null) "" else """"parentId":"$parentId","""
        val json = """{$parentPart"type":"$type","slugToken":"${enc.encodeToString(V1.slugToken(contentKey, slug))}",""" +
            """"orderIndex":$orderIndex,"sealedContent":{"suiteId":"v1","bytes":"${enc.encodeToString(sealed)}"}}"""
        val req = HttpRequest.newBuilder(URI.create("$apiBase/api/scopes/$scopeId/nodes"))
            .header("Authorization", "Bearer ${session.sessionToken}").header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json)).build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) throw RuntimeException("create node failed: ${resp.statusCode()} ${resp.body()}")
        return JSONObject(resp.body())
    }

    /** List the scope's node graph (flat). The client reconstructs the tree + decrypts names locally. */
    fun listNodes(apiBase: String, session: Login.UnlockedSession, scopeId: String): JSONArray {
        val req = HttpRequest.newBuilder(URI.create("$apiBase/api/scopes/$scopeId/nodes"))
            .header("Authorization", "Bearer ${session.sessionToken}").GET().build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) throw RuntimeException("list nodes failed: ${resp.statusCode()} ${resp.body()}")
        return JSONObject(resp.body()).getJSONArray("nodes")
    }

    /** Decrypt a node's sealed name with the scope content key. */
    fun decodeNodeName(contentKey: ByteArray, node: JSONObject): String {
        val sealed = dec.decode(node.getJSONObject("sealedContent").getString("bytes"))
        return String(V1.aeadOpen(contentKey, sealed.copyOfRange(0, 12), sealed.copyOfRange(12, sealed.size), EMPTY), Charsets.UTF_8)
    }

    /** The blinded slug token for a slug under a scope's content key (matches what the server stored). */
    fun slugTokenFor(contentKey: ByteArray, slug: String): String = enc.encodeToString(V1.slugToken(contentKey, slug))
}
