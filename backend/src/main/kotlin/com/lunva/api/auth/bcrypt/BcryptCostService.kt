package com.lunva.api.auth.bcrypt

import com.lunva.api.domain.AppConfig
import com.lunva.api.domain.AppConfigRepo
import io.quarkus.elytron.security.common.BcryptUtil
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.jboss.logging.Logger
import java.time.Instant

@ApplicationScoped
class BcryptCostService @Inject constructor(
    private val bcryptConfig: BcryptConfig,
    private val appConfigRepo: AppConfigRepo
) : BcryptCostProvider {
    private val log: Logger = Logger.getLogger(BcryptCostService::class.java)

    @Volatile
    private var cachedCost: Int? = null

    @Transactional
    fun onStartup(@Observes ev: StartupEvent) {
        cachedCost = initializeOrLoadCost()
    }

    override fun currentCost(): Int = cachedCost
        ?: throw IllegalStateException("bcrypt cost is not initialized yet")

    private fun initializeOrLoadCost(): Int {
        val existing = appConfigRepo.findByKey(KEY)
        if (existing != null) {
            return existing.configValue.toInt()
        }

        val benchmarked = benchmarkCost()
        val now = Instant.now()
        try {
            AppConfig().also {
                it.configKey = KEY
                it.configValue = benchmarked.toString()
                appConfigRepo.persist(it)
            }
            log.infov("Initialized bcrypt cost={0} (target={1}ms)", benchmarked, bcryptConfig.targetMs)
            return benchmarked
        } catch (_: Exception) {
            val row = appConfigRepo.findByKey(KEY)
                ?: throw IllegalStateException("Could not initialize bcrypt cost")
            return row.configValue.toInt()
        }
    }

    private fun benchmarkCost(): Int {
        var best = bcryptConfig.minCost
        val pwd = "lunva-bcrypt-benchmark"
        val samples = bcryptConfig.benchmarkSamples.coerceAtLeast(1)

        for (cost in bcryptConfig.minCost..bcryptConfig.maxCost) {
            val started = System.nanoTime()
            repeat(samples) {
                BcryptUtil.bcryptHash(pwd, cost)
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000.0 / samples

            if (elapsedMs <= bcryptConfig.targetMs) {
                best = cost
            } else {
                break
            }
        }
        return best
    }

    companion object {
        const val KEY = "auth.bcrypt.cost"
    }
}
