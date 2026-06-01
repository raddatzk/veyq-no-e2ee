package com.veyq.api.settings

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Optional

class SettingsServiceTest {

    @Test
    fun `server settings expose community license defaults and sponsor link`() {
        val settings = SettingsService(
            capabilityRegistry = CapabilityRegistry(),
            veyqConfig = testConfig(
                sourceUrl = "https://github.com/raddatzk/veyq",
            ),
        ).serverSettings()

        assertThat(settings.license.edition.value).isEqualTo("COMMUNITY")
        assertThat(settings.license.license).isEqualTo("AGPL-3.0-only")
        assertThat(settings.license.sourceUrl.toString()).isEqualTo("https://github.com/raddatzk/veyq")
        assertThat(settings.license.noticeUrl.toString()).isEqualTo("https://github.com/raddatzk/veyq/blob/main/NOTICE")
        assertThat(settings.support?.url.toString()).isEqualTo("https://github.com/sponsors/raddatzk")
    }

    @Test
    fun `server settings expose fixed sponsor link and build metadata`() {
        val settings = SettingsService(
            capabilityRegistry = CapabilityRegistry(),
            veyqConfig = testConfig(
                sourceUrl = "https://example.test/source",
                version = Optional.of("v1.0.0"),
                revision = Optional.of("sha-123"),
            ),
        ).serverSettings()

        assertThat(settings.license.version).isEqualTo("v1.0.0")
        assertThat(settings.license.revision).isEqualTo("sha-123")
        assertThat(settings.version).isEqualTo("v1.0.0")
        assertThat(settings.revision).isEqualTo("sha-123")
        assertThat(settings.capabilities).isNotEmpty
        assertThat(settings.license.trademarkPolicyUrl.toString()).isEqualTo("https://example.test/source/docs/legal/trademark-policy.md")
        assertThat(settings.support?.url.toString()).isEqualTo("https://github.com/sponsors/raddatzk")
    }

    private fun testConfig(
        sourceUrl: String,
        version: Optional<String> = Optional.empty(),
        revision: Optional<String> = Optional.empty(),
    ): VeyqConfig =
        object : VeyqConfig {
            override val publicWebBaseUrl: Optional<String> = Optional.empty()
            override val sourceUrl: String = sourceUrl
            override val version: Optional<String> = version
            override val revision: Optional<String> = revision
            override val updater: VeyqConfig.Updater = object : VeyqConfig.Updater {
                override val notifyToken: Optional<String> = Optional.empty()
            }
        }
}
