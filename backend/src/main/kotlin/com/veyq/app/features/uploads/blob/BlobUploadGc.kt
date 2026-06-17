package com.veyq.app.features.uploads.blob

import com.veyq.app.shared.config.UploadsConfig
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Hourly GC of abandoned blob uploads: an OPEN session never finalized would keep its frames in the
 * BlobStore (+ manifest rows) forever. Sessions older than the TTL are purged; COMPLETED ones (whose
 * frames are now owned by a content blob) are left alone.
 */
@ApplicationScoped
class BlobUploadGc(
    private val handler: BlobUploadHandler,
    private val cfg: UploadsConfig,
) {
    @Scheduled(every = "1h")
    fun sweep() {
        handler.purgeStaleOpenUploads(Instant.now().minus(cfg.sessionTtlHours(), ChronoUnit.HOURS))
    }
}
