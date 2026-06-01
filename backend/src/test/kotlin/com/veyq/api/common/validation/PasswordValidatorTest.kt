package com.veyq.api.common.validation

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PasswordValidatorTest {
    private val validator = PasswordValidator()

    @Test
    fun `password requires at least ten characters`() {
        assertThat(validator.isValid("123456789", null)).isFalse()
        assertThat(validator.isValid("1234567890", null)).isTrue()
        assertThat(validator.isValid(null, null)).isTrue()
    }
}
