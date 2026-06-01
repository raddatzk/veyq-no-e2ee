package com.veyq.api.auth.argon2

import com.veyq.api.domain.AppConfig
import com.veyq.api.domain.AppConfigRepo
import de.mkammerer.argon2.Argon2Factory
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.jboss.logging.Logger

@ApplicationScoped
class Argon2ParameterService @Inject constructor(
    private val argon2Config: Argon2Config,
    private val appConfigRepo: AppConfigRepo,
) : Argon2ParameterProvider {
    private val log: Logger = Logger.getLogger(Argon2ParameterService::class.java)
    private val argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id)

    @Volatile
    private var cachedParameters: Argon2Parameters? = null

    @Transactional
    fun onStartup(@Observes ev: StartupEvent) {
        cachedParameters = initializeOrLoadParameters()
    }

    override fun currentParameters(): Argon2Parameters = cachedParameters
        ?: throw IllegalStateException("Argon2 parameters are not initialized yet")

    private fun initializeOrLoadParameters(): Argon2Parameters {
        val existing = appConfigRepo.findByKey(AppConfig.ARGON2_PARAMS)
        if (existing != null) {
            return Argon2Parameters.deserialize(existing.configValue)
        }

        val benchmarked = benchmarkParameters()
        try {
            AppConfig().also {
                it.configKey = AppConfig.ARGON2_PARAMS
                it.configValue = benchmarked.serialize()
                appConfigRepo.persist(it)
            }
            log.infov(
                "Initialized Argon2id parameters={0} (target={1}ms)",
                benchmarked.serialize(),
                argon2Config.targetMs,
            )
            return benchmarked
        } catch (_: Exception) {
            val row = appConfigRepo.findByKey(AppConfig.ARGON2_PARAMS)
                ?: throw IllegalStateException("Could not initialize Argon2 parameters")
            return Argon2Parameters.deserialize(row.configValue)
        }
    }

    private fun benchmarkParameters(): Argon2Parameters {
        val samples = argon2Config.benchmarkSamples.coerceAtLeast(1)
        val minIterations = argon2Config.minIterations.coerceAtLeast(1)
        val maxIterations = argon2Config.maxIterations.coerceAtLeast(minIterations)
        var bestIterations = minIterations
        val password = "veyq-argon2-benchmark".toCharArray()

        for (iterations in minIterations..maxIterations) {
            val started = System.nanoTime()
            repeat(samples) {
                argon2.hash(iterations, argon2Config.memoryKiB, argon2Config.parallelism, password)
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000.0 / samples

            if (elapsedMs <= argon2Config.targetMs || iterations == minIterations) {
                bestIterations = iterations
            } else {
                break
            }
        }
        return Argon2Parameters(bestIterations, argon2Config.memoryKiB, argon2Config.parallelism)
    }
}
