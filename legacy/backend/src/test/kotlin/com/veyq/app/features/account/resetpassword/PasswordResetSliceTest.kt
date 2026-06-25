package com.veyq.app.features.account.resetpassword

import com.veyq.app.shared.crypto.Argon2Verifier
import com.veyq.app.shared.identity.ServerRole
import com.veyq.app.shared.identity.User
import com.veyq.app.shared.identity.UserRepository
import com.veyq.app.shared.keymgmt.KeyBlob
import com.veyq.app.shared.keymgmt.KeyBlobRepository
import com.veyq.app.shared.security.SessionService
import io.quarkus.mailer.MockMailbox
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import java.time.Instant
import java.util.Base64
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * uc.account.reset-password — the email-gated, Secret-Key-backed reset. The slice verifies the endpoint
 * mechanics: request emails a token (captured by the mock mailbox), material releases the RECOVERY wrap only
 * against that token, confirm swaps the PASSWORD wrap, derives the verifier and invalidates sessions. (The crypto
 * round-trip — that the RECOVERY wrap actually decrypts with the Secret Key — is covered by the web e2e.)
 */
@QuarkusTest
class PasswordResetSliceTest {
    @Inject lateinit var users: UserRepository
    @Inject lateinit var keyBlobs: KeyBlobRepository
    @Inject lateinit var sessions: SessionService
    @Inject lateinit var mailbox: MockMailbox
    @Inject lateinit var argon2: Argon2Verifier

    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val email = "pr-user@example.com"
    private val noRecEmail = "pr-norec@example.com"

    @BeforeTest
    fun seed() {
        mailbox.clear()
        QuarkusTransaction.requiringNew().run {
            if (users.findByEmail(email) != null) return@run
            val now = Instant.now()
            // a recoverable account: PASSWORD + RECOVERY wraps + an auth verifier
            users.persist(User().apply {
                id = "pr-user"; this.email = this@PasswordResetSliceTest.email
                displayName = "U"; serverRole = ServerRole.ServerMember; createdAt = now
                authVerifierKind = "ARGON2_PROOF"; authVerifierSuiteId = "v1"
                authVerifier = argon2.createPasswordVerifier("old-password"); authVerifierCreatedAt = now
            })
            keyBlobs.persist(KeyBlob().apply { id = "pr-pw"; userId = "pr-user"; unlockMethod = "PASSWORD"; suiteId = "v1"; wrappedKey = "old-pw".toByteArray(); kdfParams = "kp".toByteArray() })
            keyBlobs.persist(KeyBlob().apply { id = "pr-rec"; userId = "pr-user"; unlockMethod = "RECOVERY"; suiteId = "v1"; wrappedKey = "rec-wrap".toByteArray(); kdfParams = "rk".toByteArray(); recoveryAuthTag = "rec-tag".toByteArray() })
            // a non-recoverable account: PASSWORD only, no RECOVERY wrap
            users.persist(User().apply { id = "pr-norec"; this.email = noRecEmail; displayName = "N"; serverRole = ServerRole.ServerMember; createdAt = now })
            keyBlobs.persist(KeyBlob().apply { id = "pr-norec-pw"; userId = "pr-norec"; unlockMethod = "PASSWORD"; suiteId = "v1"; wrappedKey = "x".toByteArray() })
        }
    }

    private fun requestReset(addr: String) =
        given().contentType(ContentType.JSON).body("""{"email":"$addr"}""").post("/api/me/password-reset/request").then().statusCode(204)

    private fun tokenFromEmail(addr: String): String {
        val msgs = mailbox.getMessagesSentTo(addr)
        assertEquals(1, msgs.size)
        val match = Regex("#reset=([A-Za-z0-9_-]+)").find(msgs[0].text)
        return assertNotNull(match, "reset link missing").groupValues[1]
    }

    @Test
    fun resetsPasswordViaEmailTokenAndInvalidatesSessions() {
        val session = "Bearer ${sessions.issue("pr-user")}"
        given().header("Authorization", session).get("/api/me").then().statusCode(200)

        requestReset(email)
        val token = tokenFromEmail(email)

        // material is gated by the token and returns the RECOVERY wrap
        given().contentType(ContentType.JSON).body("""{"token":"$token"}""")
            .post("/api/me/password-reset/material").then().statusCode(200)
            .body("email", org.hamcrest.CoreMatchers.`is`(email))
            .body("recoveryWrap.unlockMethod", org.hamcrest.CoreMatchers.`is`("RECOVERY"))

        // confirm swaps the PASSWORD wrap and derives the verifier (with the Secret-Key proof = the stored recovery commitment)
        val newPwWrap = enc.encodeToString("new-pw".toByteArray())
        val newPassword = "new-password-from-reset"
        val recTag = enc.encodeToString("rec-tag".toByteArray())
        given().contentType(ContentType.JSON).body(
            """{"token":"$token","newPassword":"$newPassword","masterKeyWrap":{"unlockMethod":"PASSWORD","suiteId":"v1","wrappedKey":"$newPwWrap","kdfParams":"$newPwWrap"},"recoveryMasterKeyTag":"$recTag"}""",
        ).post("/api/me/password-reset/confirm").then().statusCode(204)

        QuarkusTransaction.requiringNew().run {
            val pw = keyBlobs.findByUserIdAndUnlockMethod("pr-user", "PASSWORD").first()
            assertEquals("new-pw", String(pw.wrappedKey))
            val user = users.findById("pr-user")!!
            assertEquals("ARGON2_PROOF", user.authVerifierKind)
            assertEquals("v1", user.authVerifierSuiteId)
            val verifier = user.authVerifier!!
            assertTrue(argon2.verifyPassword(newPassword, verifier))
            assertTrue(!argon2.verifyPassword("old-password", verifier))
        }
        // the old session was invalidated
        given().header("Authorization", session).get("/api/me").then().statusCode(401)

        // the token is single-use: a second confirm with it fails
        given().contentType(ContentType.JSON).body(
            """{"token":"$token","newPassword":"$newPassword","masterKeyWrap":{"unlockMethod":"PASSWORD","suiteId":"v1","wrappedKey":"$newPwWrap"},"recoveryMasterKeyTag":"$recTag"}""",
        ).post("/api/me/password-reset/confirm").then().statusCode(400)
    }

    /**
     * The High finding: with only the email token (no Secret Key), an attacker could previously set an arbitrary
     * PASSWORD wrap + new password and hijack the SERVER account (content stays encrypted, but the session is
     * theirs). Now confirm must reproduce the recovery commitment — which needs the master key, i.e. the Secret
     * Key. A valid token with a wrong/absent proof is rejected, so email access alone no longer suffices.
     */
    @Test
    fun rejectsConfirmWithoutSecretKeyProof() {
        requestReset(email)
        val token = tokenFromEmail(email)
        val newPwWrap = enc.encodeToString("attacker-pw".toByteArray())
        val newPassword = "attacker-password"
        val wrongTag = enc.encodeToString("wrong-tag".toByteArray())

        // wrong commitment (attacker guessed without the Secret Key) → 403, account untouched
        given().contentType(ContentType.JSON).body(
            """{"token":"$token","newPassword":"$newPassword","masterKeyWrap":{"unlockMethod":"PASSWORD","suiteId":"v1","wrappedKey":"$newPwWrap"},"recoveryMasterKeyTag":"$wrongTag"}""",
        ).post("/api/me/password-reset/confirm").then().statusCode(403)

        // the attacker's password + PASSWORD wrap were NOT applied — the takeover is blocked. (Assert "not the
        // attacker's value" rather than an absolute, since the shared H2 may carry the happy-path's swap.)
        QuarkusTransaction.requiringNew().run {
            assertTrue(!argon2.verifyPassword(newPassword, users.findById("pr-user")!!.authVerifier!!))
            assertTrue(String(keyBlobs.findByUserIdAndUnlockMethod("pr-user", "PASSWORD").first().wrappedKey) != "attacker-pw")
        }
    }

    @Test
    fun silentForUnknownEmailAndAccountsWithoutRecovery() {
        // unknown address: 204, no email sent
        requestReset("nobody@example.com")
        assertTrue(mailbox.getMessagesSentTo("nobody@example.com").isEmpty())
        // account without a RECOVERY wrap: 204, no email (recovery was never possible)
        requestReset(noRecEmail)
        assertTrue(mailbox.getMessagesSentTo(noRecEmail).isEmpty())
    }

    @Test
    fun rejectsInvalidToken() {
        given().contentType(ContentType.JSON).body("""{"token":"not-a-real-token"}""")
            .post("/api/me/password-reset/material").then().statusCode(400)
    }
}
