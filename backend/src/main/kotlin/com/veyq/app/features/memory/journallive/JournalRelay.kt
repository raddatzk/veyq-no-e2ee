package com.veyq.app.features.memory.journallive

import com.veyq.app.shared.keymgmt.ScopeGrantRepository
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

/**
 * uc.memory.journal-live — an OPERATOR-BLIND relay for live (CRDT) journal editing. The server only
 * authenticates the connection (a valid session that holds the scope grant) and then broadcasts every
 * message verbatim to the other members in the same room (scope + node). The payloads are Yjs sync/awareness
 * messages the clients have AEAD-sealed under the scope content key — the server never parses or sees them.
 * It holds no document state: the CRDT lives peer-to-peer; persistence is the existing sealed-journal save.
 */
@WebSocket(path = "/ws/journal/{scopeId}/{nodeId}")
class JournalRelay(
    private val sessions: SessionService,
    private val grants: ScopeGrantRepository,
    private val connections: OpenConnections,
) {
    private fun room(c: WebSocketConnection) = c.pathParam("scopeId") + "/" + c.pathParam("nodeId")

    /** Authenticate on connect: a valid session token (query `?token=`) that holds the scope's grant. Else close. */
    @OnOpen
    @Blocking
    @Transactional
    fun onOpen(connection: WebSocketConnection) {
        val token = queryParam(connection, "token")
        val userId = sessions.resolve(token?.let { "Bearer $it" })
        val scopeId = connection.pathParam("scopeId")
        if (userId == null || grants.find(scopeId, userId) == null) {
            connection.closeAndAwait() // not a member of this scope → no room access
        }
    }

    /** Relay one opaque (client-encrypted) CRDT message to every other connection in the same room. */
    @OnBinaryMessage
    fun onMessage(message: Buffer, sender: WebSocketConnection) {
        val room = room(sender)
        connections.listAll()
            .filter { it.id() != sender.id() && it.pathParam("scopeId") == sender.pathParam("scopeId") && room(it) == room }
            .forEach { runCatching { it.sendBinaryAndAwait(message) } }
    }

    @OnClose
    fun onClose(@Suppress("UNUSED_PARAMETER") connection: WebSocketConnection) { /* OpenConnections drops it automatically */ }

    private fun queryParam(c: WebSocketConnection, name: String): String? =
        c.handshakeRequest().query()?.split("&")?.firstNotNullOfOrNull {
            val kv = it.split("=", limit = 2)
            if (kv.size == 2 && kv[0] == name) java.net.URLDecoder.decode(kv[1], Charsets.UTF_8) else null
        }
}
