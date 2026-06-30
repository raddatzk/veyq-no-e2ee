package com.veyq.api.settings

import com.veyq.api.generated.model.ServerLicenseSummary
import com.veyq.api.generated.model.ServerSettingsResponse
import com.veyq.api.generated.model.ServerSupportSummary
import jakarta.inject.Singleton
import java.net.URI
import java.util.Optional

@Singleton
class SettingsService(
    private val capabilityRegistry: CapabilityRegistry,
    private val veyqConfig: VeyqConfig,
) {

    fun serverSettings(): ServerSettingsResponse {
        val source = veyqConfig.sourceUrl.trim().ifBlank { DEFAULT_SOURCE_URL }
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
                version = veyqConfig.version.clean(),
                revision = veyqConfig.revision.clean(),
            ),
            support = ServerSupportSummary(URI.create(SPONSOR_URL)),
            version = veyqConfig.version.clean(),
            revision = veyqConfig.revision.clean(),
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
