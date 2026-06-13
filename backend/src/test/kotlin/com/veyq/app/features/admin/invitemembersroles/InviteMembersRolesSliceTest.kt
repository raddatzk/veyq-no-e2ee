package com.veyq.app.features.admin.invitemembersroles

import com.veyq.app.shared.identity.ServerRole
import com.veyq.app.shared.identity.User
import com.veyq.app.shared.identity.UserRepository
import com.veyq.app.shared.keymgmt.IdentityKey
import com.veyq.app.shared.keymgmt.IdentityKeyRepository
import com.veyq.app.shared.keymgmt.Scope
import com.veyq.app.shared.keymgmt.ScopeGrant
import com.veyq.app.shared.keymgmt.ScopeGrantRepository
import com.veyq.app.shared.keymgmt.ScopeRepository
import com.veyq.app.shared.security.SessionService
import com.veyq.app.shared.tenancy.Membership
import com.veyq.app.shared.tenancy.MembershipRepository
import com.veyq.app.shared.tenancy.Tenant
import com.veyq.app.shared.tenancy.TenantRepository
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
class InviteMembersRolesSliceTest {

    @Inject lateinit var users: UserRepository
    @Inject lateinit var identityKeys: IdentityKeyRepository
    @Inject lateinit var tenants: TenantRepository
    @Inject lateinit var scopes: ScopeRepository
    @Inject lateinit var grants: ScopeGrantRepository
    @Inject lateinit var memberships: MembershipRepository
    @Inject lateinit var sessions: SessionService

    private lateinit var ownerToken: String
    private lateinit var memberToken: String
    private lateinit var noKeyOwnerToken: String
    private val tenantId = "im-tenant"
    private val scopeId = "im-scope"

    @BeforeTest
    fun seed() {
        QuarkusTransaction.requiringNew().run {
            val now = Instant.now()
            seedUser("im-owner", "im-owner@example.com")
            seedUser("im-recipient", "im-recipient@example.com")
            seedUser("im-member", "im-member@example.com")
            seedUser("im-nokey-owner", "im-nokey-owner@example.com")
            seedIdentityKey("im-owner")
            seedIdentityKey("im-recipient")
            seedIdentityKey("im-member")
            seedIdentityKey("im-nokey-owner")

            if (tenants.findById(tenantId) == null) {
                tenants.persist(Tenant().apply {
                    id = tenantId; name = "im-acme"; displayName = "Acme"; status = "ACTIVE"
                    createdBy = "im-owner"; createdAt = now
                })
                scopes.persist(Scope().apply {
                    id = scopeId; this.tenantId = this@InviteMembersRolesSliceTest.tenantId; kind = "TENANT_ROOT"
                    suiteId = "v1"; kemPublicKey = "scope-pub".toByteArray(); createdAt = now
                })
                // the owner is the first key-holder; the no-key owner has the role but no grant
                grants.persist(ScopeGrant().apply {
                    id = "im-grant-owner"; this.scopeId = this@InviteMembersRolesSliceTest.scopeId; userId = "im-owner"
                    suiteId = "v1"; wrappedPrivateKey = "owner-grant".toByteArray(); createdAt = now
                })
                seedMembership("im-m-owner", "im-owner", "Owner")
                seedMembership("im-m-member", "im-member", "Member")
                seedMembership("im-m-nokey", "im-nokey-owner", "Owner")
            }
        }
        ownerToken = "Bearer ${sessions.issue("im-owner")}"
        memberToken = "Bearer ${sessions.issue("im-member")}"
        noKeyOwnerToken = "Bearer ${sessions.issue("im-nokey-owner")}"
    }

    @Test
    fun lookUpDirectoryEntry() {
        given().header("Authorization", ownerToken)
            .queryParam("email", "im-recipient@example.com")
            .get("/api/directory/users").then().statusCode(200)
            .body("userId", `is`("im-recipient"))
            .body("identityPublicKey.kemPublicKey", notNullValue())

        // a directory read needs a valid session, and an unknown email is a 404
        given().header("Authorization", "Bearer nope")
            .queryParam("email", "im-recipient@example.com")
            .get("/api/directory/users").then().statusCode(401)
        given().header("Authorization", ownerToken)
            .queryParam("email", "nobody@example.com")
            .get("/api/directory/users").then().statusCode(404)
    }

    @Test
    fun grantsScopeAccessOnlyAsAKeyHoldingOwner() {
        // a non-owner cannot grant
        given().header("Authorization", memberToken).contentType(ContentType.JSON)
            .body(grantBody("im-recipient", "Member", "Z3JhbnRlZA"))
            .post("/api/tenants/$tenantId/grants").then().statusCode(403)
            .body("code", `is`("FORBIDDEN"))

        // an Owner who does not hold the scope cannot grant what they cannot read (E1)
        given().header("Authorization", noKeyOwnerToken).contentType(ContentType.JSON)
            .body(grantBody("im-recipient", "Member", "Z3JhbnRlZA"))
            .post("/api/tenants/$tenantId/grants").then().statusCode(403)
            .body("code", `is`("NOT_A_KEY_HOLDER"))

        // self-grant and unknown recipient are rejected
        given().header("Authorization", ownerToken).contentType(ContentType.JSON)
            .body(grantBody("im-owner", "Member", "Z3JhbnRlZA"))
            .post("/api/tenants/$tenantId/grants").then().statusCode(400).body("code", `is`("SELF_GRANT"))
        given().header("Authorization", ownerToken).contentType(ContentType.JSON)
            .body(grantBody("ghost", "Member", "Z3JhbnRlZA"))
            .post("/api/tenants/$tenantId/grants").then().statusCode(404)

        // the key-holding Owner grants the recipient access + a role
        given().header("Authorization", ownerToken).contentType(ContentType.JSON)
            .body(grantBody("im-recipient", "Member", "Z3JhbnRlZA"))
            .post("/api/tenants/$tenantId/grants").then().statusCode(200)
            .body("userId", `is`("im-recipient"))
            .body("role", `is`("Member"))
            .body("status", `is`("ACTIVE"))

        // blindness: the recipient now reads back the exact ciphertext that was posted
        val recipientToken = "Bearer ${sessions.issue("im-recipient")}"
        given().header("Authorization", recipientToken)
            .get("/api/tenants/$tenantId/scope").then().statusCode(200)
            .body("wrappedScopePrivateKey.bytes", `is`("Z3JhbnRlZA"))

        // re-granting replaces the wrapped key (idempotent upsert)
        given().header("Authorization", ownerToken).contentType(ContentType.JSON)
            .body(grantBody("im-recipient", "Owner", "cmV3cmFwcGVk"))
            .post("/api/tenants/$tenantId/grants").then().statusCode(200)
            .body("role", `is`("Owner"))
        given().header("Authorization", recipientToken)
            .get("/api/tenants/$tenantId/scope").then().statusCode(200)
            .body("wrappedScopePrivateKey.bytes", `is`("cmV3cmFwcGVk"))
    }

    private fun seedUser(id: String, email: String) {
        if (users.findByEmail(email) != null) return
        users.persist(User().apply {
            this.id = id; this.email = email; displayName = id
            serverRole = ServerRole.ServerMember; createdAt = Instant.now()
        })
    }

    private fun seedIdentityKey(userId: String) {
        if (identityKeys.findByUserId(userId) != null) return
        identityKeys.persist(IdentityKey().apply {
            id = "ik-$userId"; this.userId = userId; suiteId = "v1"
            kemPublicKey = "$userId-kem".toByteArray(); signPublicKey = "$userId-sign".toByteArray()
            wrappedPrivateKey = "$userId-priv".toByteArray(); wrappedPrivateSuiteId = "v1"; createdAt = Instant.now()
        })
    }

    private fun seedMembership(id: String, userId: String, role: String) {
        memberships.persist(Membership().apply {
            this.id = id; tenantId = this@InviteMembersRolesSliceTest.tenantId; this.userId = userId
            this.role = role; status = "ACTIVE"; createdAt = Instant.now()
        })
    }

    private fun grantBody(recipientUserId: String, role: String, bytes: String) = """
        {
          "recipientUserId": "$recipientUserId",
          "role": "$role",
          "wrappedScopePrivateKey": { "suiteId": "v1", "bytes": "$bytes" }
        }
    """.trimIndent()
}
