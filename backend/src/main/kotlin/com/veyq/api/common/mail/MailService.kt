package com.veyq.api.common.mail

import com.veyq.api.domain.Tenant
import com.veyq.api.domain.User
import io.quarkus.mailer.Mail
import io.quarkus.mailer.Mailer
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

@ApplicationScoped
class MailService @Inject constructor(
    private val mailer: Mailer,
    @param:ConfigProperty(name = "quarkus.mailer.from")
    private val senderAddress: String,
){
    companion object {
        private val LOG: Logger = Logger.getLogger(MailService::class.java)
    }

    fun sendInvitationMail(user: User, inviteUrl: String, tenant: Tenant) {
        sendSafely(
            Mail.withText(
                user.email,
                "veyq Einladung",
                """
                Hallo ${user.displayName},

                fuer dich wurde ein veyq-Account angelegt.
                Lege dein Passwort ueber diesen Link fest:

                $inviteUrl
                """.trimIndent(),
            ).setFrom(sender(tenant.displayName)),
            purpose = "invitation",
            recipient = user.email,
        )
    }

    fun sendPasswordResetMail(user: User, resetUrl: String, tenant: Tenant) {
        sendSafely(
            Mail.withText(
                user.email,
                "veyq Password Reset",
                """
                    Hallo ${user.displayName},
                    
                    du kannst dein Passwort über diesen Link zurücksetzen:
                    
                    $resetUrl
                """.trimIndent()
            ).setFrom(sender(tenant.displayName)),
            purpose = "password reset",
            recipient = user.email,
        )
    }

    fun sendEmailChangeConfirmationMail(user: User, pendingEmail: String, confirmUrl: String, tenant: Tenant) {
        sendSafely(
            Mail.withText(
                pendingEmail,
                "veyq Email bestaetigen",
                """
                    Hallo ${user.displayName},

                    bestaetige deine neue Email-Adresse ueber diesen Link:

                    $confirmUrl
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
}
