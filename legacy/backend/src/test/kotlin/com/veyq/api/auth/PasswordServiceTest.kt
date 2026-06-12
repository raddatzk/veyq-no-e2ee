package com.veyq.api.auth

import com.veyq.api.auth.argon2.Argon2ParameterProvider
import com.veyq.api.auth.argon2.Argon2Parameters
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class PasswordServiceTest {
    private val parameterProvider = object : Argon2ParameterProvider {
        override fun currentParameters(): Argon2Parameters = Argon2Parameters(
            iterations = 1,
            memoryKiB = 1024,
            parallelism = 1,
        )
    }

    private val service = PasswordService(parameterProvider, object : PasswordCompromiseChecker {
        override fun check(rawPassword: String) = Unit
    })

    @Test
    fun `hash and matches are consistent`() {
        val hash = service.hash("secret123")

        assertThat(hash).startsWith("\$argon2id$")
        assertThat(service.matches("secret123", hash)).isTrue()
        assertThat(service.matches("wrong1234567", hash)).isFalse()
        assertThat(service.needsRehash(hash)).isFalse()
    }

    @Test
    fun `unknown hash is rejected and needs rehash`() {
        assertThat(service.matches("pw", "plain:pw")).isFalse()
        assertThat(service.matches("pw", null)).isFalse()
        assertThat(service.needsRehash("plain:pw")).isTrue()
        assertThat(service.needsRehash(null)).isTrue()
    }
}
