package com.veyq.api.auth.filterchain

import com.veyq.api.security.SecurityConfig
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

@ApplicationScoped
class ClientIpResolver @Inject constructor(
    private val securityConfig: SecurityConfig,
) {
    fun resolve(forwardedFor: String?, remoteHost: String?): String =
        if (securityConfig.trustForwardedFor) {
            firstForwardedFor(forwardedFor) ?: fallback(remoteHost)
        } else {
            fallback(remoteHost)
        }

    private fun firstForwardedFor(value: String?): String? =
        value
            ?.split(",")
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun fallback(remoteHost: String?): String =
        remoteHost?.takeIf { it.isNotBlank() } ?: "unknown"
}
