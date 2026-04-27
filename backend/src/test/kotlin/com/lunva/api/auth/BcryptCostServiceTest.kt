package com.lunva.api.auth

import com.lunva.api.auth.bcrypt.BcryptCostService
import com.lunva.api.domain.AppConfigRepo
import io.quarkus.test.TestTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
        assertTrue(cost > 0)

        val row = appConfigRepo.findByKey(BcryptCostService.KEY)
        assertNotNull(row)
        assertEquals(cost.toString(), row.configValue)
    }
}
