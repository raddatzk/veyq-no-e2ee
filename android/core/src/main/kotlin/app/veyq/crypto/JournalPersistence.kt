package app.veyq.crypto

import org.json.JSONObject
import uniffi.yniffi.YrsDoc
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

/**
 * uc.memory.journal-live persistence (Android) — the operator-blind CRDT store behind the live editor,
 * wire-compatible with web/iOS. The journal is a Yrs document; the server keeps a sealed Yrs *state snapshot*
 * plus an append-only log of sealed Yrs *updates* (it never decrypts either). A client reconstructs the
 * document by applying the snapshot then the updates, appends a sealed update per local edit, and on close
 * compacts (a fresh snapshot + the Markdown reader projection) which prunes the folded-in updates.
 */
object JournalPersistence {
    private val client = HttpClient.newHttpClient()
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()

    private fun ByteArray.toUByteList(): List<UByte> = map { it.toUByte() }
    private fun List<UByte>.toByteArray(): ByteArray = ByteArray(size) { this[it].toByte() }

    // salt(16) || nonce(12) || AEAD(HKDF(key, salt, "veyq/v1/journal"), …) — fresh per-message sub-key so the GCM
    // nonce can never collide under the long-lived content key (see V1.journalSeal).
    private fun seal(key: ByteArray, inner: ByteArray): ByteArray = V1.journalSeal(key, inner)
    private fun open(key: ByteArray, blob: ByteArray): ByteArray? = V1.journalOpen(key, blob)

    private fun get(apiBase: String, session: Login.UnlockedSession, path: String): JSONObject {
        val req = HttpRequest.newBuilder(URI.create("$apiBase$path")).header("Authorization", "Bearer ${session.sessionToken}").GET().build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) throw RuntimeException("journal GET failed: ${resp.statusCode()} ${resp.body()}")
        return JSONObject(resp.body())
    }

    /**
     * Exchange the Bearer session for a short-lived single-use relay ticket, so the live-journal WebSocket
     * authenticates via `?ticket=` instead of putting the session token in the URL (proxy logs / history).
     */
    fun fetchLiveTicket(apiBase: String, session: Login.UnlockedSession): String {
        val req = HttpRequest.newBuilder(URI.create("$apiBase/api/journal-live/ticket"))
            .header("Authorization", "Bearer ${session.sessionToken}")
            .POST(HttpRequest.BodyPublishers.noBody()).build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) throw RuntimeException("journal-live ticket failed: ${resp.statusCode()}")
        return JSONObject(resp.body()).getString("ticket")
    }
    private fun send(apiBase: String, session: Login.UnlockedSession, path: String, method: String, body: String): JSONObject {
        val b = HttpRequest.BodyPublishers.ofString(body)
        val req = HttpRequest.newBuilder(URI.create("$apiBase$path"))
            .header("Authorization", "Bearer ${session.sessionToken}").header("Content-Type", "application/json")
            .method(method, b).build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) throw RuntimeException("journal $method failed: ${resp.statusCode()} ${resp.body()}")
        return JSONObject(resp.body())
    }

    /** Reconstruct the journal into `doc` (caller holds `lock`); returns the max seq applied. */
    fun loadJournalDoc(apiBase: String, session: Login.UnlockedSession, scopeId: String, nodeId: String, contentKey: ByteArray, doc: YrsDoc, lock: Any): Int {
        val state = get(apiBase, session, "/api/scopes/$scopeId/nodes/$nodeId/journal/live")
        var maxSeq = state.optLong("baseSeq", 0).toInt()
        synchronized(lock) {
            doc.transact(null).use { txn ->
                if (!state.isNull("snapshot")) {
                    open(contentKey, dec.decode(state.getJSONObject("snapshot").getString("bytes")))?.let { txn.transactionApplyUpdate(it.toUByteList()) }
                }
                val updates = state.getJSONArray("updates")
                for (i in 0 until updates.length()) {
                    val u = updates.getJSONObject(i)
                    open(contentKey, dec.decode(u.getJSONObject("sealedUpdate").getString("bytes")))?.let {
                        txn.transactionApplyUpdate(it.toUByteList())
                        maxSeq = maxOf(maxSeq, u.getLong("seq").toInt())
                    }
                }
            }
        }
        return maxSeq
    }

    /** Persist one local Yrs update; returns its server-assigned monotonic seq. */
    fun appendJournalUpdate(apiBase: String, session: Login.UnlockedSession, scopeId: String, nodeId: String, contentKey: ByteArray, update: ByteArray): Int {
        val body = JSONObject().put("sealedUpdate", JSONObject().put("suiteId", "v1").put("bytes", enc.encodeToString(seal(contentKey, update)))).toString()
        return send(apiBase, session, "/api/scopes/$scopeId/nodes/$nodeId/journal/updates", "POST", body).getLong("seq").toInt()
    }

    /** Compact: write a fresh sealed snapshot + the Markdown reader projection, pruning updates <= coveredSeq. */
    fun compactJournal(apiBase: String, session: Login.UnlockedSession, scopeId: String, nodeId: String, contentKey: ByteArray, doc: YrsDoc, lock: Any, markdown: String, coveredSeq: Int) {
        val full = synchronized(lock) { doc.transact(null).use { it.transactionEncodeStateAsUpdate().toByteArray() } }
        // The Markdown projection is low-volume at-rest content read by the plain getJournal reader, so it keeps
        // the plain nonce||AEAD(contentKey) envelope (not the per-message journal sub-key — that's the CRDT layer).
        val mdNonce = V1.randomBytes(12)
        val md = mdNonce + V1.aeadSeal(contentKey, mdNonce, markdown.toByteArray(Charsets.UTF_8), ByteArray(0))
        val body = JSONObject()
            .put("snapshot", JSONObject().put("suiteId", "v1").put("bytes", enc.encodeToString(seal(contentKey, full))))
            .put("markdown", JSONObject().put("suiteId", "v1").put("bytes", enc.encodeToString(md)))
            .put("coveredSeq", coveredSeq).toString()
        send(apiBase, session, "/api/scopes/$scopeId/nodes/$nodeId/journal/snapshot", "PUT", body)
    }

    /** Whole-document save (the plain, non-collaborative editor): replace the text on the current CRDT and compact. */
    fun saveJournalMarkdown(apiBase: String, session: Login.UnlockedSession, scopeId: String, nodeId: String, contentKey: ByteArray, markdown: String) {
        val doc = YrsDoc()
        val lock = Any()
        val maxSeq = loadJournalDoc(apiBase, session, scopeId, nodeId, contentKey, doc, lock)
        val text = doc.getText("journal")
        synchronized(lock) {
            doc.transact(null).use { txn ->
                val len = text.getString(txn).toByteArray(Charsets.UTF_8).size.toUInt()
                text.removeRange(txn, 0u, len)
                text.insert(txn, 0u, markdown)
            }
        }
        compactJournal(apiBase, session, scopeId, nodeId, contentKey, doc, lock, markdown, maxSeq)
    }
}
