package com.veyq.api.auth

import com.veyq.api.auth.bcrypt.BcryptCostProvider
import io.quarkus.elytron.security.common.BcryptUtil
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class PasswordServiceTest {
    private val costProvider = object : BcryptCostProvider {
        override fun currentCost(): Int = 5
    }

    private val service = PasswordService(costProvider)

    @Test
    fun `hash and matches are consistent`() {
        val hash = service.hash("secret123")

        assertThat(service.matches("secret123", hash)).isTrue()
        assertThat(service.matches("wrong1234567", hash)).isFalse()
    }

    @Test
    fun `non-bcrypt hash is rejected and needs rehash`() {
        assertThat(service.matches("pw", "plain:pw")).isFalse()
        assertThat(service.needsRehash("plain:pw")).isTrue()
        assertThat(service.needsRehash(null)).isTrue()
    }

    @Test
    fun `bcrypt hash with lower cost requires rehash`() {
        val weakerHash = BcryptUtil.bcryptHash("secret123", 4)
        assertThat(service.needsRehash(weakerHash)).isTrue()
    }
}
