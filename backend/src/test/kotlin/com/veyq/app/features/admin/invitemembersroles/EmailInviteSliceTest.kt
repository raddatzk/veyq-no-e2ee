package com.veyq.app.features.admin.invitemembersroles

import com.veyq.app.shared.identity.ServerRole
import com.veyq.app.shared.identity.User
import com.veyq.app.shared.identity.UserRepository
import com.veyq.app.shared.security.SessionService
import com.veyq.app.shared.tenancy.Membership
import com.veyq.app.shared.tenancy.MembershipRepository
import com.veyq.app.shared.tenancy.Tenant
import com.veyq.app.shared.tenancy.TenantInvite
import com.veyq.app.shared.tenancy.TenantInviteRepository
import com.veyq.app.shared.tenancy.TenantRepository
import io.quarkus.mailer.MockMailbox
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.`is`
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.veyq.app.shared.tenancy.TenantStatus
import com.veyq.app.shared.tenancy.MembershipStatus
import com.veyq.app.shared.tenancy.MemberRole
import com.veyq.app.shared.tenancy.InviteStatus
import com.veyq.app.shared.tenancy.InviteKind

/** uc.admin.email-invite — the server relays a pending member-invite link to an email (captured by the mock mailbox). */
@QuarkusTest
class EmailInviteSliceTest {
    @Inject lateinit var users: UserRepository
    @Inject lateinit var tenants: TenantRepository
    @Inject lateinit var memberships: MembershipRepository
    @Inject lateinit var invites: TenantInviteRepository
    @Inject lateinit var sessions: SessionService
    @Inject lateinit var mailbox: MockMailbox

    private lateinit var ownerToken: String
    private lateinit var outsiderToken: String
    private val tenantId = "eml-tenant"
    private val inviteId = "eml-invite"
    private val link = "http://localhost:5173/#invite=eml-invite&s=AAAA&k=BBBB"

    @BeforeTest
    fun seed() {
        mailbox.clear()
        QuarkusTransaction.requiringNew().run {
            if (users.findByEmail("eml-owner@example.com") != null) return@run
            val now = Instant.now()
            users.persist(User().apply { id = "eml-owner"; email = "eml-owner@example.com"; displayName = "O"; serverRole = ServerRole.ServerMember; createdAt = now })
            users.persist(User().apply { id = "eml-out"; email = "eml-out@example.com"; displayName = "X"; serverRole = ServerRole.ServerMember; createdAt = now })
            tenants.persist(Tenant().apply { id = tenantId; name = "eml-t"; domain = "eml-t.test"; displayName = "Acme"; status = TenantStatus.ACTIVE; termsVersion = 0; createdBy = "eml-owner"; createdAt = now })
            memberships.persist(Membership().apply { id = "eml-mb"; this.tenantId = this@EmailInviteSliceTest.tenantId; userId = "eml-owner"; role = MemberRole.Owner; status = MembershipStatus.ACTIVE; createdAt = now })
            invites.persist(TenantInvite().apply {
                id = inviteId; this.tenantId = this@EmailInviteSliceTest.tenantId; role = MemberRole.Member; secretHash = "h".toByteArray()
                status = InviteStatus.PENDING; kind = InviteKind.MEMBER; createdBy = "eml-owner"; createdAt = now; expiresAt = now.plusSeconds(86400)
            })
        }
        ownerToken = "Bearer ${sessions.issue("eml-owner")}"
        outsiderToken = "Bearer ${sessions.issue("eml-out")}"
    }

    private fun emailReq(token: String, email: String, link: String) =
        given().header("Authorization", token).contentType(ContentType.JSON)
            .body("""{"email":"$email","inviteId":"$inviteId","link":"$link"}""")
            .post("/api/tenants/$tenantId/member-invites/email").then()

    @Test
    fun ownerEmailsThePendingInviteLink() {
        // a non-owner cannot send; a link not pointing at this instance is rejected; a bad email is rejected
        emailReq(outsiderToken, "bob@example.com", link).statusCode(403)
        emailReq(ownerToken, "bob@example.com", "https://evil.example/#invite=x").statusCode(400).body("code", `is`("BAD_LINK"))
        emailReq(ownerToken, "not-an-email", link).statusCode(400).body("code", `is`("INVALID_EMAIL"))
        // origin (not prefix) check: these all `startsWith(appBaseUrl)` but resolve to a different origin → rejected
        emailReq(ownerToken, "bob@example.com", "http://localhost:5173@evil.com/#invite=x").statusCode(400).body("code", `is`("BAD_LINK"))
        emailReq(ownerToken, "bob@example.com", "http://localhost:5173.evil.com/#invite=x").statusCode(400).body("code", `is`("BAD_LINK"))
        emailReq(ownerToken, "bob@example.com", "http://localhost:51730/#invite=x").statusCode(400).body("code", `is`("BAD_LINK"))

        emailReq(ownerToken, "bob@example.com", link).statusCode(204)

        val sent = mailbox.getMessagesSentTo("bob@example.com")
        assertEquals(1, sent.size)
        assertTrue(sent[0].text.contains(link))
        assertTrue(sent[0].subject.contains("Acme"))
    }
}
