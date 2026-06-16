package com.veyq.app.features.memory.journallive

import com.veyq.app.features.memory.share.ShareHandler
import com.veyq.app.shared.content.NodeRepository
import com.veyq.app.shared.content.ShareRepository
import com.veyq.app.shared.security.ScopeAccessGuard
import com.veyq.app.shared.security.SessionService
import io.quarkus.websockets.next.OnBinaryMessage
import io.quarkus.websockets.next.OnClose
import io.quarkus.websockets.next.OnOpen
import io.quarkus.websockets.next.OpenConnections
import io.quarkus.websockets.next.WebSocket
import io.quarkus.websockets.next.WebSocketConnection
import io.smallrye.common.annotation.Blocking
import io.vertx.core.buffer.Buffer
import jakarta.transaction.Transactional
import java.util.concurrent.ConcurrentHashMap

/**
 * uc.memory.journal-live — an OPERATOR-BLIND relay for live (CRDT) journal editing. The server only
 * authenticates the connection (a valid session that holds the scope grant) and then broadcasts every
 * message verbatim to the other members in the same room (scope + node). The payloads are Yjs sync/awareness
 * messages the clients have AEAD-sealed under the scope content key — the server never parses or sees them.
 * It holds no document state: the CRDT lives peer-to-peer; persistence is the existing sealed-journal save.
 *
 * Even though every connection is an authenticated scope member, a member is not trusted to be well-behaved:
 * a buggy or malicious client could flood the room and amplify load via fan-out. So we bound it — a per-room
 * peer cap and a per-connection rate limit (messages + bytes per second). Anyone over the limit is closed;
 * the generous thresholds sit far above real live-editing traffic.
 */
@WebSocket(path = "/ws/journal/{scopeId}/{nodeId}")
class JournalRelay(
    private val sessions: SessionService,
    private val guard: ScopeAccessGuard,
    private val connections: OpenConnections,
    private val shares: ShareRepository,
    private val nodes: NodeRepository,
) {
    private companion object {
        const val MAX_PEERS_PER_ROOM = 32          // bound fan-out amplification
        const val MAX_MSGS_PER_SEC = 100           // ~5x the busiest real typing (keystroke = sync + awareness)
        const val MAX_BYTES_PER_SEC = 4L * 1024 * 1024
        const val WINDOW_NANOS = 1_000_000_000L
    }

    /** Per-connection fixed-window counter (1s) for the rate limit; dropped on close. */
    private class Window { var startNanos = System.nanoTime(); var msgs = 0; var bytes = 0L }
    private val windows = ConcurrentHashMap<String, Window>()

    /** Connection ids of read-only share guests — they may RECEIVE live edits but never broadcast (no writes). */
    private val receiveOnly = ConcurrentHashMap.newKeySet<String>()

    private fun room(c: WebSocketConnection) = c.pathParam("scopeId") + "/" + c.pathParam("nodeId")
    private fun inRoom(c: WebSocketConnection, sender: WebSocketConnection) =
        c.pathParam("scopeId") == sender.pathParam("scopeId") && room(c) == room(sender)

    /**
     * Authenticate on connect. A scope MEMBER (session token + active grant) may read AND write. A read-capable
     * public-share guest (`?share=<id>` over `canRead` + the shared subtree) may live-READ only — it is marked
     * receive-only, so journal editing stays member-only. Anyone else is closed.
     */
    @OnOpen
    @Blocking
    @Transactional
    fun onOpen(connection: WebSocketConnection) {
        val scopeId = connection.pathParam("scopeId")
        val nodeId = connection.pathParam("nodeId")
        val userId = sessions.resolve(queryParam(connection, "token")?.let { "Bearer $it" })
        val member = userId != null && guard.isActiveKeyHolder(userId, scopeId)
        if (!member) {
            val shareId = queryParam(connection, "share")
            if (shareId != null && canReadViaShare(shareId, scopeId, nodeId)) {
                receiveOnly.add(connection.id())
            } else {
                connection.closeAndAwait()
                return
            }
        }
        val roomPeers = connections.listAll().count { it.id() != connection.id() && inRoom(it, connection) }
        if (roomPeers >= MAX_PEERS_PER_ROOM) { // room full → shed load
            receiveOnly.remove(connection.id())
            connection.closeAndAwait()
        }
    }

    /** A non-expired share with read rights whose subtree contains this room's node (same gate as the HTTP read). */
    private fun canReadViaShare(shareId: String, scopeId: String, nodeId: String): Boolean {
        val share = shares.findById(shareId) ?: return false
        if (ShareHandler.isExpired(share) || !ShareHandler.canRead(share.role) || share.scopeId != scopeId) return false
        return nodeId in ShareHandler.subtreeIds(nodes.listByScopeId(scopeId), share.nodeId)
    }

    /** Relay one opaque (client-encrypted) CRDT message to every other connection in the same room. */
    @OnBinaryMessage
    fun onMessage(message: Buffer, sender: WebSocketConnection) {
        if (sender.id() in receiveOnly) return // read-only share guests receive but never broadcast
        if (!allow(sender, message.length().toLong())) {
            windows.remove(sender.id())
            runCatching { sender.closeAndAwait() } // over the rate limit → drop this abuser
            return
        }
        connections.listAll()
            .filter { it.id() != sender.id() && inRoom(it, sender) }
            .forEach { runCatching { it.sendBinaryAndAwait(message) } }
    }

    /** Fixed-window rate check: false once this connection exceeds messages OR bytes within the 1s window. */
    private fun allow(sender: WebSocketConnection, size: Long): Boolean {
        val w = windows.getOrPut(sender.id()) { Window() }
        synchronized(w) {
            val now = System.nanoTime()
            if (now - w.startNanos > WINDOW_NANOS) { w.startNanos = now; w.msgs = 0; w.bytes = 0 }
            w.msgs++; w.bytes += size
            return w.msgs <= MAX_MSGS_PER_SEC && w.bytes <= MAX_BYTES_PER_SEC
        }
    }

    @OnClose
    fun onClose(connection: WebSocketConnection) { windows.remove(connection.id()); receiveOnly.remove(connection.id()) }

    private fun queryParam(c: WebSocketConnection, name: String): String? =
        c.handshakeRequest().query()?.split("&")?.firstNotNullOfOrNull {
            val kv = it.split("=", limit = 2)
            if (kv.size == 2 && kv[0] == name) java.net.URLDecoder.decode(kv[1], Charsets.UTF_8) else null
        }
}
