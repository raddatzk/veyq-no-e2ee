package com.veyq.app.features.admin.tenant

import com.veyq.app.shared.config.VeyqConfig
import com.veyq.app.shared.ratelimit.RateLimitWindow
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * The member-invite mail limiter (cr-email-invite-send-hardening) — the **real** anti-bombing guard.
 *
 * The per-IP `@RateLimit(key = IP)`/`IpRateLimitFilter` machinery keys on the client IP, which an authenticated owner
 * sits behind and which cannot express the thing that actually needs bounding: **how many invites reach one
 * victim address**. So this is a dedicated, handler-level limiter — a sibling of `ShareRateLimiter` — consumed in
 * [TenantHandler] on the two tenant-owner member paths that drive SMTP mail (`inviteMember`, `reissueMemberInvite`),
 * **after** the authorization guards pass and **before** the mint, so a limited request creates no orphan invite
 * and only authorized owners consume a slot.
 *
 * Two fixed windows over the pure, mutation-verified [RateLimitWindow] arithmetic (ADR 0045 §2) — unlike
 * `ShareRateLimiter`, which reimplements the window inline; this reuses the tested core:
 *  - **per (tenantId, recipient)** — the tighter, primary guard against bombing one address;
 *  - **per tenantId** — the volume cap against an owner-as-sender.
 *
 * In-memory + per-instance — defense-in-depth, not a distributed quota (a gateway bucket is the production answer,
 * as for the auth limiter). Operator-blind is untouched: the state is ephemeral and the recipient address it keys
 * on is already server-visible plaintext (ADR 0049 §4).
 */
@ApplicationScoped
class InviteMailRateLimiter(
    private val perRecipientMax: Int,
    private val perTenantMax: Int,
    private val windowSeconds: Long,
) {
    /** CDI wiring: read the `veyq.ratelimit.invite.*` bucket. The primary constructor stays value-only so the
     *  limiter can be bite-tested without stubbing the whole [VeyqConfig] tree. */
    @Inject
    constructor(config: VeyqConfig) : this(
        config.ratelimit().invite().perRecipientMaxRequests(),
        config.ratelimit().invite().perTenantMaxRequests(),
        config.ratelimit().invite().windowSeconds(),
    )

    private data class Slot(val windowStart: Long, val count: Int)

    private val recipientWindows = ConcurrentHashMap<String, Slot>()
    private val tenantWindows = ConcurrentHashMap<String, Slot>()

    /**
     * Consume one invite-send slot for [tenantId] → [recipientEmail]. Returns `null` when allowed, or the
     * `Retry-After` seconds when a window's cap is exceeded. The recipient (tighter) window is checked first, so a
     * rejected recipient does not consume the tenant budget; if the recipient passes but the tenant cap trips, the
     * recipient slot was already consumed — a deliberate, slightly-conservative over-count (fails safe).
     */
    fun check(tenantId: String, recipientEmail: String): Long? {
        val nowSec = Instant.now().epochSecond
        // Key on the same lowercase normalization the mint uses (idempotent — callers already normalize).
        // NUL joins the two key parts unambiguously — it cannot occur in a UUID tenantId or an email.
        val recipientKey = "$tenantId\u0000${recipientEmail.lowercase()}"
        consume(recipientWindows, recipientKey, perRecipientMax, nowSec)?.let { return it }
        return consume(tenantWindows, tenantId, perTenantMax, nowSec)
    }

    /** Increment [key]'s current fixed window and return the `Retry-After` seconds iff it is now over [max]. */
    private fun consume(windows: ConcurrentHashMap<String, Slot>, key: String, max: Int, nowSec: Long): Long? {
        val ws = RateLimitWindow.windowStart(nowSec, windowSeconds)
        val slot = windows.compute(key) { _, cur ->
            if (cur == null || RateLimitWindow.hasElapsed(nowSec, cur.windowStart, windowSeconds)) Slot(ws, 1)
            else cur.copy(count = cur.count + 1)
        }!!
        return if (RateLimitWindow.isOverLimit(slot.count.toLong(), max.toLong())) {
            RateLimitWindow.retryAfter(nowSec, windowSeconds)
        } else {
            null
        }
    }
}
