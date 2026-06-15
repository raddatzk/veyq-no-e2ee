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
        handler.purgeDueTenants(Instant.now())
    }
}
