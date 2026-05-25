package com.veyq.api.setup

import com.veyq.api.auth.BadRequestException
import com.veyq.api.auth.passkey.requiredPasskeyDisplayName
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SetupValidationTest {
    @Test
    fun `setup display name is required and trimmed`() {
        assertThat(requiredSetupDisplayName("  Administrator  ")).isEqualTo("Administrator")

        assertThatThrownBy { requiredSetupDisplayName("   ") }
            .isInstanceOf(BadRequestException::class.java)
            .hasMessage("displayName must not be blank")
    }

    @Test
    fun `setup passkey display name is required and trimmed`() {
        assertThat(requiredPasskeyDisplayName("  MacBook Touch ID  ")).isEqualTo("MacBook Touch ID")

        assertThatThrownBy { requiredPasskeyDisplayName("   ") }
            .isInstanceOf(BadRequestException::class.java)
            .hasMessage("displayName must not be blank")
    }
}
