package com.veyq.api.settings

import com.veyq.api.generated.model.ServerLicenseSummary
import com.veyq.api.generated.model.ServerSettingsResponse
import com.veyq.api.generated.model.ServerSupportSummary
import jakarta.inject.Singleton
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.net.URI
import java.util.Optional

@Singleton
class SettingsService(
    private val capabilityRegistry: CapabilityRegistry,
    @param:ConfigProperty(name = "veyq.source-url")
    private val sourceUrl: String,
    @param:ConfigProperty(name = "veyq.version")
    private val version: Optional<String>,
    @param:ConfigProperty(name = "veyq.revision")
    private val revision: Optional<String>,
) {

    fun serverSettings(): ServerSettingsResponse {
        val source = sourceUrl.trim().ifBlank { DEFAULT_SOURCE_URL }
        return ServerSettingsResponse(
            displayName = "Server",
            userManagementAvailable = true,
            license = ServerLicenseSummary(
                edition = ServerLicenseSummary.Edition.COMMUNITY,
                license = "AGPL-3.0-only",
                sourceUrl = URI.create(source),
                noticeUrl = source.docUri("NOTICE"),
                trademarkPolicyUrl = source.docUri("docs/legal/trademark-policy.md"),
                commercialBoundaryUrl = source.docUri("docs/legal/commercial-boundary.md"),
                version = version.clean(),
                revision = revision.clean(),
            ),
            support = ServerSupportSummary(URI.create(SPONSOR_URL)),
            version = version.clean(),
            revision = revision.clean(),
            capabilities = capabilityRegistry.listFor(),
        )
    }

    private fun String.docUri(path: String): URI {
        val normalized = trim().removeSuffix("/")
        val url = when {
            "github.com/" in normalized -> "$normalized/blob/main/$path"
            else -> "$normalized/$path"
        }
        return URI.create(url)
    }

    private fun Optional<String>.clean(): String? = orElse("").trim().ifBlank { null }

    companion object {
        private const val DEFAULT_SOURCE_URL = "https://github.com/raddatzk/veyq"
        private const val SPONSOR_URL = "https://github.com/sponsors/raddatzk"
    }
}
