package app.veyq.crypto

import org.json.JSONObject
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

/**
 * uc.journal.write (Android) — the Markdown READ projection of a Memory's journal, sealed under the scope
 * content key (nonce || AEAD). The server stores ciphertext only. Editing goes through the operator-blind
 * CRDT store (JournalPersistence); this is the read path (detail view) that opens the compacted Markdown.
 */
object Journal {
    private val client = ApiClient.shared
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()
    private val EMPTY = ByteArray(0)

    data class Content(val markdown: String, val updatedAt: String?)

    /** Load and decrypt a memory's journal Markdown ("" when none has been written yet). */
    fun getJournal(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: ByteArray, nodeId: String): Content {
        val req = HttpRequest.newBuilder(URI.create("$apiBase/api/scopes/$scopeId/nodes/$nodeId/journal"))
            .header("Authorization", "Bearer ${session.sessionToken}").GET().build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) throw RuntimeException("get journal failed: ${resp.statusCode()}")
        val body = JSONObject(resp.body())
        if (body.isNull("sealedContent")) return Content("", null)
        val sealed = dec.decode(body.getJSONObject("sealedContent").getString("bytes"))
        val pt = V1.aeadOpen(contentKey, sealed.copyOfRange(0, 12), sealed.copyOfRange(12, sealed.size), EMPTY)
        return Content(String(pt, Charsets.UTF_8), if (body.isNull("updatedAt")) null else body.getString("updatedAt"))
    }
}
