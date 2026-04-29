package com.lunva.api.library.media

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithName
import java.nio.file.Path

@ConfigMapping(prefix = "lunva.media")
interface MediaConfig {
    @get:WithName("storage-dir")
    val storageDir: Path

    @get:WithName("chunk-size-bytes")
    val chunkSizeBytes: Long

    @get:WithName("upload-session-ttl-hours")
    val uploadSessionTtlHours: Long

    @get:WithName("upload-session-cleanup-cron")
    val uploadSessionCleanupCron: String
}
