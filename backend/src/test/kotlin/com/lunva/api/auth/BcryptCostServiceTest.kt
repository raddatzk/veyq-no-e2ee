package com.lunva.api.auth

import com.lunva.api.auth.bcrypt.BcryptCostService
import com.lunva.api.domain.AppConfigRepo
import io.quarkus.test.TestTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

@QuarkusTest
class BcryptCostServiceTest {
    @Inject
    lateinit var bcryptCostService: BcryptCostService
    @Inject
    lateinit var appConfigRepo: AppConfigRepo

    @Test
    @TestTransaction
    fun `current cost is initialized and persisted in config`() {
        val cost = bcryptCostService.currentCost()
        assertThat(cost).isPositive()

        val row = appConfigRepo.findByKey(BcryptCostService.KEY)
        assertThat(row).isNotNull
        assertThat(row!!.configValue).isEqualTo(cost.toString())
    }
}
