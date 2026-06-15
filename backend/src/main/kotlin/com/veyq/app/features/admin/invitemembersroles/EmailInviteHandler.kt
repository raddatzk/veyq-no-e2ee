package com.veyq.app.features.admin.invitemembersroles

import com.veyq.app.generated.model.EmailInviteRequest
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.security.SessionService
import com.veyq.app.shared.tenancy.InviteStatus
import com.veyq.app.shared.tenancy.MembershipRepository
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
    private val memberships: MembershipRepository,
    private val invites: TenantInviteRepository,
    private val sessions: SessionService,
    private val mailer: Mailer,
    @param:ConfigProperty(name = "veyq.app.base-url") private val appBaseUrl: String,
) {
    @Transactional
    fun emailMemberInvite(auth: String?, tenantId: String, req: EmailInviteRequest) {
        val callerId = sessions.resolve(auth) ?: throw ApiException(401, "UNAUTHORIZED", "Missing or invalid session")
        val tenant = tenants.findById(tenantId) ?: throw ApiException(404, "NOT_FOUND", "Tenant not found")
        val caller = memberships.find(tenantId, callerId)
        if (caller == null || caller.role != "Owner" || caller.status != "ACTIVE") {
            throw ApiException(403, "FORBIDDEN", "Tenant Owner role required")
        }
        val invite = invites.findById(req.inviteId) ?: throw ApiException(404, "NOT_FOUND", "Invite not found")
        if (invite.tenantId != tenantId || invite.status != InviteStatus.PENDING.name) {
            throw ApiException(400, "INVALID_INVITE", "Invite is not pending for this tenant")
        }
        val to = req.email.trim()
        if (!to.contains("@") || to.contains(" ")) throw ApiException(400, "INVALID_EMAIL", "Invalid email address")
        // Anti-abuse: only relay links that point at this instance's own app origin.
        if (!req.link.startsWith(appBaseUrl)) throw ApiException(400, "BAD_LINK", "Invite link must point at this instance")

        val subject = "You've been invited to ${tenant.displayName} on veyq"
        val body = buildString {
            append("Hi,\n\nYou've been invited to join \"").append(tenant.displayName).append("\" on veyq.\n")
            append("Open this link to accept:\n\n").append(req.link).append("\n\n")
            append("If you didn't expect this, you can ignore this email.\n")
        }
        mailer.send(Mail.withText(to, subject, body))
    }
}
