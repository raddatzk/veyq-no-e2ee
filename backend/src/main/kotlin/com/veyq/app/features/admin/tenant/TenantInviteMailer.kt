package com.veyq.app.features.admin.tenant

import io.quarkus.runtime.LaunchMode
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

/**
 * Dev/test impl: logs the invite link instead of sending mail. Replace with an SMTP-backed impl in infra.
 *
 * Two safeguards so this dev convenience never becomes a prod liability:
 *  - the raw bootstrap token (a secret that must live only in the URL fragment, ADR 0006) is logged **only
 *    outside production**; a production runtime logs a secret-free warning instead of the link, so the
 *    invite secret never lands in server logs;
 *  - the owner-supplied `domain` is validated as a bare hostname before it is interpolated into the link,
 *    so it can't inject a different scheme/host/path (phishing / link-injection) once a real mailer ships.
 */
@ApplicationScoped
class LoggingTenantInviteMailer : TenantInviteMailer {
    private val log = Logger.getLogger(LoggingTenantInviteMailer::class.java)

    override fun sendInvite(domain: String, ownerEmail: String, token: String, expiresAt: Instant) {
        require(HOSTNAME.matches(domain)) { "invalid tenant domain" }
        if (LaunchMode.current() == LaunchMode.NORMAL) {
            // Production: never write the bootstrap secret to logs. A real SMTP-backed mailer must be wired.
            log.warnf(
                "No SMTP mailer configured: tenant invite for %s (expires %s) was NOT delivered.",
                ownerEmail, expiresAt,
            )
            return
        }
        val link = "https://$domain/tenant/accept#$token"
        log.infof("Tenant invite for %s (expires %s): %s", ownerEmail, expiresAt, link)
    }

    private companion object {
        /** A bare DNS hostname (labels of letters/digits/hyphens, dot-separated) — no scheme, port, path or
         *  userinfo, so the interpolated link can only ever point at the host the Server-Owner registered. */
        val HOSTNAME = Regex("^(?=.{1,253}$)([A-Za-z0-9](-?[A-Za-z0-9])*)(\\.[A-Za-z0-9](-?[A-Za-z0-9])*)+$")
    }
}
