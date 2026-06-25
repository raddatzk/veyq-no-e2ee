package com.veyq.app.shared.security

import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped

/**
 * Time-based GC of expired sessions: a deterministic hourly sweep so stale rows can't accumulate even if
 * the server sees no logins (login no longer sweeps). `resolve()` still purges a presented expired token.
 */
@ApplicationScoped
class SessionGc(private val sessions: SessionService) {
    @Scheduled(every = "1h")
    fun sweep() {
        sessions.purgeExpired()
    }
}
