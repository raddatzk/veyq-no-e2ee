package com.lunva.api.auth

import com.lunva.api.auth.bcrypt.BcryptCostProvider
import io.quarkus.elytron.security.common.BcryptUtil
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PasswordServiceTest {
    private val costProvider = object : BcryptCostProvider {
        override fun currentCost(): Int = 5
    }

    private val service = PasswordService(costProvider)

    @Test
    fun `hash and matches are consistent`() {
        val hash = service.hash("secret123")

        assertTrue(service.matches("secret123", hash))
        assertFalse(service.matches("wrong", hash))
    }

    @Test
    fun `non-bcrypt hash is rejected and needs rehash`() {
        assertFalse(service.matches("pw", "plain:pw"))
        assertTrue(service.needsRehash("plain:pw"))
        assertTrue(service.needsRehash(null))
    }

    @Test
    fun `bcrypt hash with lower cost requires rehash`() {
        val weakerHash = BcryptUtil.bcryptHash("secret123", 4)
        assertTrue(service.needsRehash(weakerHash))
    }
}
