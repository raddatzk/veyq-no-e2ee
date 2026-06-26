package com.veyq.app.features.admin.tenant

import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Duration
import java.time.Instant

/**
 * The instance's first scheduled cleanup job: flips overdue ACTIVE invites to EXPIRED, then physically
 * deletes terminal-state invites (SUPERSEDED/CONSUMED/EXPIRED) whose TTL passed longer than the retention
 * window ago. Soft-invalidate now, hard-delete later — never inline (the design the user chose, mirroring
 * the session-token model). The same seam later covers expired session tokens (inbox #42 → cr-0011).
 */
@ApplicationScoped
class TenantInvitePurge(
    private val store: TenantStore,
    @param:ConfigProperty(name = "veyq.tenant.invite.retention", defaultValue = "P30D") private val retention: Duration,
) {

    @Scheduled(every = "{veyq.tenant.invite.purge.every}")
    fun run() {
        val now = Instant.now()
        store.expireOverdueInvites(now)
        store.purgeTerminalInvites(now.minus(retention))
    }
}
