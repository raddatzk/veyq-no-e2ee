package com.veyq.app.features.admin.createtenantinviteowner

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
import org.hamcrest.CoreMatchers.startsWith
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test

@QuarkusTest
class TenantProvisioningSliceTest {

    @Inject lateinit var users: UserRepository
    @Inject lateinit var sessions: SessionService
    @Inject lateinit var mailbox: MockMailbox

    private lateinit var ownerToken: String
    private lateinit var memberToken: String

    @BeforeTest
    fun seed() {
        mailbox.clear()
        QuarkusTransaction.requiringNew().run {
            if (users.findByEmail("srvowner@example.com") == null) {
                // systemAccount = true keeps these seeds from tripping the global setup gate
                // (countServerOwners ignores system accounts); the role check is unaffected.
                users.persist(User().apply {
                    id = "srv-owner"; email = "srvowner@example.com"; displayName = "Srv Owner"
                    serverRole = ServerRole.ServerOwner; systemAccount = true; createdAt = Instant.now()
                })
                users.persist(User().apply {
                    id = "srv-member"; email = "srvmember@example.com"; displayName = "Srv Member"
                    serverRole = ServerRole.ServerMember; systemAccount = true; createdAt = Instant.now()
                })
            }
        }
        ownerToken = "Bearer ${sessions.issue("srv-owner")}"
        memberToken = "Bearer ${sessions.issue("srv-member")}"
    }

    @Test
    fun provisionInviteAndClaim() {
        // 1. only a Server Owner can create a tenant shell (valid body so it reaches the auth check)
        given().header("Authorization", memberToken).contentType(ContentType.JSON)
            .body("""{"name":"acme","displayName":"Acme","domain":"acme.example"}""")
            .post("/api/tenants").then().statusCode(403)

        val tenantId = given().header("Authorization", ownerToken).contentType(ContentType.JSON)
            .body("""{"name":"acme","displayName":"Acme","domain":"acme.example"}""")
            .post("/api/tenants").then().statusCode(200)
            .body("status", `is`("PENDING"))
            .body("name", `is`("acme"))
            .extract().path<String>("id")

        // the domain is unique: a different tenant name but the same domain is rejected
        given().header("Authorization", ownerToken).contentType(ContentType.JSON)
            .body("""{"name":"acme2","displayName":"Acme2","domain":"acme.example"}""")
            .post("/api/tenants").then().statusCode(409).body("code", `is`("DOMAIN_TAKEN"))

        // 2. issue an owner invite. The bootstrap secret is delivered by email, not returned to the caller.
        val invite = given().header("Authorization", ownerToken).contentType(ContentType.JSON)
            .body("""{"email":"srvowner@example.com"}""")
            .post("/api/tenants/$tenantId/owner-invite").then().statusCode(200).extract()
        val inviteId = invite.path<String>("inviteId")
        invite.path<String?>("bootstrapSecret")?.let { throw AssertionError("owner invite response must not expose bootstrapSecret") }
        val ownerMail = mailbox.getMessagesSentTo("srvowner@example.com").last().text
        org.junit.jupiter.api.Assertions.assertTrue(ownerMail.contains("http://acme.example/#invite=$inviteId&s="))
        val bootstrapSecret = ownerInviteSecret("srvowner@example.com", inviteId)

        // 3. public metadata exposes the tenant but not the secret
        given().get("/api/tenant-invites/$inviteId").then().statusCode(200)
            .body("tenant.name", `is`("acme"))
            .body("role", `is`("Owner"))
            .body("status", `is`("PENDING"))
            .body("existingAccount", `is`(true))

        // 4. a wrong bootstrap secret is rejected
        given().header("Authorization", ownerToken).contentType(ContentType.JSON)
            .body(claimBody("AAAA"))
            .post("/api/tenant-invites/$inviteId/claim").then().statusCode(400)
            .body("code", `is`("BAD_BOOTSTRAP_SECRET"))

        given().header("Authorization", memberToken).contentType(ContentType.JSON)
            .body(claimBody(bootstrapSecret))
            .post("/api/tenant-invites/$inviteId/claim").then().statusCode(403)
            .body("code", `is`("INVITE_EMAIL_MISMATCH"))

        // 5. claim ownership: scope public key + scope private key wrapped to the claimer
        given().header("Authorization", ownerToken).contentType(ContentType.JSON)
            .body(claimBody(bootstrapSecret))
            .post("/api/tenant-invites/$inviteId/claim").then().statusCode(200)
            .body("status", `is`("ACTIVE"))

        given().header("Authorization", memberToken).contentType(ContentType.JSON)
            .body("""{"contentType":"image/png","bytes":"bm9wZQ=="}""")
            .put("/api/tenants/$tenantId/logo").then().statusCode(403)

        given().header("Authorization", ownerToken).contentType(ContentType.JSON)
            .body("""{"contentType":"image/png","bytes":"aW1hZ2U="}""")
            .put("/api/tenants/$tenantId/logo").then().statusCode(200)
            .body("logoUrl", startsWith("/api/tenants/$tenantId/logo?v="))

        given().get("/api/tenants/$tenantId/logo").then().statusCode(200)
            .contentType("image/png")
            .body(`is`("image"))

        // 6. blindness: the stored scope grant is byte-identical to what was posted
        given().header("Authorization", ownerToken)
            .get("/api/tenants/$tenantId/scope").then().statusCode(200)
            .body("scopePublicKey.kemPublicKey", `is`("a2Vt"))
            .body("wrappedScopePrivateKey.bytes", `is`("Z3JhbnQ"))

        // 7. the invite is single-use
        given().header("Authorization", ownerToken).contentType(ContentType.JSON)
            .body(claimBody(bootstrapSecret))
            .post("/api/tenant-invites/$inviteId/claim").then().statusCode(409)
            .body("code", `is`("INVITE_NOT_PENDING"))

        // 7b. a fresh owner invite on the already-claimed tenant cannot mint a second scope
        val invite2 = given().header("Authorization", ownerToken).contentType(ContentType.JSON)
            .body("""{"email":"srvowner@example.com"}""")
            .post("/api/tenants/$tenantId/owner-invite").then().statusCode(200).extract()
        val invite2Id = invite2.path<String>("inviteId")
        given().header("Authorization", ownerToken).contentType(ContentType.JSON)
            .body(claimBody(ownerInviteSecret("srvowner@example.com", invite2Id)))
            .post("/api/tenant-invites/$invite2Id/claim").then().statusCode(409)
            .body("code", `is`("TENANT_ALREADY_CLAIMED"))

        // 8. a bogus session does not resolve (valid body so it reaches the auth check)
        given().header("Authorization", "Bearer nope").contentType(ContentType.JSON)
            .body("""{"name":"other","displayName":"Other","domain":"other.example"}""")
            .post("/api/tenants").then().statusCode(401)
    }

    private fun claimBody(secret: String) = """
        {
          "bootstrapSecret": "$secret",
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
