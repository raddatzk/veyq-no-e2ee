package com.veyq.app.features.admin.tenant

import com.veyq.app.shared.crypto.ConfigSecretSeal
import com.veyq.app.shared.crypto.SealedSecret
import io.vertx.core.Vertx
import io.vertx.ext.mail.LoginOption
import io.vertx.ext.mail.MailClient
import io.vertx.ext.mail.MailConfig
import io.vertx.ext.mail.MailMessage
import io.vertx.ext.mail.StartTLSOptions
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import org.jboss.logging.Logger
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * The real, SMTP-backed [TenantInviteMailer] (cr-email-smtp-transport, ADR 0048/0049/0050). A per-tenant transport:
 * it resolves the tenant's own mail settings by the `domain` it is handed, and when that tenant has **enabled +
 * fully configured** SMTP (and the env KEK is present so the password can be unsealed), it renders the invite over
 * the closed variable set ([InviteMailRenderer]) and sends it from the tenant's own address via a Vert.x
 * `MailClient` built at send time. Otherwise it **delegates to [LoggingTenantInviteMailer]** (copy-link + the
 * cr-0046 dev-file) — so SMTP-less tenants, the owner-bootstrap invite (a PENDING tenant has no settings), and the
 * whole dev/seed loop are unchanged.
 *
 * **CDI:** a `@Priority`-enabled `@Alternative` so it wins the `TenantInviteMailer` injection point globally over
 * the plain [LoggingTenantInviteMailer] bean (which it injects **by concrete type** for the fallback — a concrete
 * bean type is never ambiguous, ADR 0048). No call site changes.
 *
 * **Best-effort, like the logging mailer:** a send failure is logged **secret-free** and swallowed — the invite
 * was already minted and its raw token is returned to the owner for the copy-link, so a transient SMTP error must
 * not roll back the mint. The raw token / accept link is **never** logged, in any mode.
 */
@Alternative
@Priority(1)
@ApplicationScoped
class SmtpTenantInviteMailer(
    private val store: TenantStore,
    private val seal: ConfigSecretSeal,
    private val renderer: InviteMailRenderer,
    private val fallback: LoggingTenantInviteMailer,
    private val vertx: Vertx,
) : TenantInviteMailer {

    private val log = Logger.getLogger(SmtpTenantInviteMailer::class.java)

    override fun sendInvite(domain: String, ownerEmail: String, token: String, expiresAt: Instant) {
        val s = store.mailSettingsByDomain(domain)
        // Not sendable ⇒ the unchanged copy-link + dev-file path (ADR 0048 §4). Covers: no settings row, sending
        // disabled, an incomplete config, no stored password, or no env KEK to unseal it.
        if (s == null || !s.enabled || s.host.isNullOrBlank() || s.passwordCiphertext == null || !seal.configured) {
            fallback.sendInvite(domain, ownerEmail, token, expiresAt)
            return
        }
        // The accept link mirrors web/src/shared/inviteLink.ts: the secret rides the URL fragment (ADR 0020). The
        // domain is a bare hostname validated at tenant creation (CreateTenantRequest pattern), so it is safe here.
        val ctx = MailContext(
            link = "https://$domain/tenant/accept#$token",
            domain = domain,
            expiry = EXPIRY_FMT.format(expiresAt),
        )
        val subject = renderer.render(s.subjectTemplate ?: renderer.defaultSubject(), ctx)
        val body = renderer.render(s.bodyTemplate ?: renderer.defaultBody(), ctx)
        val from = s.from?.takeUnless { it.isBlank() } ?: "noreply@$domain"
        deliver(s, from, ownerEmail, subject, body)
    }

    /** Build a per-tenant SMTP client and send. Best-effort: any failure is logged secret-free and swallowed. */
    private fun deliver(s: MailSettingsRow, from: String, to: String, subject: String, body: String) {
        val cfg = MailConfig()
            .setHostname(s.host)
            .setPort(s.port ?: DEFAULT_SMTP_PORT)
            .setStarttls(if (s.tls) StartTLSOptions.REQUIRED else StartTLSOptions.DISABLED)
        if (!s.username.isNullOrBlank()) {
            cfg.setUsername(s.username)
                .setPassword(seal.unseal(SealedSecret(s.passwordCiphertext!!, s.passwordNonce!!)))
                .setLogin(LoginOption.REQUIRED)
        }
        val client = MailClient.create(vertx, cfg)
        try {
            val message = MailMessage().setFrom(from).setTo(to).setSubject(subject).setText(body)
            client.sendMail(message).toCompletionStage().toCompletableFuture().get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: Exception) {
            // Never log the token or the accept link; only the recipient + a cause message.
            log.warnf("SMTP delivery of a tenant invite to %s failed: %s", to, e.message)
        } finally {
            client.close()
        }
    }

    private companion object {
        const val DEFAULT_SMTP_PORT = 587
        const val SEND_TIMEOUT_SECONDS = 30L
        val EXPIRY_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)
    }
}
