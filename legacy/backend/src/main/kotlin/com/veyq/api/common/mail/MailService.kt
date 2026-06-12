package com.veyq.api.common.mail

import com.veyq.api.domain.Tenant
import com.veyq.api.domain.User
import io.quarkus.mailer.Mail
import io.quarkus.mailer.Mailer
import io.quarkus.mailer.runtime.Mailers
import io.quarkus.mailer.runtime.MailersRuntimeConfig
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

@ApplicationScoped
class MailService @Inject constructor(
    private val mailer: Mailer,
    mailersRuntimeConfig: MailersRuntimeConfig,
) {
    private val senderAddress: String =
        mailersRuntimeConfig.mailers()[Mailers.DEFAULT_MAILER_NAME]
            ?.from()
            ?.orElse(null)
            ?: throw IllegalStateException("quarkus.mailer.from must be configured")

    companion object {
        private val LOG: Logger = Logger.getLogger(MailService::class.java)
        private val LINK_EXPIRY_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm 'UTC'", Locale.GERMAN)
                .withZone(ZoneOffset.UTC)
    }

    fun sendInvitationMailWithUserCompletion(user: User, inviteUrl: String, expiresAt: Instant, tenant: Tenant) {
        sendSafely(
            Mail.withText(
                user.email,
                "veyq Einladung",
                """
                Hallo ${user.displayName},

                du wurdest in ${tenant.displayName} eingeladen.
                Vervollständige deinen Account und tritt über diesen Link bei:

                $inviteUrl

                Dieser Link ist gültig bis ${formatLinkExpiry(expiresAt)}.
                """.trimIndent(),
            ).setFrom(sender(tenant.displayName)),
            purpose = "invitation",
            recipient = user.email,
        )
    }

    fun sendInvitationMail(user: User, inviteUrl: String, expiresAt: Instant, tenant: Tenant) {
        sendSafely(
            Mail.withText(
                user.email,
                "veyq Einladung",
                """
                Hallo ${user.displayName},

                du wurdest in ${tenant.displayName} eingeladen.
                Über diesen Link kannst du ganz einfach beitreten:

                $inviteUrl

                Dieser Link ist gültig bis ${formatLinkExpiry(expiresAt)}.
                """.trimIndent(),
            ).setFrom(sender(tenant.displayName)),
            purpose = "invitation",
            recipient = user.email,
        )
    }

    fun sendPasswordResetMail(user: User, resetUrl: String, expiresAt: Instant, tenant: Tenant) {
        sendSafely(
            Mail.withText(
                user.email,
                "veyq Password Reset",
                """
                    Hallo ${user.displayName},
                    
                    du kannst dein Passwort über diesen Link zurücksetzen:
                    
                    $resetUrl

                    Dieser Link ist gültig bis ${formatLinkExpiry(expiresAt)}.
                """.trimIndent()
            ).setFrom(sender(tenant.displayName)),
            purpose = "password reset",
            recipient = user.email,
        )
    }

    fun sendEmailChangeConfirmationMail(
        user: User,
        pendingEmail: String,
        confirmUrl: String,
        expiresAt: Instant,
        tenant: Tenant,
    ) {
        sendSafely(
            Mail.withText(
                pendingEmail,
                "veyq Email best#tigen",
                """
                    Hallo ${user.displayName},

                    bestätige deine neue Email-Adresse äber diesen Link:

                    $confirmUrl

                    Dieser Link ist gültig bis ${formatLinkExpiry(expiresAt)}.
                """.trimIndent()
            ).setFrom(sender(tenant.displayName)),
            purpose = "email change confirmation",
            recipient = pendingEmail,
        )
    }

    private fun sendSafely(mail: Mail, purpose: String, recipient: String) {
        try {
            mailer.send(mail)
        } catch (exception: Exception) {
            LOG.warn("Failed to send $purpose mail to $recipient", exception)
        }
    }

    private fun sender(tenantName: String): String =
        "${safeSenderName(tenantName)} <${senderAddress.trim()}>"

    private fun safeSenderName(value: String): String =
        value
            .replace(Regex("[\\r\\n<>]"), " ")
            .trim()
            .ifBlank { "veyq" }

    private fun formatLinkExpiry(expiresAt: Instant): String =
        LINK_EXPIRY_FORMATTER.format(expiresAt)
}
