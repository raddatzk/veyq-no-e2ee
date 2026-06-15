package app.veyq.crypto

import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

/**
 * uc.memory.journal (Android) — a Memory's journal is a Markdown body sealed under the scope content key
 * (nonce || AEAD), the same envelope as node names. The server stores ciphertext only; we seal before PUT
 * and open after GET. Whole-document replace (no live collaboration in the greenfield).
 */
object Journal {
    private val client = HttpClient.newHttpClient()
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()
    private val EMPTY = ByteArray(0)

    data class Content(val markdown: String, val updatedAt: String?)

    /** Load and decrypt a memory's journal Markdown ("" when none has been written yet). */
    fun getJournal(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: ByteArray, nodeId: String): Content {
        val req = HttpRequest.newBuilder(URI.create("$apiBase/api/scopes/$scopeId/nodes/$nodeId/journal"))
            .header("Authorization", "Bearer ${session.sessionToken}").GET().build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) throw RuntimeException("get journal failed: ${resp.statusCode()} ${resp.body()}")
        val body = JSONObject(resp.body())
        if (body.isNull("sealedContent")) return Content("", null)
        val sealed = dec.decode(body.getJSONObject("sealedContent").getString("bytes"))
        val pt = V1.aeadOpen(contentKey, sealed.copyOfRange(0, 12), sealed.copyOfRange(12, sealed.size), EMPTY)
        return Content(String(pt, Charsets.UTF_8), if (body.isNull("updatedAt")) null else body.getString("updatedAt"))
    }

    /** Seal and store a memory's journal Markdown (whole-document replace). */
    fun updateJournal(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: ByteArray, nodeId: String, markdown: String): Content {
        val nonce = V1.randomBytes(12)
        val sealed = nonce + V1.aeadSeal(contentKey, nonce, markdown.toByteArray(Charsets.UTF_8), EMPTY)
        val payload = JSONObject().put("sealedContent", JSONObject().put("suiteId", "v1").put("bytes", enc.encodeToString(sealed))).toString()
        val req = HttpRequest.newBuilder(URI.create("$apiBase/api/scopes/$scopeId/nodes/$nodeId/journal"))
            .header("Authorization", "Bearer ${session.sessionToken}").header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(payload)).build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) throw RuntimeException("update journal failed: ${resp.statusCode()} ${resp.body()}")
        val body = JSONObject(resp.body())
        return Content(markdown, if (body.isNull("updatedAt")) null else body.getString("updatedAt"))
    }
}
