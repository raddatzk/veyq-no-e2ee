package com.veyq.app.features.sharing

import com.veyq.app.shared.config.VeyqConfig
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * A best-effort, per-share **fixed-window** bound on **wrong** share-password attempts (cr-0165, ADR 0037) — the
 * online brute-force bound ADR 0037 relies on. In-memory + per-instance, mirroring [ShareRateLimiter] and the auth
 * rate limiter (a gateway bucket is the production answer). Keyed on `shareId`.
 *
 * **Only a wrong attempt consumes a slot** (the caller — `SharingHandler.passwordCheck` — invokes this only when
 * the token mismatched): a correct password is admitted before this is ever reached, so a legitimate guest is
 * never locked out, and a tokenless (challenge-fetch) request never spends budget. This is a deliberate sibling of
 * [ShareRateLimiter], not a shared abstraction — the two have identical window logic but different budgets and
 * different consume semantics (every call vs. only-on-wrong); the abstraction waits for a third user (rule of three).
 */
@ApplicationScoped
class SharePasswordAttemptLimiter(
    config: VeyqConfig,
) {
    private val maxPerWindow: Int = config.share().password().rate().maxPerWindow()
    private val windowSeconds: Long = config.share().password().rate().windowSeconds()

    private data class Window(val startEpochSec: Long, val count: Int)

    private val windows = ConcurrentHashMap<String, Window>()

    /** Consume one slot for [shareId] on a wrong attempt; returns false once the current window's cap is exceeded. */
    fun allow(shareId: String): Boolean {
        val nowSec = Instant.now().epochSecond
        val next = windows.compute(shareId) { _, cur ->
            if (cur == null || nowSec - cur.startEpochSec >= windowSeconds) Window(nowSec, 1)
            else cur.copy(count = cur.count + 1)
        }!!
        return next.count <= maxPerWindow
    }
}
