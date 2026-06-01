package com.veyq.api.auth.argon2

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithName

@ConfigMapping(prefix = "veyq.auth.argon2")
interface Argon2Config {
    @get:WithName("target-ms")
    val targetMs: Long

    @get:WithName("memory-kib")
    val memoryKiB: Int

    @get:WithName("min-iterations")
    val minIterations: Int

    @get:WithName("max-iterations")
    val maxIterations: Int

    @get:WithName("parallelism")
    val parallelism: Int

    @get:WithName("benchmark-samples")
    val benchmarkSamples: Int
}
