package com.veyq.app.shared.tenancy

import com.veyq.app.platform.error.ApiException
import io.vertx.ext.web.RoutingContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

@ApplicationScoped
class CurrentTenantResolver(private val tenants: TenantRepository) {
    @Inject
    lateinit var routingContext: RoutingContext

    fun currentTenant(): Tenant {
        val host = normalizeHost(routingContext.request().host())
        if (host.isBlank()) throw ApiException(404, "TENANT_NOT_FOUND", "Tenant not found")
        return tenants.findByDomain(host) ?: throw ApiException(404, "TENANT_NOT_FOUND", "Tenant not found")
    }

    fun currentTenantId(): String = currentTenant().id

    companion object {
        fun normalizeHost(raw: String?): String {
            val host = raw
                ?.trim()
                ?.lowercase()
                ?.substringBefore(',')
                ?.removePrefix("http://")
                ?.removePrefix("https://")
                ?.substringBefore('/')
                ?.trim()
                ?.trimEnd('.')
                ?: return ""
            return if (host.startsWith("[")) {
                host.substringAfter("[").substringBefore("]")
            } else {
                host.substringBefore(':')
            }
        }
    }
}
