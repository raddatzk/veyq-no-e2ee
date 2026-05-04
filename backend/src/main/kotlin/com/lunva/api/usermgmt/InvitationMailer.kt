package com.lunva.api.usermgmt

import com.lunva.api.domain.User
import io.quarkus.mailer.Mail
import io.quarkus.mailer.Mailer
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

@ApplicationScoped
class InvitationMailer @Inject constructor(
    private val mailer: Mailer,
    private val invitationConfig: InvitationConfig,
) {
    fun sendInvitation(user: User, inviteUrl: String) {
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
            ).setFrom(invitationConfig.mailFrom)
        )
    }
}
