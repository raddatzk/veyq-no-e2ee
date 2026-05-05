package com.lunva.api.common.mail

import com.lunva.api.domain.User
import io.quarkus.mailer.Mail
import io.quarkus.mailer.Mailer
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

@ApplicationScoped
class MailService @Inject constructor(
    private val mailer: Mailer,
    private val mailConfig: MailConfig,
){
    fun sendInvitationMail(user: User, inviteUrl: String) {
        mailer.send(
            Mail.withText(
                user.email,
                "Lunva Einladung",
                """
                Hallo ${user.displayName},

                fuer dich wurde ein Lunva-Account angelegt.
                Lege dein Passwort ueber diesen Link fest:

                $inviteUrl
                """.trimIndent(),
            )
        )
    }

    fun sendPasswordResetMail(user: User, resetUrl: String) {
        mailer.send(
            Mail.withText(
                user.email,
                "Lunva Password Reset",
                """
                    Hallo ${user.displayName},
                    
                    du kannst dein Passwort über diesen Link zurücksetzen:
                    
                    $resetUrl
                """.trimIndent()
            )
        )
    }

    fun sendEmailChangeConfirmationMail(user: User, pendingEmail: String, confirmUrl: String) {
        mailer.send(
            Mail.withText(
                pendingEmail,
                "Lunva Email bestaetigen",
                """
                    Hallo ${user.displayName},

                    bestaetige deine neue Email-Adresse ueber diesen Link:

                    $confirmUrl
                """.trimIndent()
            )
        )
    }
}
