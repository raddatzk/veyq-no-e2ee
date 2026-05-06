package com.lunva.api.library.media

import io.quarkus.runtime.configuration.MemorySize
import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithName
import java.nio.file.Path

@ConfigMapping(prefix = "lunva.media")
interface MediaConfig {
    @get:WithName("storage-dir")
    val storageDir: Path

    @get:WithName("chunk-size")
    val chunkSize: MemorySize

    @get:WithName("upload-session-ttl-hours")
    val uploadSessionTtlHours: Long

    @get:WithName("upload-session-cleanup-cron")
    val uploadSessionCleanupCron: String

    @get:WithName("ffmpeg-path")
    val ffmpegPath: String

    @get:WithName("preview-timeout-seconds")
    val previewTimeoutSeconds: Long

    val chunkSizeBytes: Long
        get() = chunkSize.asLongValue()
}
