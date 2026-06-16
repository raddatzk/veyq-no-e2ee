package com.veyq.app.features.admin.createtenantinviteowner

import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant

/**
 * Hourly GC of tenants whose grace period has passed: a scheduled deletion stays usable until its deadline,
 * then this sweep hard-purges it (scope + groups + content + memberships + invites). Deterministic, so a
 * tenant can't linger past its deadline even with no traffic.
 */
@ApplicationScoped
class TenantDeletionGc(private val handler: TenantProvisioningHandler) {
    @Scheduled(every = "1h")
    fun sweep() {
        // One transaction per tenant (cross-bean call so @Transactional applies), so a backlog isn't one giant
        // write tx and one bad tenant doesn't roll back the rest.
        val now = Instant.now()
        for (tenantId in handler.dueTenantIds(now)) runCatching { handler.purgeTenant(tenantId) }
    }
}
