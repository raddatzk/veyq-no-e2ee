package com.veyq.api.auth.passkey

import com.veyq.api.auth.BadRequestException
import com.veyq.api.auth.ForbiddenException
import com.veyq.api.domain.TenantRepo
import io.vertx.ext.web.RoutingContext
import jakarta.enterprise.context.ApplicationScoped
import java.net.URI

@ApplicationScoped
class PasskeyHostService(
    private val tenantRepo: TenantRepo,
) {
    fun currentDomain(routingContext: RoutingContext): String =
        normalizeDomain(routingContext.request().host())

    fun currentOrigin(routingContext: RoutingContext): String {
        val uri = URI.create(routingContext.request().absoluteURI())
        return "${uri.scheme}://${uri.authority}".removeSuffix("/")
    }

    fun requireAllowedCurrentDomain(routingContext: RoutingContext): String {
        val domain = currentDomain(routingContext)
        if (isAllowedDomain(domain)) return domain
        throw ForbiddenException("Passkey login is not allowed for this domain")
    }

    fun normalizeOrigin(value: String): Pair<String, String> {
        val uri = try {
            URI.create(value.trim())
        } catch (_: Exception) {
            throw BadRequestException("Invalid origin")
        }
        if (uri.scheme != "https" && !isLocalhostDomain(uri.host ?: "")) {
            throw BadRequestException("Invalid origin")
        }
        if (uri.rawPath?.takeIf { it.isNotBlank() && it != "/" } != null || uri.rawQuery != null || uri.rawFragment != null) {
            throw BadRequestException("Invalid origin")
        }
        val domain = normalizeDomain(uri.authority ?: throw BadRequestException("Invalid origin"))
        return "${uri.scheme}://${uri.authority}" to domain
    }

    fun requireAllowedDomain(domain: String) {
        if (!isAllowedDomain(domain)) {
            throw ForbiddenException("Passkey login is not allowed for this domain")
        }
    }

    fun loginDomains(currentDomain: String): List<Pair<String, String>> =
        tenantRepo.listAllOrderByDomain()
            .map { it.domain.lowercase() }
            .distinct()
            .filter { it != currentDomain }
            .map { domain -> domain to "https://$domain" }

    private fun isAllowedDomain(domain: String): Boolean =
        isLocalhostDomain(domain) || !tenantRepo.existsAny() || tenantRepo.existsByDomain(domain)

    private fun normalizeDomain(value: String): String {
        val host = value.trim().lowercase()
            .removePrefix("[")
            .substringBefore("]")
            .ifBlank { throw BadRequestException("Invalid host") }
        return if (host.contains(":") && !host.contains("::")) host.substringBefore(":") else host
    }

    private fun isLocalhostDomain(domain: String): Boolean =
        domain == "localhost" || domain == "127.0.0.1" || domain == "::1"
}
