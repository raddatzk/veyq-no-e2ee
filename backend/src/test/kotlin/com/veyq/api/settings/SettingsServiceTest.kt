package com.veyq.api.settings

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Optional

class SettingsServiceTest {

    @Test
    fun `server settings expose community license defaults without support link`() {
        val settings = SettingsService(
            sourceUrl = "https://github.com/raddatzk/veyq",
            supportUrl = Optional.empty(),
            version = Optional.empty(),
            revision = Optional.empty(),
        ).serverSettings()

        assertThat(settings.license.edition.value).isEqualTo("COMMUNITY")
        assertThat(settings.license.license).isEqualTo("AGPL-3.0-only")
        assertThat(settings.license.sourceUrl.toString()).isEqualTo("https://github.com/raddatzk/veyq")
        assertThat(settings.license.noticeUrl.toString()).isEqualTo("https://github.com/raddatzk/veyq/blob/main/NOTICE")
        assertThat(settings.support).isNull()
    }

    @Test
    fun `server settings expose optional support and build metadata`() {
        val settings = SettingsService(
            sourceUrl = "https://example.test/source",
            supportUrl = Optional.of("https://example.test/support"),
            version = Optional.of("v1.0.0"),
            revision = Optional.of("sha-123"),
        ).serverSettings()

        assertThat(settings.license.version).isEqualTo("v1.0.0")
        assertThat(settings.license.revision).isEqualTo("sha-123")
        assertThat(settings.license.trademarkPolicyUrl.toString()).isEqualTo("https://example.test/source/docs/legal/trademark-policy.md")
        assertThat(settings.support?.url.toString()).isEqualTo("https://example.test/support")
    }
}
