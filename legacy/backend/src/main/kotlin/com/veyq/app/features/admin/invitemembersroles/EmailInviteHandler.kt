package com.veyq.app.features.admin.invitemembersroles

import com.veyq.app.generated.model.EmailInviteRequest
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.security.TenantAdminGuard
import com.veyq.app.shared.tenancy.InviteStatus
import com.veyq.app.shared.tenancy.TenantInviteRepository
import com.veyq.app.shared.tenancy.TenantRepository
import io.quarkus.mailer.Mail
import io.quarkus.mailer.Mailer
import com.veyq.app.shared.config.AppConfig
import com.veyq.app.shared.tenancy.Tenant
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.net.URI

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
    app: AppConfig,
) {
    private val appBaseUrl: String = app.baseUrl()

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
        // Anti-abuse: only relay links whose ORIGIN exactly matches this instance or this tenant's domain —
        // a prefix check (`startsWith`) is bypassable via attacker-controlled hosts.
        if (!sameOrigin(req.link, appBaseUrl) && !sameOrigin(req.link, tenantInviteBaseUrl(tenant))) {
            throw ApiException(400, "BAD_LINK", "Invite link must point at this instance")
        }

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
        val l = URI(link)
        val b = URI(base)
        val scheme = l.scheme?.lowercase()
        scheme != null && scheme in setOf("http", "https") && scheme == b.scheme?.lowercase() &&
            l.host != null && l.host.equals(b.host, ignoreCase = true) && port(l) == port(b)
    } catch (_: Exception) {
        false
    }

    private fun tenantInviteBaseUrl(tenant: Tenant): String {
        val domain = tenant.domain.trim().trimEnd('/')
        if (domain.isBlank()) return appBaseUrl
        if (domain.startsWith("http://", ignoreCase = true) || domain.startsWith("https://", ignoreCase = true)) {
            return domain
        }
        val scheme = runCatching { URI(appBaseUrl).scheme?.lowercase() }.getOrNull()
            ?.takeIf { it == "http" || it == "https" }
            ?: "https"
        return "$scheme://$domain"
    }

    private fun port(u: URI): Int =
        if (u.port != -1) u.port else when (u.scheme?.lowercase()) { "https" -> 443; "http" -> 80; else -> -1 }
}
