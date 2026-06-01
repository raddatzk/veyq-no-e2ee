package com.veyq.api.auth

import com.veyq.api.auth.argon2.Argon2ParameterService
import com.veyq.api.domain.AppConfig
import com.veyq.api.domain.AppConfigRepo
import io.quarkus.test.TestTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

@QuarkusTest
class Argon2ParameterServiceTest {
    @Inject
    lateinit var argon2ParameterService: Argon2ParameterService
    @Inject
    lateinit var appConfigRepo: AppConfigRepo

    @Test
    @TestTransaction
    fun `argon2 parameters are initialized and persisted in config`() {
        val parameters = argon2ParameterService.currentParameters()
        assertThat(parameters.iterations).isPositive()
        assertThat(parameters.memoryKiB).isPositive()
        assertThat(parameters.parallelism).isPositive()

        val row = appConfigRepo.findByKey(AppConfig.ARGON2_PARAMS)
        assertThat(row).isNotNull
        assertThat(row!!.configValue).isEqualTo(parameters.serialize())
    }
}
