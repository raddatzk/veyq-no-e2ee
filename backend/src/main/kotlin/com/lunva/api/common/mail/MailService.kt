package com.lunva.api.common.mail

import com.lunva.api.domain.User
import io.quarkus.mailer.Mail
import io.quarkus.mailer.Mailer
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger

@ApplicationScoped
class MailService @Inject constructor(
    private val mailer: Mailer,
    private val mailConfig: MailConfig,
){
    companion object {
        private val LOG: Logger = Logger.getLogger(MailService::class.java)
    }

    fun sendInvitationMail(user: User, inviteUrl: String) {
        sendSafely(
            Mail.withText(
                user.email,
                "Lunva Einladung",
                """
                Hallo ${user.displayName},

                fuer dich wurde ein Lunva-Account angelegt.
                Lege dein Passwort ueber diesen Link fest:

                $inviteUrl
                """.trimIndent(),
            ),
            purpose = "invitation",
            recipient = user.email,
        )
    }

    fun sendPasswordResetMail(user: User, resetUrl: String) {
        sendSafely(
            Mail.withText(
                user.email,
                "Lunva Password Reset",
                """
                    Hallo ${user.displayName},
                    
                    du kannst dein Passwort über diesen Link zurücksetzen:
                    
                    $resetUrl
                """.trimIndent()
            ),
            purpose = "password reset",
            recipient = user.email,
        )
    }

    fun sendEmailChangeConfirmationMail(user: User, pendingEmail: String, confirmUrl: String) {
        sendSafely(
            Mail.withText(
                pendingEmail,
                "Lunva Email bestaetigen",
                """
                    Hallo ${user.displayName},

                    bestaetige deine neue Email-Adresse ueber diesen Link:

                    $confirmUrl
                """.trimIndent()
            ),
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
}
