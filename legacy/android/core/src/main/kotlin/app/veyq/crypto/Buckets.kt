package app.veyq.crypto

import org.json.JSONObject
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

/**
 * uc.contributions (Android, ADR 0023) — per-contributor media buckets. Bucket names are sealed under the scope
 * content key (operator-blind), like node names. A member's primary bucket is synthesized server-side (no
 * sealed name — the client labels it with the member's display name); sub-buckets + guest primaries are
 * persisted with sealed names. Create/rename/delete + media moves are own-bucket only (enforced server-side).
 */
object Buckets {
    private val client = ApiClient.shared
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()
    private val EMPTY = ByteArray(0)

    data class Bucket(val id: String, val ownerKey: String, val parentBucketId: String?, val sealedName: JSONObject?, val primary: Boolean, val mediaCount: Int)

    /** Decrypt a bucket's sealed name, or "" when absent (synthesized member primary) / undecryptable. */
    fun name(contentKey: ByteArray, bucket: Bucket): String {
        val sn = bucket.sealedName ?: return ""
        return runCatching {
            val sealed = dec.decode(sn.getString("bytes"))
            String(V1.aeadOpen(contentKey, sealed.copyOfRange(0, 12), sealed.copyOfRange(12, sealed.size), EMPTY), Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun sealedName(contentKey: ByteArray, name: String): JSONObject {
        val nonce = V1.randomBytes(12)
        val sealed = nonce + V1.aeadSeal(contentKey, nonce, name.toByteArray(Charsets.UTF_8), EMPTY)
        return JSONObject().put("suiteId", "v1").put("bytes", enc.encodeToString(sealed))
    }

    private fun req(uri: String, token: String) = HttpRequest.newBuilder(URI.create(uri)).header("Authorization", "Bearer $token")

    fun list(apiBase: String, session: Login.UnlockedSession, scopeId: String, nodeId: String): List<Bucket> {
        val resp = client.send(req("$apiBase/api/scopes/$scopeId/nodes/$nodeId/buckets", session.sessionToken).GET().build(), HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) throw RuntimeException("list buckets failed: ${resp.statusCode()}")
        val arr = JSONObject(resp.body()).getJSONArray("buckets")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Bucket(o.getString("id"), o.getString("ownerKey"), o.optString("parentBucketId", null), o.optJSONObject("sealedName"), o.getBoolean("primary"), o.getInt("mediaCount"))
        }
    }

    /** Create a sub-bucket under the caller's own primary (sealed name). */
    fun create(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: ByteArray, nodeId: String, name: String) {
        val body = JSONObject().put("sealedName", sealedName(contentKey, name)).toString()
        val resp = client.send(req("$apiBase/api/scopes/$scopeId/nodes/$nodeId/buckets", session.sessionToken).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) throw RuntimeException("create bucket failed: ${resp.statusCode()}")
    }

    fun rename(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: ByteArray, nodeId: String, bucketId: String, name: String) {
        val body = JSONObject().put("sealedName", sealedName(contentKey, name)).toString()
        val resp = client.send(req("$apiBase/api/scopes/$scopeId/nodes/$nodeId/buckets/$bucketId", session.sessionToken).header("Content-Type", "application/json").method("PATCH", HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) throw RuntimeException("rename bucket failed: ${resp.statusCode()}")
    }

    fun delete(apiBase: String, session: Login.UnlockedSession, scopeId: String, nodeId: String, bucketId: String) {
        val resp = client.send(req("$apiBase/api/scopes/$scopeId/nodes/$nodeId/buckets/$bucketId", session.sessionToken).DELETE().build(), HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 204 && resp.statusCode() != 200) throw RuntimeException("delete bucket failed: ${resp.statusCode()}")
    }
}
