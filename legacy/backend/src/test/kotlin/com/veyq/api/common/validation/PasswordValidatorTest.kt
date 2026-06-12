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

    @Test
    fun `name accepts url safe non reserved names`() {
        val nameValidator = NameValidator()

        assertThat(nameValidator.isValid("summer-trip", null)).isTrue()
        assertThat(nameValidator.isValid("Summer-Trip", null)).isTrue()
        assertThat(nameValidator.isValid("summer_trip", null)).isFalse()
        assertThat(nameValidator.isValid("-summer", null)).isFalse()
        assertThat(nameValidator.isValid("settings", null)).isFalse()
        assertThat(nameValidator.isValid(null, null)).isTrue()
    }

    @Test
    fun `display name requires visible text`() {
        val displayNameValidator = DisplayNameValidator()

        assertThat(displayNameValidator.isValid("Summer Trip", null)).isTrue()
        assertThat(displayNameValidator.isValid("   ", null)).isFalse()
        assertThat(displayNameValidator.isValid("a".repeat(MAX_DISPLAY_NAME_LENGTH + 1), null)).isFalse()
        assertThat(displayNameValidator.isValid(null, null)).isTrue()
    }

    @Test
    fun `positive byte values require values above zero`() {
        val bytesValidator = PositiveBytesValidator()

        assertThat(bytesValidator.isValid(1, null)).isTrue()
        assertThat(bytesValidator.isValid(0, null)).isFalse()
        assertThat(bytesValidator.isValid(-1, null)).isFalse()
        assertThat(bytesValidator.isValid(null, null)).isTrue()
    }

    @Test
    fun `required text rejects blank values`() {
        val requiredTextValidator = RequiredTextValidator()

        assertThat(requiredTextValidator.isValid("beach.mp4", null)).isTrue()
        assertThat(requiredTextValidator.isValid("   ", null)).isFalse()
        assertThat(requiredTextValidator.isValid(null, null)).isTrue()
    }
}
