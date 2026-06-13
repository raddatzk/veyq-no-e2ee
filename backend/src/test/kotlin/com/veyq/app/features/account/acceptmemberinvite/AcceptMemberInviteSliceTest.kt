package com.veyq.app.features.account.acceptmemberinvite

import com.veyq.app.shared.identity.ServerRole
import com.veyq.app.shared.identity.User
import com.veyq.app.shared.identity.UserRepository
import com.veyq.app.shared.keymgmt.IdentityKey
import com.veyq.app.shared.keymgmt.IdentityKeyRepository
import com.veyq.app.shared.keymgmt.Scope
import com.veyq.app.shared.keymgmt.ScopeRepository
import com.veyq.app.shared.security.SessionService
import com.veyq.app.shared.tenancy.InviteKind
import com.veyq.app.shared.tenancy.InviteStatus
import com.veyq.app.shared.tenancy.Tenant
import com.veyq.app.shared.tenancy.TenantInvite
import com.veyq.app.shared.tenancy.TenantInviteRepository
import com.veyq.app.shared.tenancy.TenantRepository
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.`is`
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import kotlin.test.BeforeTest
import kotlin.test.Test

@QuarkusTest
class AcceptMemberInviteSliceTest {

    @Inject lateinit var users: UserRepository
    @Inject lateinit var identityKeys: IdentityKeyRepository
    @Inject lateinit var tenants: TenantRepository
    @Inject lateinit var scopes: ScopeRepository
    @Inject lateinit var invites: TenantInviteRepository
    @Inject lateinit var sessions: SessionService

    private lateinit var userToken: String
    private val secretB64 = "YWNjZXB0LXNlY3JldA" // base64url("accept-secret")

    @BeforeTest
    fun seed() {
        QuarkusTransaction.requiringNew().run {
            val now = Instant.now()
            if (users.findByEmail("acc-user@example.com") == null) {
                users.persist(User().apply {
                    id = "acc-user"; email = "acc-user@example.com"; displayName = "Acc User"
                    serverRole = ServerRole.ServerMember; createdAt = now
                })
                identityKeys.persist(IdentityKey().apply {
                    id = "acc-ik"; userId = "acc-user"; suiteId = "v1"
                    kemPublicKey = "kem".toByteArray(); signPublicKey = "sign".toByteArray()
                    wrappedPrivateKey = "priv".toByteArray(); wrappedPrivateSuiteId = "v1"; createdAt = now
                })
                tenants.persist(Tenant().apply {
                    id = "acc-tenant"; name = "acc-acme"; displayName = "AccAcme"; status = "ACTIVE"
                    createdBy = "acc-user"; createdAt = now
                })
                scopes.persist(Scope().apply {
                    id = "acc-scope"; tenantId = "acc-tenant"; kind = "TENANT_ROOT"
                    suiteId = "v1"; kemPublicKey = "scope-pub".toByteArray(); createdAt = now
                })
                val hash = sha256(Base64.getUrlDecoder().decode(secretB64))
                invites.persist(memberInvite("acc-invite", hash, now))
                invites.persist(memberInvite("acc-invite-2", hash, now))
                // an owner invite cannot be accepted onto an existing account
                invites.persist(TenantInvite().apply {
                    id = "acc-owner-invite"; tenantId = "acc-tenant"; role = "Owner"
                    secretHash = hash; status = InviteStatus.PENDING.name; kind = InviteKind.OWNER.name
                    createdBy = "acc-user"; createdAt = now; expiresAt = now.plusSeconds(3600)
                })
            }
        }
        userToken = "Bearer ${sessions.issue("acc-user")}"
    }

    @Test
    fun acceptsMemberInviteOntoExistingAccount() {
        // session + bootstrap secret + invite kind are all enforced
        given().header("Authorization", "Bearer nope").contentType(ContentType.JSON).body(acceptBody(secretB64, "YWNjZXB0LWdyYW50"))
            .post("/api/tenant-invites/acc-invite/accept").then().statusCode(401)
        given().header("Authorization", userToken).contentType(ContentType.JSON).body(acceptBody("d3Jvbmc", "YWNjZXB0LWdyYW50"))
            .post("/api/tenant-invites/acc-invite/accept").then().statusCode(400).body("code", `is`("BAD_BOOTSTRAP_SECRET"))
        given().header("Authorization", userToken).contentType(ContentType.JSON).body(acceptBody(secretB64, "YWNjZXB0LWdyYW50"))
            .post("/api/tenant-invites/acc-owner-invite/accept").then().statusCode(400).body("code", `is`("NOT_A_MEMBER_INVITE"))

        // accept onto the existing account: grant + membership, no new account
        given().header("Authorization", userToken).contentType(ContentType.JSON).body(acceptBody(secretB64, "YWNjZXB0LWdyYW50"))
            .post("/api/tenant-invites/acc-invite/accept").then().statusCode(200)
            .body("userId", `is`("acc-user"))
            .body("role", `is`("Member"))
            .body("status", `is`("ACTIVE"))

        // the same account now holds the scope grant it just re-wrapped to itself
        given().header("Authorization", userToken)
            .get("/api/tenants/acc-tenant/scope").then().statusCode(200)
            .body("wrappedScopePrivateKey.bytes", `is`("YWNjZXB0LWdyYW50"))

        // the invite is single-use, and a second invite for an already-granted user is rejected
        given().header("Authorization", userToken).contentType(ContentType.JSON).body(acceptBody(secretB64, "YWNjZXB0LWdyYW50"))
            .post("/api/tenant-invites/acc-invite/accept").then().statusCode(409).body("code", `is`("INVITE_NOT_PENDING"))
        given().header("Authorization", userToken).contentType(ContentType.JSON).body(acceptBody(secretB64, "YWNjZXB0LWdyYW50"))
            .post("/api/tenant-invites/acc-invite-2/accept").then().statusCode(409).body("code", `is`("ALREADY_GRANTED"))
    }

    private fun memberInvite(id: String, hash: ByteArray, now: Instant) = TenantInvite().apply {
        this.id = id; tenantId = "acc-tenant"; role = "Member"
        secretHash = hash; status = InviteStatus.PENDING.name; kind = InviteKind.MEMBER.name
        scopeId = "acc-scope"; tempPublicKey = "tp".toByteArray(); tempWrappedScopePrivateKey = "tw".toByteArray()
        tempWrapSuiteId = "v1"; createdBy = "acc-user"; createdAt = now; expiresAt = now.plusSeconds(3600)
    }

    private fun acceptBody(secret: String, bytes: String) =
        """{"bootstrapSecret":"$secret","wrappedScopePrivateKey":{"suiteId":"v1","bytes":"$bytes"}}"""

    private fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
}
