package app.veyq.crypto

import org.json.JSONObject
import uniffi.yniffi.YrsDoc
import uniffi.yniffi.YrsText
import uniffi.yniffi.YrsTransaction
import java.net.URI
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.concurrent.CompletionStage

/**
 * Operator-blind live-journal client for Android, wire-compatible with web + iOS. It runs the SAME Yjs CRDT
 * natively via Yrs (the uniffi-generated y-crdt binding), speaks the IDENTICAL y-websocket sync framing the
 * web/iOS use, and AEAD-seals every message under the scope content key before it hits the relay. So all
 * three platforms edit the same document through the dumb, ciphertext-only relay. String-facing API:
 * `onText` pushes remote merges up; `applyLocalText` diffs the editor value into minimal CRDT ops.
 */
// `urlFactory` is called on EVERY (re)connect so each handshake gets a fresh single-use relay ticket (a
// baked-in URL would break reconnection once its ticket is consumed). It runs on a background thread, so a
// blocking ticket fetch is fine. Share connections return a constant URL (the `?share=` token is reusable).
class JournalLiveProvider(private val urlFactory: () -> String?, private val contentKey: ByteArray) {
    private val doc = YrsDoc()
    private val text: YrsText = doc.getText("journal")
    // The one monitor. It already serialized doc transactions (tx{}); it now ALSO guards every shared field
    // below (lastSentSV, the awareness maps, local* presence state), which are otherwise written from the
    // websocket-listener thread AND the editor/persistence threads concurrently. Compute outgoing bytes under
    // the lock, then send()/invoke callbacks OUTSIDE it (network/UI must not run while holding the monitor).
    private val lock = Any()
    @Volatile private var ws: WebSocket? = null
    private var lastSentSV: ByteArray = ByteArray(0)
    @Volatile private var closed = false
    // Bumped at the start of every connect(); a stale connect/listener (superseded by a newer one) sees a
    // mismatch and neither installs its socket nor schedules a reconnect — so overlapping retries can't leak
    // a second live socket.
    @Volatile private var generation = 0

    // Awareness (presence + cursor) — the y-protocols/awareness wire format, byte-identical to web/iOS, so
    // co-editors and their caret positions sync across all three platforms. The cursor is a plain UTF-16 char
    // index (`cursorIndex`); the web's yCollab uses Yjs relative positions, so cross-family shows presence (the
    // `user` field) and same-family shows exact carets.
    data class Peer(val clientId: Long, val name: String, val color: String, val cursor: Int?)
    private val localClientId: Long = (Math.abs(java.util.UUID.randomUUID().mostSignificantBits) % 0x7FFFFFFFL) + 1
    private var localClock: Long = 0
    private var localUser: JSONObject? = null
    private var localCursor: Int? = null
    private val peers = LinkedHashMap<Long, Peer>()
    private val peerClocks = HashMap<Long, Long>()

    var onText: ((String) -> Unit)? = null
    var onStatus: ((Boolean) -> Unit)? = null
    var onPeers: ((List<Peer>) -> Unit)? = null

    // Operator-blind persistence (the CRDT store: sealed snapshot + sealed update log). Load reconstructs the
    // doc; flush appends edits since the last persist; compact writes a fresh snapshot + Markdown projection.
    private var persistApiBase: String? = null
    private var persistSession: Login.UnlockedSession? = null
    private var persistScopeId = ""
    private var persistNodeId = ""
    private var lastPersistedSV: ByteArray = ByteArray(0)
    @Volatile var persistMaxSeq = 0; private set

    init { connect() }

    val currentText: String get() = tx { text.getString(it) }

    /** Apply the editor's new value as minimal insert/remove ops (UTF-8 offsets) and broadcast the update. */
    fun applyLocalText(newText: String) {
        // The read-diff-apply must be atomic: a concurrent edit (remote apply) between reading `old` and the
        // mutation would otherwise make the offsets stale and corrupt the text. Hold the lock across all three.
        synchronized(lock) {
            val old = currentText
            if (old == newText) return
            var p = 0
            while (p < old.length && p < newText.length && old[p] == newText[p]) p++
            var s = 0
            while (s < old.length - p && s < newText.length - p && old[old.length - 1 - s] == newText[newText.length - 1 - s]) s++
            val prefixBytes = old.substring(0, p).toByteArray(Charsets.UTF_8).size.toUInt()
            val removedBytes = old.substring(p, old.length - s).toByteArray(Charsets.UTF_8).size.toUInt()
            val inserted = newText.substring(p, newText.length - s)
            tx { txn ->
                if (removedBytes > 0u) text.removeRange(txn, prefixBytes, removedBytes)
                if (inserted.isNotEmpty()) text.insert(txn, prefixBytes, inserted)
            }
        }
        broadcastUpdate()
    }

    /** Seed an empty shared doc from the sealed journal (call once after a short sync settle, like web/iOS). */
    fun seedIfEmpty(markdown: String) { if (currentText.isEmpty() && markdown.isNotEmpty()) applyLocalText(markdown) }

    /** Publish this editor's identity (name + colour) to co-editors. */
    fun setUser(name: String, color: String) {
        val bytes = synchronized(lock) {
            localUser = JSONObject().put("name", name).put("color", color).put("colorLight", color + "33")
            bumpAwarenessLocked()
        }
        bytes?.let { send(it) }
    }
    /** Publish this editor's caret position (char index) so others can draw it. */
    fun setCursor(index: Int?) {
        val bytes = synchronized(lock) {
            if (localCursor == index) return
            localCursor = index
            bumpAwarenessLocked()
        }
        bytes?.let { send(it) }
    }

    fun destroy() { closed = true; ws?.sendClose(WebSocket.NORMAL_CLOSURE, "") }

    // --- websocket ---

    private fun connect() {
        val gen = synchronized(lock) { ++generation } // supersede any in-flight connect/listener
        // Off-thread: fetch a fresh ticket (blocking) then open the socket; init/reconnect never block the caller.
        Thread {
            if (closed || gen != generation) return@Thread
            val u = runCatching { urlFactory() }.getOrNull()
            if (closed || gen != generation) return@Thread
            if (u == null) { reconnectAfterDelay(gen); return@Thread }
            // Reuse the shared HttpClient (one pool/executor) instead of newHttpClient() per connect — a flaky
            // network's reconnect loop would otherwise leak a client (+ its threads) on every retry.
            ApiClient.shared.newWebSocketBuilder()
                .buildAsync(URI.create(u), Listener(gen))
                .whenComplete { socket, err ->
                    if (closed || gen != generation) { socket?.sendClose(WebSocket.NORMAL_CLOSURE, ""); return@whenComplete }
                    if (err == null) { ws = socket; onStatus?.invoke(true); onOpen() } else reconnectAfterDelay(gen)
                }
        }.start()
    }

    private fun reconnectAfterDelay(gen: Int) { if (!closed && gen == generation) Thread { Thread.sleep(1500); connect() }.start() }

    private fun onOpen() {
        // STEP_1 (our state vector) so peers send what we're missing, plus our presence — built under the lock.
        val (s1, aw) = synchronized(lock) {
            val sv = stateVector()
            lastSentSV = sv
            encode(0, sv) to (if (localUser != null) encodeAwareness() else null)
        }
        send(s1)
        aw?.let { send(it) }
    }

    private inner class Listener(private val gen: Int) : WebSocket.Listener {
        private var acc = ByteArray(0)
        override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
            val chunk = ByteArray(data.remaining()); data.get(chunk); acc += chunk
            if (last) { val msg = acc; acc = ByteArray(0); runCatching { handle(msg) } }
            webSocket.request(1)
            return null
        }
        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            onStatus?.invoke(false)
            reconnectAfterDelay(gen)
            return null
        }
        override fun onError(webSocket: WebSocket, error: Throwable?) { onStatus?.invoke(false) }
    }

    private fun handle(data: ByteArray) {
        val plain = open(data) ?: return
        val pos = intArrayOf(0)
        val msgType = readVarUint(plain, pos)
        if (msgType == 1L) { // awareness (presence + cursor)
            readVarUint8Array(plain, pos)?.let { applyAwareness(it) }
            return
        }
        if (msgType != 0L) return // 0 = sync
        val syncKind = readVarUint(plain, pos)
        val payload = readVarUint8Array(plain, pos) ?: return
        when (syncKind) {
            0L -> { // step1 → reply step2 (the update covering what the peer is missing)
                val reply = synchronized(lock) { encode(1, tx { it.transactionEncodeStateAsUpdateFromSv(payload.toUByteList()).toByteArray() }) }
                send(reply)
            }
            1L, 2L -> { // step2 / update → apply, then push the merged text up
                synchronized(lock) {
                    tx { it.transactionApplyUpdate(payload.toUByteList()) }
                    lastSentSV = stateVector()
                }
                onText?.invoke(currentText)
            }
        }
    }

    // --- operator-blind persistence ---

    fun configurePersistence(apiBase: String, session: Login.UnlockedSession, scopeId: String, nodeId: String) {
        persistApiBase = apiBase; persistSession = session; persistScopeId = scopeId; persistNodeId = nodeId
    }

    /** Reconstruct the doc from the store (snapshot + update log); returns the max seq applied. Blocking. */
    fun loadPersisted(): Int {
        val api = persistApiBase ?: return 0
        val s = persistSession ?: return 0
        persistMaxSeq = runCatching { JournalPersistence.loadJournalDoc(api, s, persistScopeId, persistNodeId, contentKey, doc, lock) }.getOrDefault(0)
        synchronized(lock) { lastPersistedSV = stateVector() }
        onText?.invoke(currentText)
        return persistMaxSeq
    }

    /** Append the edits since the last persist as one sealed update (local + any relay-applied; idempotent). */
    fun flushPersist() {
        val api = persistApiBase ?: return
        val s = persistSession ?: return
        val diff = synchronized(lock) { tx { it.transactionEncodeStateAsUpdateFromSv(lastPersistedSV.toUByteList()).toByteArray() } }
        if (diff.isEmpty()) return
        runCatching { JournalPersistence.appendJournalUpdate(api, s, persistScopeId, persistNodeId, contentKey, diff) }
            .onSuccess { synchronized(lock) { persistMaxSeq = maxOf(persistMaxSeq, it); lastPersistedSV = stateVector() } }
    }

    /** Compact: write a fresh snapshot + the Markdown reader projection and prune the folded-in updates. */
    fun compactPersisted() {
        val api = persistApiBase ?: return
        val s = persistSession ?: return
        // Reload the full log into the doc first so the snapshot covers every update <= coveredSeq (a seq another
        // editor appended that this doc never folded in would otherwise be pruned but missing from the snapshot).
        loadPersisted()
        runCatching { JournalPersistence.compactJournal(api, s, persistScopeId, persistNodeId, contentKey, doc, lock, currentText, persistMaxSeq) }
    }

    private fun broadcastUpdate() {
        val bytes = synchronized(lock) {
            val update = tx { it.transactionEncodeStateAsUpdateFromSv(lastSentSV.toUByteList()).toByteArray() }
            if (update.isEmpty()) return
            lastSentSV = stateVector()
            encode(2, update)
        }
        send(bytes)
    }

    private fun <T> tx(block: (YrsTransaction) -> T): T = synchronized(lock) { doc.transact(null).use { block(it) } }
    private fun stateVector(): ByteArray = tx { it.transactionStateVector().toByteArray() }

    private fun send(bytes: ByteArray) { ws?.sendBinary(ByteBuffer.wrap(bytes), true) }

    // --- framing (lib0 varint, matches web/iOS) + AEAD ---

    /** salt(16) || nonce(12) || AEAD(HKDF(contentKey, salt), inner) — fresh per-message sub-key (see V1.journalSeal). */
    private fun seal(inner: ByteArray): ByteArray = V1.journalSeal(contentKey, inner)
    /** [varuint 0=sync][varuint syncKind][varUint8Array payload] → sealed. */
    private fun encode(syncKind: Long, payload: ByteArray): ByteArray {
        val enc = ArrayList<Byte>()
        writeVarUint(enc, 0); writeVarUint(enc, syncKind); writeVarUint8Array(enc, payload)
        return seal(enc.toByteArray())
    }
    /** [varuint 1=awareness][varUint8Array update], where update = [count][clientId][clock][varString(json)]. */
    private fun encodeAwareness(): ByteArray {
        val state = JSONObject().put("user", localUser ?: JSONObject())
        localCursor?.let { state.put("cursorIndex", it) }
        val json = state.toString().toByteArray(Charsets.UTF_8)
        val aw = ArrayList<Byte>()
        writeVarUint(aw, 1); writeVarUint(aw, localClientId); writeVarUint(aw, localClock)
        writeVarUint8Array(aw, json)
        val enc = ArrayList<Byte>()
        writeVarUint(enc, 1); writeVarUint8Array(enc, aw.toByteArray())
        return seal(enc.toByteArray())
    }
    /** Bump the awareness clock and return the encoded presence frame to send (or null if not connected). Caller
     * MUST hold [lock] (it mutates localClock and reads localUser/localCursor); send the result outside the lock. */
    private fun bumpAwarenessLocked(): ByteArray? { localClock++; return if (ws != null) encodeAwareness() else null }
    private fun applyAwareness(update: ByteArray) {
        val snapshot = synchronized(lock) {
            val p = intArrayOf(0)
            val count = readVarUint(update, p)
            var i = 0L
            while (i < count) {
                i++
                val clientId = readVarUint(update, p)
                val clock = readVarUint(update, p)
                val jsonBytes = readVarUint8Array(update, p) ?: break
                if (clientId == localClientId) continue
                val prev = peerClocks[clientId]
                if (prev != null && prev >= clock) continue
                peerClocks[clientId] = clock
                val obj = runCatching { JSONObject(String(jsonBytes, Charsets.UTF_8)) }.getOrNull()
                val user = obj?.optJSONObject("user")
                if (user != null) {
                    val cursor = if (obj.has("cursorIndex")) obj.optInt("cursorIndex") else null
                    peers[clientId] = Peer(clientId, user.optString("name", "?"), user.optString("color", "#888888"), cursor)
                } else {
                    peers.remove(clientId)
                }
            }
            peers.values.toList()
        }
        onPeers?.invoke(snapshot)
    }
    private fun open(data: ByteArray): ByteArray? = V1.journalOpen(contentKey, data)

    private fun writeVarUint(out: MutableList<Byte>, value: Long) {
        var v = value
        while (v > 0x7F) { out.add(((v and 0x7F) or 0x80).toByte()); v = v ushr 7 }
        out.add((v and 0x7F).toByte())
    }
    private fun writeVarUint8Array(out: MutableList<Byte>, bytes: ByteArray) {
        writeVarUint(out, bytes.size.toLong()); for (b in bytes) out.add(b)
    }
    private fun readVarUint(bytes: ByteArray, pos: IntArray): Long {
        var num = 0L; var shift = 0
        while (pos[0] < bytes.size) {
            val b = bytes[pos[0]].toInt() and 0xFF; pos[0]++
            num = num or ((b.toLong() and 0x7F) shl shift)
            if (b < 0x80) break
            shift += 7
        }
        return num
    }
    private fun readVarUint8Array(bytes: ByteArray, pos: IntArray): ByteArray? {
        val len = readVarUint(bytes, pos).toInt()
        if (pos[0] + len > bytes.size) return null
        val out = bytes.copyOfRange(pos[0], pos[0] + len); pos[0] += len; return out
    }

    private fun ByteArray.toUByteList(): List<UByte> = map { it.toUByte() }
    private fun List<UByte>.toByteArray(): ByteArray = ByteArray(size) { this[it].toByte() }
}
