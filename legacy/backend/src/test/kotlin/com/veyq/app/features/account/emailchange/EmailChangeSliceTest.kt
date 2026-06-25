package com.veyq.app.features.account.emailchange

import com.veyq.app.shared.identity.ServerRole
import com.veyq.app.shared.identity.User
import com.veyq.app.shared.identity.UserRepository
import com.veyq.app.shared.security.SessionService
import io.quarkus.mailer.MockMailbox
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.hamcrest.CoreMatchers.`is` as iz

/**
 * uc.account.change-email — the authenticated request → token-to-new-address → token-gated confirm flow.
 * Email is not part of key derivation, so the slice only needs to prove the endpoint mechanics: a token is
 * emailed to the NEW address, confirm swaps the account email and is single-use, and a taken address is rejected.
 */
@QuarkusTest
class EmailChangeSliceTest {
    @Inject lateinit var users: UserRepository
    @Inject lateinit var sessions: SessionService
    @Inject lateinit var mailbox: MockMailbox

    private val email = "ec-user@example.com"
    private val takenEmail = "ec-taken@example.com"

    @BeforeTest
    fun seed() {
        mailbox.clear()
        QuarkusTransaction.requiringNew().run {
            if (users.findById("ec-user") != null) return@run // email may already have changed; guard on stable id
            val now = Instant.now()
            users.persist(User().apply { id = "ec-user"; this.email = this@EmailChangeSliceTest.email; displayName = "U"; serverRole = ServerRole.ServerMember; createdAt = now })
            users.persist(User().apply { id = "ec-other"; this.email = takenEmail; displayName = "O"; serverRole = ServerRole.ServerMember; createdAt = now })
        }
    }

    private fun tokenFromEmail(addr: String): String {
        val msgs = mailbox.getMessagesSentTo(addr)
        assertEquals(1, msgs.size)
        val match = Regex("#confirm-email=([A-Za-z0-9_-]+)").find(msgs[0].text)
        return assertNotNull(match, "confirm link missing").groupValues[1]
    }

    @Test
    fun changesEmailViaTokenToNewAddress() {
        val session = "Bearer ${sessions.issue("ec-user")}"
        val newEmail = "ec-new@example.com"

        given().header("Authorization", session).contentType(ContentType.JSON).body("""{"newEmail":"$newEmail"}""")
            .post("/api/me/email-change/request").then().statusCode(204)

        val token = tokenFromEmail(newEmail)

        given().contentType(ContentType.JSON).body("""{"token":"$token"}""")
            .post("/api/me/email-change/confirm").then().statusCode(200)
            .body("email", iz(newEmail))

        // the swap persisted: the same (still-valid) session now reports the new email
        given().header("Authorization", session).get("/api/me").then().statusCode(200).body("email", iz(newEmail))

        // the token is single-use: a second confirm fails
        given().contentType(ContentType.JSON).body("""{"token":"$token"}""")
            .post("/api/me/email-change/confirm").then().statusCode(400)
    }

    @Test
    fun rejectsChangeToTakenEmail() {
        val session = "Bearer ${sessions.issue("ec-user")}"
        given().header("Authorization", session).contentType(ContentType.JSON).body("""{"newEmail":"$takenEmail"}""")
            .post("/api/me/email-change/request").then().statusCode(409)
        assertTrue(mailbox.getMessagesSentTo(takenEmail).isEmpty())
    }

    @Test
    fun rejectsConfirmWithInvalidToken() {
        given().contentType(ContentType.JSON).body("""{"token":"not-a-real-token"}""")
            .post("/api/me/email-change/confirm").then().statusCode(400)
    }

    @Test
    fun rejectsRequestWithoutValidSession() {
        given().header("Authorization", "Bearer bogus-token").contentType(ContentType.JSON).body("""{"newEmail":"x@example.com"}""")
            .post("/api/me/email-change/request").then().statusCode(401)
    }
}
