package com.veyq.app.features.memory.journallive

import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Short-lived, single-use tickets that authenticate a WebSocket handshake WITHOUT putting the long-lived
 * session token in the URL. A member POSTs (with its Bearer session) to mint a ticket, then opens the relay
 * with `?ticket=<t>`; the relay consumes it once. Even if the ticket lands in a proxy access log or browser
 * history it is useless seconds later and after one use. In-memory + per-instance, like the rate limiter
 * (a clustered deployment would front the relay per node anyway).
 */
@ApplicationScoped
class JournalLiveTicketService {
    private companion object {
        const val TTL_NANOS = 30_000_000_000L // 30s — generous for "mint then immediately connect"
    }

    private class Entry(val userId: String, val expiresAtNanos: Long)

    private val tickets = ConcurrentHashMap<String, Entry>()
    private val rng = SecureRandom()
    private val enc = Base64.getUrlEncoder().withoutPadding()

    /** Mint a single-use ticket for [userId]. Returns the opaque ticket string. */
    fun issue(userId: String): String {
        val token = enc.encodeToString(ByteArray(32).also(rng::nextBytes))
        tickets[token] = Entry(userId, System.nanoTime() + TTL_NANOS)
        return token
    }

    /** TTL in whole seconds (handed back to the client so it knows the connect window). */
    fun ttlSeconds(): Long = TTL_NANOS / 1_000_000_000L

    /** Consume a ticket: remove it and return its userId if it exists and hasn't expired, else null. */
    fun consume(ticket: String): String? {
        val entry = tickets.remove(ticket) ?: return null
        return if (System.nanoTime() <= entry.expiresAtNanos) entry.userId else null
    }

    /** Drop expired tickets so the map can't grow unbounded if minted-but-never-used. */
    @Scheduled(every = "5m")
    fun sweep() {
        val now = System.nanoTime()
        tickets.entries.removeIf { it.value.expiresAtNanos < now }
    }
}
