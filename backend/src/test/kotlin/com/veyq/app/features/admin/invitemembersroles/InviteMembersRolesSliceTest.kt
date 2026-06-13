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
    private lateinit var removableToken: String
    private val tenantId = "im-tenant"
    private val scopeId = "im-scope"
    // a dedicated tenant with a stable active-member set ({im-owner Owner, im-member Member}) — other
    // tests mutate im-tenant's membership, which would make rotation's "exactly the active members" flaky.
    private val rotTenantId = "im-rot-tenant"
    private val rotScopeId = "im-rot-scope"

    @BeforeTest
    fun seed() {
        QuarkusTransaction.requiringNew().run {
            val now = Instant.now()
            seedUser("im-owner", "im-owner@example.com")
            seedUser("im-recipient", "im-recipient@example.com")
            seedUser("im-member", "im-member@example.com")
            seedUser("im-nokey-owner", "im-nokey-owner@example.com")
            seedUser("im-removable", "im-removable@example.com")
            seedIdentityKey("im-owner")
            seedIdentityKey("im-recipient")
            seedIdentityKey("im-member")
            seedIdentityKey("im-nokey-owner")
            seedIdentityKey("im-removable")

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
                // a removable member who actually holds a grant (to prove removal revokes key access)
                grants.persist(ScopeGrant().apply {
                    id = "im-grant-removable"; this.scopeId = this@InviteMembersRolesSliceTest.scopeId; userId = "im-removable"
                    suiteId = "v1"; wrappedPrivateKey = "removable-grant".toByteArray(); createdAt = now
                })
                seedMembership("im-m-owner", "im-owner", "Owner")
                seedMembership("im-m-member", "im-member", "Member")
                seedMembership("im-m-nokey", "im-nokey-owner", "Owner")
                seedMembership("im-m-removable", "im-removable", "Member")

                // dedicated rotation tenant: stable active members {im-owner Owner, im-member Member}
                tenants.persist(Tenant().apply {
                    id = rotTenantId; name = "im-rot-acme"; displayName = "RotAcme"; status = "ACTIVE"
                    createdBy = "im-owner"; createdAt = now
                })
                scopes.persist(Scope().apply {
                    id = rotScopeId; tenantId = rotTenantId; kind = "TENANT_ROOT"
                    suiteId = "v1"; kemPublicKey = "rot-pub".toByteArray(); createdAt = now
                })
                grants.persist(ScopeGrant().apply {
                    id = "im-rot-grant-owner"; scopeId = rotScopeId; userId = "im-owner"
                    suiteId = "v1"; wrappedPrivateKey = "rot-owner".toByteArray(); createdAt = now
                })
                grants.persist(ScopeGrant().apply {
                    id = "im-rot-grant-member"; scopeId = rotScopeId; userId = "im-member"
                    suiteId = "v1"; wrappedPrivateKey = "rot-member".toByteArray(); createdAt = now
                })
                memberships.persist(Membership().apply {
                    id = "im-rot-m-owner"; tenantId = rotTenantId; userId = "im-owner"; role = "Owner"; status = "ACTIVE"; createdAt = now
                })
                memberships.persist(Membership().apply {
                    id = "im-rot-m-member"; tenantId = rotTenantId; userId = "im-member"; role = "Member"; status = "ACTIVE"; createdAt = now
                })
            }
        }
        ownerToken = "Bearer ${sessions.issue("im-owner")}"
        memberToken = "Bearer ${sessions.issue("im-member")}"
        noKeyOwnerToken = "Bearer ${sessions.issue("im-nokey-owner")}"
        removableToken = "Bearer ${sessions.issue("im-removable")}"
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

    @Test
    fun issuesTempWrapMemberInviteAndRegistersNewUserOnExistingScope() {
        // a non-key-holding Owner cannot hand the scope on
        given().header("Authorization", noKeyOwnerToken).contentType(ContentType.JSON)
            .body(memberInviteBody("Member"))
            .post("/api/tenants/$tenantId/member-invites").then().statusCode(403)
            .body("code", `is`("NOT_A_KEY_HOLDER"))

        // the key-holding Owner issues a temp-wrap member invite
        val invite = given().header("Authorization", ownerToken).contentType(ContentType.JSON)
            .body(memberInviteBody("Member"))
            .post("/api/tenants/$tenantId/member-invites").then().statusCode(200).extract()
        val inviteId = invite.path<String>("inviteId")
        val bootstrapSecret = invite.path<String>("bootstrapSecret")

        // public metadata carries the temp-wrapped grant (token stays in the fragment, never sent here)
        val meta = given().get("/api/tenant-invites/$inviteId").then().statusCode(200)
            .body("kind", `is`("MEMBER"))
            .body("role", `is`("Member"))
            .body("tempWrappedScopePrivateKey.bytes", `is`("dGVtcC13cmFwcGVk"))
            .extract()
        val scopePub = meta.path<String>("scopePublicKey.kemPublicKey")

        // a wrong scope public key is rejected (integrity)
        given().contentType(ContentType.JSON)
            .body(memberRegisterBody(bootstrapSecret, "im-newmember@example.com", "d3Jvbmctc2NvcGU"))
            .post("/api/tenant-invites/$inviteId/register").then().statusCode(400)
            .body("code", `is`("SCOPE_MISMATCH"))

        // the new user registers and gets a grant on the EXISTING scope (no new scope, role assigned)
        val session = given().contentType(ContentType.JSON)
            .body(memberRegisterBody(bootstrapSecret, "im-newmember@example.com", scopePub))
            .post("/api/tenant-invites/$inviteId/register").then().statusCode(200)
            .body("user.email", `is`("im-newmember@example.com"))
            .body("tenant.status", `is`("ACTIVE"))
            .extract().path<String>("sessionToken")

        // the new user reads the existing scope's public key back + their own wrapped grant
        given().header("Authorization", "Bearer $session")
            .get("/api/tenants/$tenantId/scope").then().statusCode(200)
            .body("scopePublicKey.kemPublicKey", `is`(scopePub))
            .body("wrappedScopePrivateKey.bytes", `is`("bWVtYmVyLWdyYW50"))

        // the tenant still has exactly one TENANT_ROOT scope (the invite did not mint a new one)
        val scopeCount = QuarkusTransaction.requiringNew().call {
            scopes.count("tenantId = ?1 and kind = ?2", tenantId, "TENANT_ROOT")
        }
        kotlin.test.assertEquals(1L, scopeCount)
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

    @Test
    fun removesMemberRevokingGrantAndSessions() {
        // the removable member's session resolves and they hold a grant up front
        given().header("Authorization", removableToken).get("/api/me").then().statusCode(200)
            .body("email", `is`("im-removable@example.com"))

        // only an Owner may remove; you cannot remove yourself; unknown members 404
        given().header("Authorization", memberToken)
            .delete("/api/tenants/$tenantId/members/im-removable").then().statusCode(403).body("code", `is`("FORBIDDEN"))
        given().header("Authorization", ownerToken)
            .delete("/api/tenants/$tenantId/members/im-owner").then().statusCode(400).body("code", `is`("CANNOT_REMOVE_SELF"))
        given().header("Authorization", ownerToken)
            .delete("/api/tenants/$tenantId/members/ghost").then().statusCode(404)

        // the Owner removes the member
        given().header("Authorization", ownerToken)
            .delete("/api/tenants/$tenantId/members/im-removable").then().statusCode(200)
            .body("userId", `is`("im-removable"))
            .body("status", `is`("REMOVED"))

        // instant revocation: their session no longer resolves, and their scope grant is gone
        given().header("Authorization", removableToken).get("/api/me").then().statusCode(401)
        val grantGone = QuarkusTransaction.requiringNew().call { grants.find(scopeId, "im-removable") == null }
        kotlin.test.assertTrue(grantGone)
    }

    @Test
    fun listsTenantMembers() {
        // a member sees the roster with identity public keys; a non-member is forbidden
        given().header("Authorization", ownerToken)
            .get("/api/tenants/$rotTenantId/members").then().statusCode(200)
            .body("members.size()", `is`(2))
            .body("members.userId", org.hamcrest.Matchers.containsInAnyOrder("im-owner", "im-member"))
            .body("members.find { it.userId == 'im-owner' }.identityPublicKey.kemPublicKey", notNullValue())

        given().header("Authorization", noKeyOwnerToken)
            .get("/api/tenants/$rotTenantId/members").then().statusCode(403)
            .body("code", `is`("FORBIDDEN"))
    }

    @Test
    fun rotatesScopeReGrantingExactlyTheActiveMembers() {
        // a non-member cannot rotate
        given().header("Authorization", noKeyOwnerToken).contentType(ContentType.JSON)
            .body(rotateBody("bmV3LXNjb3Bl", listOf("im-owner" to "b3duZXItbmV3", "im-member" to "bWVtYmVyLW5ldw")))
            .post("/api/tenants/$rotTenantId/scope/rotate").then().statusCode(403).body("code", `is`("FORBIDDEN"))

        // leaving a member out, or granting an outsider, is rejected
        given().header("Authorization", ownerToken).contentType(ContentType.JSON)
            .body(rotateBody("bmV3LXNjb3Bl", listOf("im-owner" to "b3duZXItbmV3")))
            .post("/api/tenants/$rotTenantId/scope/rotate").then().statusCode(400).body("code", `is`("INCOMPLETE_ROTATION"))
        given().header("Authorization", ownerToken).contentType(ContentType.JSON)
            .body(rotateBody("bmV3LXNjb3Bl", listOf("im-owner" to "b3duZXItbmV3", "im-member" to "bWVtYmVyLW5ldw", "im-recipient" to "b3V0c2lkZXI")))
            .post("/api/tenants/$rotTenantId/scope/rotate").then().statusCode(400).body("code", `is`("INCOMPLETE_ROTATION"))

        // rotate: new scope public key + a fresh grant for each active member
        given().header("Authorization", ownerToken).contentType(ContentType.JSON)
            .body(rotateBody("bmV3LXNjb3Bl", listOf("im-owner" to "b3duZXItbmV3", "im-member" to "bWVtYmVyLW5ldw")))
            .post("/api/tenants/$rotTenantId/scope/rotate").then().statusCode(200)
            .body("memberCount", `is`(2))

        // the owner now reads the rotated public key + their new wrapped grant
        given().header("Authorization", ownerToken)
            .get("/api/tenants/$rotTenantId/scope").then().statusCode(200)
            .body("scopePublicKey.kemPublicKey", `is`("bmV3LXNjb3Bl"))
            .body("wrappedScopePrivateKey.bytes", `is`("b3duZXItbmV3"))
    }

    private fun rotateBody(scopePub: String, grants: List<Pair<String, String>>): String {
        val grantsJson = grants.joinToString(",") { (uid, bytes) ->
            """{"userId":"$uid","wrappedScopePrivateKey":{"suiteId":"v1","bytes":"$bytes"}}"""
        }
        return """{"scopePublicKey":{"suiteId":"v1","kemPublicKey":"$scopePub"},"grants":[$grantsJson]}"""
    }

    private fun memberInviteBody(role: String) = """
        {
          "role": "$role",
          "tempPublicKey": { "suiteId": "v1", "kemPublicKey": "dGVtcC1wdWI" },
          "tempWrappedScopePrivateKey": { "suiteId": "v1", "bytes": "dGVtcC13cmFwcGVk" }
        }
    """.trimIndent()

    private fun memberRegisterBody(secret: String, email: String, scopePub: String) = """
        {
          "bootstrapSecret": "$secret",
          "email": "$email",
          "displayName": "New Member",
          "identityPublicKey": { "suiteId": "v1", "kemPublicKey": "a2VtcHVi", "signPublicKey": "c2lnbg" },
          "wrappedIdentityPrivateKey": { "suiteId": "v1", "bytes": "d3JhcHBlZA" },
          "masterKeyWraps": [ { "unlockMethod": "PASSWORD", "suiteId": "v1", "wrappedKey": "bWs", "kdfParams": "c2FsdA" } ],
          "authVerifier": { "kind": "ARGON2_PROOF", "suiteId": "v1", "verifier": "dmVyaWZpZXI" },
          "personalScopePublicKey": { "suiteId": "v1", "kemPublicKey": "cGVyc29uYWw" },
          "wrappedPersonalScopePrivateKey": { "suiteId": "v1", "bytes": "cGVyc29uYWwtZ3JhbnQ" },
          "scopePublicKey": { "suiteId": "v1", "kemPublicKey": "$scopePub" },
          "wrappedScopePrivateKey": { "suiteId": "v1", "bytes": "bWVtYmVyLWdyYW50" }
        }
    """.trimIndent()

    private fun grantBody(recipientUserId: String, role: String, bytes: String) = """
        {
          "recipientUserId": "$recipientUserId",
          "role": "$role",
          "wrappedScopePrivateKey": { "suiteId": "v1", "bytes": "$bytes" }
        }
    """.trimIndent()
}
