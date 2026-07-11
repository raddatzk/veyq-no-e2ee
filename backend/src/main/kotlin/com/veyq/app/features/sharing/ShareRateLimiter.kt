package com.veyq.app.features.sharing

import com.veyq.app.shared.config.VeyqConfig
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * A best-effort, per-share **fixed-window** rate limit for the anonymous contributor upload path (cr-0101,
 * ADR 0030). In-memory + per-instance — defense-in-depth against an abusive link holder, not a distributed
 * quota (a gateway bucket is the production answer, mirroring the auth rate limiter). Keyed on `shareId`:
 * each `startShareBlobUploads` call consumes one slot; over the cap in the current window → `429`.
 */
@ApplicationScoped
class ShareRateLimiter(
    config: VeyqConfig,
) {
    private val maxPerWindow: Int = config.share().upload().rate().maxPerWindow()
    private val windowSeconds: Long = config.share().upload().rate().windowSeconds()

    private data class Window(val startEpochSec: Long, val count: Int)

    private val windows = ConcurrentHashMap<String, Window>()

    /** Consume one slot for [shareId]; returns false when the current window's cap is exceeded. */
    fun allow(shareId: String): Boolean {
        val nowSec = Instant.now().epochSecond
        val next = windows.compute(shareId) { _, cur ->
            if (cur == null || nowSec - cur.startEpochSec >= windowSeconds) Window(nowSec, 1)
            else cur.copy(count = cur.count + 1)
        }!!
        return next.count <= maxPerWindow
    }
}
