package com.veyq.app.features.admin.tenant

import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.time.Instant

/**
 * Delivers a tenant-owner invite. A **port**: the structural slice (cr-0009) only needs the link minted and
 * handed off; real SMTP is an infra/runbook concern, deliberately out of scope. The invite link carries the
 * raw bootstrap secret in the URL **fragment** (never sent to the server until acceptance — ADR 0006) and
 * targets the tenant's own domain, where the invited owner accepts and cold-births the scope (Slice B).
 */
interface TenantInviteMailer {
    fun sendInvite(domain: String, ownerEmail: String, token: String, expiresAt: Instant)
}

/** Dev/test impl: logs the invite link instead of sending mail. Replace with an SMTP-backed impl in infra. */
@ApplicationScoped
class LoggingTenantInviteMailer : TenantInviteMailer {
    private val log = Logger.getLogger(LoggingTenantInviteMailer::class.java)

    override fun sendInvite(domain: String, ownerEmail: String, token: String, expiresAt: Instant) {
        val link = "https://$domain/tenant/accept#$token"
        log.infof("Tenant invite for %s (expires %s): %s", ownerEmail, expiresAt, link)
    }
}
