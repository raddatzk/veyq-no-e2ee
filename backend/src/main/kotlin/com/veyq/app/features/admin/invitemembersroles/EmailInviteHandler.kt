package com.veyq.app.features.admin.invitemembersroles

import com.veyq.app.generated.model.EmailInviteRequest
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.security.TenantAdminGuard
import com.veyq.app.shared.tenancy.InviteStatus
import com.veyq.app.shared.tenancy.TenantInviteRepository
import com.veyq.app.shared.tenancy.TenantRepository
import io.quarkus.mailer.Mail
import io.quarkus.mailer.Mailer
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * uc.admin.email-invite — relay an already-issued member-invite link to an email address. The link is
 * assembled client-side, so what the server (and mail transit) sees depends on the mode: pragmatic = the
 * temp private key in the fragment; passphrase = only the passphrase-encrypted temp key (the operator-blind
 * variant, with the passphrase shared out of band). The server validates ownership + that the link targets
 * this instance, then sends it. Email delivery is best-effort.
 */
@ApplicationScoped
class EmailInviteHandler(
    private val tenants: TenantRepository,
    private val invites: TenantInviteRepository,
    private val guard: TenantAdminGuard,
    private val mailer: Mailer,
    @param:ConfigProperty(name = "veyq.app.base-url") private val appBaseUrl: String,
) {
    @Transactional
    fun emailMemberInvite(auth: String?, tenantId: String, req: EmailInviteRequest) {
        guard.requireOwner(auth, tenantId)
        val tenant = tenants.findById(tenantId) ?: throw ApiException(404, "NOT_FOUND", "Tenant not found")
        val invite = invites.findById(req.inviteId) ?: throw ApiException(404, "NOT_FOUND", "Invite not found")
        if (invite.tenantId != tenantId || invite.status != InviteStatus.PENDING) {
            throw ApiException(400, "INVALID_INVITE", "Invite is not pending for this tenant")
        }
        val to = req.email.trim()
        if (!to.contains("@") || to.contains(" ")) throw ApiException(400, "INVALID_EMAIL", "Invalid email address")
        // Anti-abuse: only relay links whose ORIGIN exactly matches this instance — a prefix check (startsWith)
        // is bypassable (`https://veyq.example.com.evil.com/…`, `https://veyq.example.com@evil.com/…`), which
        // would turn this Owner-gated endpoint into a phishing/spam relay sending attacker links from our domain.
        if (!sameOrigin(req.link, appBaseUrl)) throw ApiException(400, "BAD_LINK", "Invite link must point at this instance")

        val subject = "You've been invited to ${tenant.displayName} on veyq"
        val body = buildString {
            append("Hi,\n\nYou've been invited to join \"").append(tenant.displayName).append("\" on veyq.\n")
            append("Open this link to accept:\n\n").append(req.link).append("\n\n")
            append("If you didn't expect this, you can ignore this email.\n")
        }
        mailer.send(Mail.withText(to, subject, body))
    }

    /** True iff [link] and [base] share an exact origin (scheme + host + effective port). Any parse failure = false. */
    private fun sameOrigin(link: String, base: String): Boolean = try {
        val l = java.net.URI(link)
        val b = java.net.URI(base)
        val scheme = l.scheme?.lowercase()
        scheme != null && scheme in setOf("http", "https") && scheme == b.scheme?.lowercase() &&
            l.host != null && l.host.equals(b.host, ignoreCase = true) && port(l) == port(b)
    } catch (_: Exception) {
        false
    }

    private fun port(u: java.net.URI): Int =
        if (u.port != -1) u.port else when (u.scheme?.lowercase()) { "https" -> 443; "http" -> 80; else -> -1 }
}
