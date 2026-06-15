package app.veyq.crypto

import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

/**
 * uc.memory.share (Android) — owner side. Create a read-only public link for a Memory: the scope content key
 * is wrapped under a random per-share key, which rides the returned link's fragment (/s/<id>#k=…) and never
 * reaches the server. The web viewer at the instance origin opens it; the server stays blind to the content.
 */
object Share {
    private val client = HttpClient.newHttpClient()
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val EMPTY = ByteArray(0)

    /** Create a share and return the public link (origin = the instance base URL serving the web viewer). */
    fun createShare(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: ByteArray, nodeId: String, origin: String): String {
        val shareKey = V1.randomBytes(32)
        val nonce = V1.randomBytes(12)
        val wrapped = nonce + V1.aeadSeal(shareKey, nonce, contentKey, EMPTY)
        val payload = JSONObject().put("wrappedContentKey", JSONObject().put("suiteId", "v1").put("bytes", enc.encodeToString(wrapped))).toString()
        val req = HttpRequest.newBuilder(URI.create("$apiBase/api/scopes/$scopeId/nodes/$nodeId/shares"))
            .header("Authorization", "Bearer ${session.sessionToken}").header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload)).build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) throw RuntimeException("create share failed: ${resp.statusCode()} ${resp.body()}")
        val shareId = JSONObject(resp.body()).getString("shareId")
        return "${origin.trimEnd('/')}/s/$shareId#k=${enc.encodeToString(shareKey)}"
    }
}
