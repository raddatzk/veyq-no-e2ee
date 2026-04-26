package com.lunva.api.auth.bcrypt

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithName

@ConfigMapping(prefix = "app.auth.bcrypt")
interface BcryptConfig {
    @get:WithName("target-ms")
    val targetMs: Long

    @get:WithName("min-cost")
    val minCost: Int

    @get:WithName("max-cost")
    val maxCost: Int

    @get:WithName("benchmark-samples")
    val benchmarkSamples: Int
}
