package com.veyq.app.features.uploads.blob

import com.veyq.app.shared.config.UploadsConfig
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Hourly GC of blob storage:
 *  - abandoned OPEN uploads (never finalized) → their frames + manifest + session row.
 *  - finalized blobs no media references anymore (a deleted medium leaves dedup'd frames behind, ADR 0007).
 * Both are gated by the same TTL grace so in-flight work is never reaped; COMPLETED upload rows are kept.
 */
@ApplicationScoped
class BlobUploadGc(
    private val handler: BlobUploadHandler,
    private val cfg: UploadsConfig,
) {
    @Scheduled(every = "1h")
    fun sweep() {
        val cutoff = Instant.now().minus(cfg.sessionTtlHours(), ChronoUnit.HOURS)
        handler.purgeStaleOpenUploads(cutoff)
        handler.purgeUnreferencedBlobs(cutoff)
    }
}
