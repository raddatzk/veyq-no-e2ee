package com.veyq.app.features.account.registerviainvitation

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
import org.hamcrest.CoreMatchers.`is`
import org.hamcrest.CoreMatchers.notNullValue
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test

@QuarkusTest
class RegisterViaInvitationSliceTest {

    @Inject lateinit var users: UserRepository
    @Inject lateinit var sessions: SessionService
    @Inject lateinit var mailbox: MockMailbox

    private lateinit var ownerToken: String

    @BeforeTest
    fun seed() {
        mailbox.clear()
        // A seed identity scoped to this class — sharing ids/emails across slice tests is unsafe because
        // the H2 instance is shared across the run (a sibling test's guarded seed would be skipped).
        QuarkusTransaction.requiringNew().run {
            if (users.findByEmail("reg-srvowner@example.com") == null) {
                // systemAccount = true keeps the seed from tripping the global setup gate.
                users.persist(User().apply {
                    id = "reg-srv-owner"; email = "reg-srvowner@example.com"; displayName = "Srv Owner"
                    serverRole = ServerRole.ServerOwner; systemAccount = true; createdAt = Instant.now()
                })
            }
        }
        ownerToken = "Bearer ${sessions.issue("reg-srv-owner")}"
    }

    @Test
    fun registersNewOwnerAndActivatesTenant() {
        // a Server Owner provisions a tenant shell + owner invite
        val tenantId = given().header("Authorization", ownerToken).contentType(ContentType.JSON)
            .body("""{"name":"reg-acme","displayName":"Acme","domain":"reg-acme.test"}""")
            .post("/api/tenants").then().statusCode(200)
            .extract().path<String>("id")
        val invite = given().header("Authorization", ownerToken).contentType(ContentType.JSON)
            .body("""{"email":"invitee@example.com"}""")
            .post("/api/tenants/$tenantId/owner-invite").then().statusCode(200).extract()
        val inviteId = invite.path<String>("inviteId")
        val bootstrapSecret = ownerInviteSecret("invitee@example.com", inviteId)

        // a wrong bootstrap secret is rejected before any account is created
        given().contentType(ContentType.JSON).body(registerBody("AAAA", "nope@example.com"))
            .post("/api/tenant-invites/$inviteId/register").then().statusCode(400)
            .body("code", `is`("BAD_BOOTSTRAP_SECRET"))

        given().contentType(ContentType.JSON).body(registerBody(bootstrapSecret, "wrong@example.com"))
            .post("/api/tenant-invites/$inviteId/register").then().statusCode(403)
            .body("code", `is`("INVITE_EMAIL_MISMATCH"))

        // the invitee (unauthenticated) registers an account and claims the tenant in one step
        val session = given().contentType(ContentType.JSON)
            .body(registerBody(bootstrapSecret, "invitee@example.com"))
            .post("/api/tenant-invites/$inviteId/register").then().statusCode(200)
            .body("user.email", `is`("invitee@example.com"))
            .body("user.serverRole", `is`("ServerMember"))
            .body("tenant.status", `is`("ACTIVE"))
            .body("sessionToken", notNullValue())
            .extract().path<String>("sessionToken")

        // the returned session resolves
        given().header("Authorization", "Bearer $session")
            .get("/api/me").then().statusCode(200)
            .body("email", `is`("invitee@example.com"))

        // personal access is resolved through the session and remains ciphertext-only
        given().header("Authorization", "Bearer $session")
            .get("/api/me/personal-scope").then().statusCode(200)
            .body("scopePublicKey.kemPublicKey", `is`("cGVyc29uYWw"))
            .body("wrappedScopePrivateKey.bytes", `is`("cGVyc29uYWxncmFudA"))

        // blindness: the stored tenant scope grant is byte-identical to what was posted
        given().header("Authorization", "Bearer $session")
            .get("/api/tenants/$tenantId/scope").then().statusCode(200)
            .body("scopePublicKey.kemPublicKey", `is`("a2Vt"))
            .body("wrappedScopePrivateKey.bytes", `is`("Z3JhbnQ"))

        // the invite is single-use — a second registration is rejected (and no second account is made)
        given().contentType(ContentType.JSON).body(registerBody(bootstrapSecret, "second@example.com"))
            .post("/api/tenant-invites/$inviteId/register").then().statusCode(409)
            .body("code", `is`("INVITE_NOT_PENDING"))
    }

    @Test
    fun rejectsDuplicateEmail() {
        val tenantId = given().header("Authorization", ownerToken).contentType(ContentType.JSON)
            .body("""{"name":"reg-globex","displayName":"Globex","domain":"reg-globex.test"}""")
            .post("/api/tenants").then().statusCode(200).extract().path<String>("id")
        val invite = given().header("Authorization", ownerToken).contentType(ContentType.JSON)
            .body("""{"email":"reg-srvowner@example.com"}""")
            .post("/api/tenants/$tenantId/owner-invite").then().statusCode(200).extract()
        val inviteId = invite.path<String>("inviteId")
        val bootstrapSecret = ownerInviteSecret("reg-srvowner@example.com", inviteId)

        // the seeded Server Owner's email is already taken — the invite stays claimable
        given().contentType(ContentType.JSON).body(registerBody(bootstrapSecret, "reg-srvowner@example.com"))
            .post("/api/tenant-invites/$inviteId/register").then().statusCode(409)
            .body("code", `is`("EMAIL_TAKEN"))
        given().get("/api/tenant-invites/$inviteId").then().statusCode(200)
            .body("status", `is`("PENDING"))
    }

    private fun registerBody(secret: String, email: String) = """
        {
          "bootstrapSecret": "$secret",
          "email": "$email",
          "displayName": "Invited Owner",
          "identityPublicKey": { "suiteId": "v1", "kemPublicKey": "a2VtcHVi", "signPublicKey": "c2lnbg" },
          "wrappedIdentityPrivateKey": { "suiteId": "v1", "bytes": "d3JhcHBlZA" },
          "masterKeyWraps": [
            { "unlockMethod": "PASSWORD", "suiteId": "v1", "wrappedKey": "bWs", "kdfParams": "c2FsdA" }
          ],
          "authVerifier": { "kind": "ARGON2_PROOF", "suiteId": "v1", "verifier": "dmVyaWZpZXI" },
          "personalScopePublicKey": { "suiteId": "v1", "kemPublicKey": "cGVyc29uYWw" },
          "wrappedPersonalScopePrivateKey": { "suiteId": "v1", "bytes": "cGVyc29uYWxncmFudA" },
          "scopePublicKey": { "suiteId": "v1", "kemPublicKey": "a2Vt" },
          "wrappedScopePrivateKey": { "suiteId": "v1", "bytes": "Z3JhbnQ" }
        }
    """.trimIndent()

    private fun ownerInviteSecret(email: String, inviteId: String): String {
        val text = mailbox.getMessagesSentTo(email).last().text
        val marker = "#invite=$inviteId&s="
        return text.substringAfter(marker).substringBefore("\n").substringBefore("&").trim()
    }
}
