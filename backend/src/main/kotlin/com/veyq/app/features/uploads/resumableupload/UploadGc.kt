package com.veyq.app.features.uploads.resumableupload

import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Time-based GC of abandoned resumable uploads: an OPEN session never finalized would otherwise keep its
 * frames in the BlobStore (and manifest rows) forever. An hourly sweep purges sessions older than the TTL.
 * COMPLETED sessions are the media's storage and are left alone.
 */
@ApplicationScoped
class UploadGc(
    private val handler: ResumableUploadHandler,
    @param:ConfigProperty(name = "veyq.uploads.session-ttl-hours", defaultValue = "24") private val ttlHours: Long,
) {
    @Scheduled(every = "1h")
    fun sweep() {
        handler.purgeStaleOpenUploads(Instant.now().minus(ttlHours, ChronoUnit.HOURS))
    }
}
