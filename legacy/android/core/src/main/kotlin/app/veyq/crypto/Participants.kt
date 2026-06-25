package app.veyq.crypto

import org.json.JSONObject
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

/**
 * uc.people.manage-participants (Android, ADR 0032 operator-blind) — Memory participants are sealed People
 * metadata. The server stores an opaque payload {displayName, linkedUserId?}; linked-vs-anonymous semantics are
 * decrypted locally. Wire/seal format matches iOS/web/backend (nonce || AEAD over the JSON payload, AAD empty).
 * Blocking HTTP — call off the main thread.
 */
object Participants {
    private val client = ApiClient.shared
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()
    private val EMPTY = ByteArray(0)

    data class Item(val id: String, val displayName: String, val linkedUserId: String?)

    /** A node a linked participant appears in — drives the participant profile screen. */
    data class NodeHit(val nodeId: String, val name: String, val type: String, val displayName: String)

    private fun seal(contentKey: ByteArray, displayName: String, linkedUserId: String?): JSONObject {
        val payload = JSONObject().put("displayName", displayName)
        if (!linkedUserId.isNullOrEmpty()) payload.put("linkedUserId", linkedUserId)
        val nonce = V1.randomBytes(12)
        val sealed = nonce + V1.aeadSeal(contentKey, nonce, payload.toString().toByteArray(Charsets.UTF_8), EMPTY)
        return JSONObject().put("suiteId", "v1").put("bytes", enc.encodeToString(sealed))
    }

    private fun decode(body: String, contentKey: ByteArray): List<Item> {
        val arr = JSONObject(body).getJSONArray("participants")
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                val sealed = dec.decode(o.getJSONObject("sealedPayload").getString("bytes"))
                val pt = V1.aeadOpen(contentKey, sealed.copyOfRange(0, 12), sealed.copyOfRange(12, sealed.size), EMPTY)
                val p = JSONObject(String(pt, Charsets.UTF_8))
                Item(o.getString("id"), p.getString("displayName"), p.optString("linkedUserId", null))
            }.getOrNull()
        }
    }

    private fun req(uri: String, token: String) = HttpRequest.newBuilder(URI.create(uri)).header("Authorization", "Bearer $token")

    /** List + decrypt a memory's participants. */
    fun list(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: ByteArray, nodeId: String): List<Item> {
        val resp = client.send(req("$apiBase/api/scopes/$scopeId/nodes/$nodeId/participants", session.sessionToken).GET().build(), HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) throw RuntimeException("list participants failed: ${resp.statusCode()}")
        return decode(resp.body(), contentKey)
    }

    /** Seal {displayName, linkedUserId?} and create a participant. */
    fun create(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: ByteArray, nodeId: String, displayName: String, linkedUserId: String? = null) {
        val body = JSONObject().put("sealedPayload", seal(contentKey, displayName, linkedUserId)).toString()
        val resp = client.send(req("$apiBase/api/scopes/$scopeId/nodes/$nodeId/participants", session.sessionToken).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) throw RuntimeException("create participant failed: ${resp.statusCode()}")
    }

    fun delete(apiBase: String, session: Login.UnlockedSession, scopeId: String, nodeId: String, participantId: String) {
        val resp = client.send(req("$apiBase/api/scopes/$scopeId/nodes/$nodeId/participants/$participantId", session.sessionToken).DELETE().build(), HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 204 && resp.statusCode() != 200) throw RuntimeException("delete participant failed: ${resp.statusCode()}")
    }

    /** All non-folder nodes a linked participant appears in (client-side scan — the server is blind to participants). */
    fun listNodeHits(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: ByteArray, linkedUserId: String): List<NodeHit> {
        val nodes = MemoryTree.listNodes(apiBase, session, scopeId)
        val hits = mutableListOf<NodeHit>()
        for (i in 0 until nodes.length()) {
            val node = nodes.getJSONObject(i)
            val type = node.getString("type")
            if (type == "FOLDER") continue
            val nodeId = node.getString("id")
            val participants = runCatching { list(apiBase, session, scopeId, contentKey, nodeId) }.getOrDefault(emptyList())
            val match = participants.firstOrNull { it.linkedUserId == linkedUserId } ?: continue
            val name = runCatching { MemoryTree.decodeNodeName(contentKey, node) }.getOrDefault(nodeId)
            hits.add(NodeHit(nodeId, name, type, match.displayName))
        }
        return hits.sortedBy { it.name.lowercase() }
    }
}
