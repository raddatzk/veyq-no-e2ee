package app.veyq.crypto

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
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
        val sealed = sealContent(contentKey, name, slug)
        // the slug token's parent slot is the parent node id, or the scope id at the root (the server's sentinel)
        val parentSlot = parentId ?: scopeId
        val parentPart = if (parentId == null) "" else """"parentId":"$parentId","""
        val json = """{$parentPart"type":"$type","slugToken":"${enc.encodeToString(V1.slugToken(contentKey, parentSlot, slug))}",""" +
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

    /** Seal a node's {name, slug} metadata under the content key (nonce || AEAD). */
    private fun sealContent(contentKey: ByteArray, name: String, slug: String): ByteArray {
        val nonce = V1.randomBytes(12)
        val payload = JSONObject().put("name", name).put("slug", slug).toString().toByteArray(Charsets.UTF_8)
        return nonce + V1.aeadSeal(contentKey, nonce, payload, EMPTY)
    }

    /** Decrypt a node's sealed metadata ({name, slug}) with the scope content key. */
    fun decodeNode(contentKey: ByteArray, node: JSONObject): JSONObject {
        val sealed = dec.decode(node.getJSONObject("sealedContent").getString("bytes"))
        val pt = V1.aeadOpen(contentKey, sealed.copyOfRange(0, 12), sealed.copyOfRange(12, sealed.size), EMPTY)
        return JSONObject(String(pt, Charsets.UTF_8))
    }

    /** Convenience: a node's decrypted display name. */
    fun decodeNodeName(contentKey: ByteArray, node: JSONObject): String = decodeNode(contentKey, node).getString("name")

    /** Re-seal a node's metadata under a content key, returning the new sealed blob (nonce || AEAD). */
    fun resealNode(contentKey: ByteArray, content: JSONObject): ByteArray =
        sealContent(contentKey, content.getString("name"), content.getString("slug"))

    /** The blinded slug token for a slug under a given parent slot (parent node id, or scope id at the root). */
    fun slugTokenFor(contentKey: ByteArray, parentSlot: String, slug: String): String =
        enc.encodeToString(V1.slugToken(contentKey, parentSlot, slug))
}
