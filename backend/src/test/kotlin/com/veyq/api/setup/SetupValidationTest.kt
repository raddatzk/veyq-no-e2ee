package com.veyq.api.setup

import com.veyq.api.auth.BadRequestException
import com.veyq.api.auth.passkey.optionalPasskeyDisplayName
import com.veyq.api.auth.passkey.requiredPasskeyDisplayName
import com.veyq.api.auth.passkey.suggestedPasskeyDisplayName
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
    fun `passkey display name is optional when creating and required when renaming`() {
        assertThat(optionalPasskeyDisplayName("  MacBook Touch ID  ")).isEqualTo("MacBook Touch ID")
        assertThat(optionalPasskeyDisplayName("   ")).isNull()
        assertThat(optionalPasskeyDisplayName(null)).isNull()
        assertThat(requiredPasskeyDisplayName("  MacBook Touch ID  ")).isEqualTo("MacBook Touch ID")

        assertThatThrownBy { requiredPasskeyDisplayName("   ") }
            .isInstanceOf(BadRequestException::class.java)
            .hasMessage("displayName must not be blank")
    }

    @Test
    fun `passkey display name is suggested from known aaguid`() {
        assertThat(suggestedPasskeyDisplayName("FBFC3007-154E-4ECC-8C0B-6E020557D7BD"))
            .isEqualTo("Apple Passwords")
        assertThat(suggestedPasskeyDisplayName("bada5566-a7aa-401f-bd96-45619a55120d"))
            .isEqualTo("1Password")
        assertThat(suggestedPasskeyDisplayName("ea9b8d66-4d01-1d21-3ce4-b6b48cb575d4"))
            .isEqualTo("Google Password Manager")
        assertThat(suggestedPasskeyDisplayName("d548826e-79b4-db40-a3d8-11116f7e8349"))
            .isEqualTo("Bitwarden")
        assertThat(suggestedPasskeyDisplayName("d7781e5d-e353-46aa-afe2-3ca49f13332a"))
            .isEqualTo("YubiKey")
        assertThat(suggestedPasskeyDisplayName("00000000-0000-0000-0000-000000000000"))
            .isEqualTo("Passkey")
    }
}
