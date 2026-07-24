package com.veyq.app.features.admin.tenant

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.GreenMailUtil
import com.icegreen.greenmail.util.ServerSetup
import com.veyq.app.shared.crypto.ConfigSecretSeal
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end SMTP send (cr-email-smtp-transport, ADR 0048/0049) against a **hermetic in-JVM GreenMail** server —
 * runs in `./gradlew check`, no external container. Proves two things the renderer unit test cannot: that the
 * `@Alternative` [SmtpTenantInviteMailer] is the wired [TenantInviteMailer], and that a **real** sent mail carries
 * exactly the rendered closed-set content (link+domain+expiry + owner prose) from the tenant's own address — and
 * that an unconfigured tenant sends **no** SMTP at all (the copy-link fallback). Mailpit (`mail` profile) is the
 * manual dev smoke; this is the automated coverage claim.
 */
@QuarkusTest
class SmtpInviteMailSendTest {

    @Inject lateinit var mailer: TenantInviteMailer // the @Alternative SmtpTenantInviteMailer wins this injection
    @Inject lateinit var store: TenantStore
    @Inject lateinit var seal: ConfigSecretSeal

    @Test
    fun aConfiguredTenantSendsARenderedInviteCarryingOnlyTheClosedSet() {
        val greenMail = GreenMail(ServerSetup(SMTP_PORT, "127.0.0.1", "smtp"))
        greenMail.start()
        try {
            greenMail.setUser("smtpuser", "smtppass") // Vert.x AUTHs with the unsealed password against this user
            val domain = "send.example.com"
            seedTenant(domain)
            val sealed = seal.seal("smtppass")
            QuarkusTransaction.requiringNew().run {
                store.saveMailSettings(
                    tenantIdByDomain(domain),
                    MailSettingsRow(
                        host = "127.0.0.1", port = SMTP_PORT, from = "noreply@$domain", username = "smtpuser",
                        passwordCiphertext = sealed.ciphertext, passwordNonce = sealed.nonce,
                        tls = false, enabled = true,
                        subjectTemplate = "Invite to {{domain}}",
                        bodyTemplate = "Hi! You are invited to {{domain}}. Accept {{link}} before {{expiry}}.",
                    ),
                )
            }

            mailer.sendInvite(domain, "invitee@elsewhere.example", "TOKEN-abc123", Instant.parse("2026-07-31T12:00:00Z"))

            assertTrue(greenMail.waitForIncomingEmail(5_000, 1), "the invite mail should be delivered")
            val messages = greenMail.receivedMessages
            assertEquals(1, messages.size)
            val message = messages[0]
            assertEquals("Invite to send.example.com", message.subject)
            assertEquals("noreply@send.example.com", message.from[0].toString())
            val body = GreenMailUtil.getBody(message)
            assertTrue(body.contains("https://send.example.com/tenant/accept#TOKEN-abc123"), "the accept link (secret in fragment) rides the body")
            assertTrue(body.contains("send.example.com"), "the tenant domain rides the body")
            assertTrue(body.contains("2026-07-31 12:00 UTC"), "the expiry rides the body")
            assertFalse(body.contains("{{"), "every closed-set tag must be substituted — no raw template markers")
        } finally {
            greenMail.stop()
        }
    }

    @Test
    fun anUnconfiguredTenantSendsNoSmtpAndFallsBack() {
        val greenMail = GreenMail(ServerSetup(SMTP_PORT_2, "127.0.0.1", "smtp"))
        greenMail.start()
        try {
            val domain = "no-smtp.example.com"
            seedTenant(domain) // deliberately no mail-settings row ⇒ the copy-link fallback

            mailer.sendInvite(domain, "x@elsewhere.example", "TOKEN-xyz", Instant.parse("2026-07-31T12:00:00Z"))

            assertFalse(greenMail.waitForIncomingEmail(1_000, 1), "an unconfigured tenant must not send SMTP")
            assertEquals(0, greenMail.receivedMessages.size)
        } finally {
            greenMail.stop()
        }
    }

    private fun seedTenant(domain: String) {
        QuarkusTransaction.requiringNew().run {
            TenantEntity().apply {
                id = UUID.randomUUID().toString(); name = "Fam"; this.domain = domain
                ownerEmail = "owner@$domain"; status = TenantEntity.ACTIVE; createdAt = Instant.now()
            }.persist()
        }
    }

    private fun tenantIdByDomain(domain: String): String =
        QuarkusTransaction.requiringNew().call { TenantEntity.find("domain", domain).firstResult()!!.id }

    private companion object {
        const val SMTP_PORT = 13925
        const val SMTP_PORT_2 = 13926
    }
}
