package com.veyq.app.features.admin.tenant

import com.veyq.app.features.account.login.SessionService
import com.veyq.app.features.spaces.scope.ScopeEntity
import com.veyq.app.features.tasks.CapabilityTags
import com.veyq.app.shared.crypto.Tokens
import com.veyq.app.shared.identity.VeyqUser
import com.veyq.app.shared.membership.MembershipEntity
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Slice tests for member-add onboarding (uc.spaces.add-member, cr-0077): the tenant **owner** mints a MEMBER
 * invite (owner-gated admission), the invitee **cold-births** their account and joins the ACTIVE tenant as a
 * **grant-less** member, which cr-0076 picks up as a `grant-delivery` job; and the owner reads the member
 * directory (pubkeys + grant status). Distinct domains/emails so the JVM-lived H2 mem DB can't bleed state.
 */
@QuarkusTest
class MemberAddSliceTest {

    @Inject lateinit var store: TenantStore
    @Inject lateinit var sessions: SessionService

    private fun bearer(userId: String) = "Bearer ${sessions.issue(userId).accessToken}"

    @Test
    fun ownerMintsMemberInviteOutsiderIs404() {
        val (tenantId, owner) = seedActiveTenant("mint.example.com", "owner@mint.example.com")
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body("""{"email":"newbie@mint.example.com"}""")
            .`when`().post("/api/tenants/$tenantId/member-invites")
            .then().statusCode(201).body("email", equalTo("newbie@mint.example.com"))
            .body("inviteStatus", equalTo("ACTIVE")).body("inviteExpiresAt", notNullValue())

        // An outsider (not a member of the tenant at all) cannot admit members — and gets the same answer as for
        // a tenant that does not exist (membership is the first guard, ADR 0042 / cr-0162).
        val outsider = seedUser("outsider@mint.example.com")
        given().header("Authorization", bearer(outsider)).contentType(ContentType.JSON)
            .body("""{"email":"x@mint.example.com"}""")
            .`when`().post("/api/tenants/$tenantId/member-invites")
            .then().statusCode(404).body("code", equalTo("tenant_not_found"))
    }

    @Test
    fun memberRegisterJoinsGrantLessAndOwnerClaimOriginatesDelivery() {
        val (tenantId, owner) = seedActiveTenant("join.example.com", "owner@join.example.com")
        val secret = "member-secret-1"
        seedMemberInvite(tenantId, "member@join.example.com", secret)

        // The invitee cold-births an account and joins — NO grant in the body (a member is grant-less).
        val newUserId = given().contentType(ContentType.JSON).body(memberRegisterBody(secret))
            .`when`().post("/api/tenants/accept/register")
            .then().statusCode(201).body("status", equalTo("ACTIVE")).body("userId", notNullValue())
            .extract().path<String>("userId")

        // The membership exists on the TENANT scope, role MEMBER, with NO grant yet (the hollow state).
        assertEquals("MEMBER", membershipRole(newUserId, tenantId))
        assertNull(grantOf(newUserId, tenantId), "a freshly onboarded member is grant-less")

        // cr-0076 integration: the owner (a key-holder) claims grant-delivery → reconcile-on-claim originates
        // the job for the pending member and hands it back, resolving the target's pubkey.
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body("""{"capabilityTags":["${CapabilityTags.GRANT_DELIVERY}"],"scopeId":"$tenantId"}""")
            .`when`().post("/api/tasks/claim")
            .then().statusCode(200)
            .body("capabilityTag", equalTo(CapabilityTags.GRANT_DELIVERY))
            .body("scopeId", equalTo(tenantId))
            .body("grantDelivery.targetUserId", equalTo(newUserId))
            .body("grantDelivery.identityPublicKey", notNullValue())
    }

    @Test
    fun mintedMemberInviteReturnsAUsableBootstrapSecretForCopyLinkHandoff() {
        // cr-0080: the mint response hands the owner the raw secret so they can copy the invite link (no mailer
        // needed). Prove it is the *real* token — registering with it actually joins the tenant grant-less.
        val (tenantId, owner) = seedActiveTenant("copylink.example.com", "owner@copylink.example.com")
        val secret = given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body("""{"email":"newbie@copylink.example.com"}""")
            .`when`().post("/api/tenants/$tenantId/member-invites")
            .then().statusCode(201).body("bootstrapSecret", notNullValue())
            .extract().path<String>("bootstrapSecret")

        val newUserId = given().contentType(ContentType.JSON).body(memberRegisterBody(secret))
            .`when`().post("/api/tenants/accept/register")
            .then().statusCode(201).body("status", equalTo("ACTIVE"))
            .extract().path<String>("userId")
        assertEquals("MEMBER", membershipRole(newUserId, tenantId))
        assertNull(grantOf(newUserId, tenantId))
    }

    @Test
    fun existingAccountAcceptsMemberInviteAndJoinsGrantLess() {
        // The second onboarding flow (uc.spaces.add-member): the invited email ALREADY has an account, so it
        // signs in and accepts (POST /api/tenants/accept) rather than cold-registering. A member joins grant-
        // less here too — no grant in the body, the key delivered later by a key-holder (cr-0076).
        val (tenantId, _) = seedActiveTenant("existing.example.com", "owner@existing.example.com")
        val secret = "existing-member-secret"
        seedMemberInvite(tenantId, "existing@existing.example.com", secret)
        val existing = seedUser("existing@existing.example.com") // already registered before the invite

        given().header("Authorization", bearer(existing)).contentType(ContentType.JSON)
            .body("""{"bootstrapSecret":"$secret","username":"existingmbr"}""") // no grant — an existing-account member joins grant-less
            .`when`().post("/api/tenants/accept")
            .then().statusCode(200).body("status", equalTo("ACTIVE"))

        assertEquals("MEMBER", membershipRole(existing, tenantId))
        assertNull(grantOf(existing, tenantId), "an existing-account member joins grant-less too")
    }

    @Test
    fun ownerInviteRegisterStillRequiresAGrant() {
        // The OWNER path is unchanged: registering to an OWNER invite without a grant is a 400.
        val secret = "owner-secret-1"
        QuarkusTransaction.requiringNew().run {
            store.createPendingTenant("Fam", "owner-need-grant.example.com", "o@owner-need-grant.example.com",
                Tokens.hash(secret), Instant.now().plus(7, ChronoUnit.DAYS))
        }
        given().contentType(ContentType.JSON).body(memberRegisterBody(secret)) // body omits grant
            .`when`().post("/api/tenants/accept/register")
            .then().statusCode(400).body("code", equalTo("invalid_request"))
    }

    @Test
    fun memberDirectoryListsMembersWithGrantStatus() {
        val (tenantId, owner) = seedActiveTenant("dir.example.com", "owner@dir.example.com")
        val pending = seedUser("pending@dir.example.com", pubkey = "cGVuZGluZy1wdWJrZXk=")
        QuarkusTransaction.requiringNew().run {
            MembershipEntity().apply {
                id = UUID.randomUUID().toString(); userId = pending; this.tenantId = tenantId
                scopeId = tenantId; role = MembershipEntity.MEMBER; wrappedGrant = null; createdAt = Instant.now()
            }.persist()
        }

        given().header("Authorization", bearer(owner))
            .`when`().get("/api/scopes/$tenantId/members")
            .then().statusCode(200).body("size()", equalTo(2))
            .body("find { it.userId == '$owner' }.hasGrant", equalTo(true))
            .body("find { it.userId == '$owner' }.role", equalTo("OWNER"))
            .body("find { it.userId == '$pending' }.hasGrant", equalTo(false))
            .body("find { it.userId == '$pending' }.identityPublicKey", equalTo("cGVuZGluZy1wdWJrZXk="))

        // A non-member cannot read the directory, and gets the same answer as for a scope that does not exist
        // (membership is the first guard, ADR 0042 / cr-0162 — no scope enumeration).
        val outsider = seedUser("nope@dir.example.com")
        given().header("Authorization", bearer(outsider))
            .`when`().get("/api/scopes/$tenantId/members")
            .then().statusCode(404).body("code", equalTo("scope_not_found"))
    }

    // --- helpers ---

    /** An ACTIVE tenant + its TENANT scope + a granted OWNER. Returns (tenantId, ownerUserId). */
    private fun seedActiveTenant(domain: String, ownerEmail: String): Pair<String, String> {
        val tenantId = UUID.randomUUID().toString()
        val ownerId = seedUser(ownerEmail)
        QuarkusTransaction.requiringNew().run {
            TenantEntity().apply {
                id = tenantId; name = "Fam"; this.domain = domain; this.ownerEmail = ownerEmail
                status = TenantEntity.ACTIVE; createdAt = Instant.now()
            }.persist()
            ScopeEntity().apply {
                id = tenantId; this.tenantId = tenantId; kind = ScopeEntity.TENANT; createdAt = Instant.now()
            }.persist()
            MembershipEntity().apply {
                id = UUID.randomUUID().toString(); userId = ownerId; this.tenantId = tenantId
                scopeId = tenantId; role = MembershipEntity.OWNER; wrappedGrant = "b3duZXItZ3JhbnQ="; createdAt = Instant.now()
            }.persist()
        }
        return tenantId to ownerId
    }

    private fun seedMemberInvite(tenantId: String, email: String, secret: String) =
        QuarkusTransaction.requiringNew().run {
            store.mintMemberInvite(tenantId, email, Tokens.hash(secret), Instant.now().plus(7, ChronoUnit.DAYS))
        }

    private fun seedUser(email: String, pubkey: String = "aWRlbnRpdHktcHVibGljLWtleQ=="): String {
        val userId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            VeyqUser().apply {
                id = userId; this.email = email; serverRole = "MEMBER"
                suiteId = "v1"; identityPublicKey = pubkey; wrappedIdentityPrivateKey = "aWRlbnRpdHktcHJpdg=="
                authPublicKey = "YXV0aC1wdWJsaWMta2V5"; createdAt = Instant.now()
            }.persist()
        }
        return userId
    }

    private fun membershipRole(userId: String, tenantId: String): String? =
        QuarkusTransaction.requiringNew().call {
            MembershipEntity.find("userId = ?1 and tenantId = ?2", userId, tenantId).firstResult()?.role
        }

    private fun grantOf(userId: String, tenantId: String): String? =
        QuarkusTransaction.requiringNew().call {
            MembershipEntity.find("userId = ?1 and tenantId = ?2", userId, tenantId).firstResult()?.wrappedGrant
        }

    /** A cold-birth register body with NO grant (a MEMBER joins grant-less). */
    private fun memberRegisterBody(secret: String) = """
        {"bootstrapSecret":"$secret","username":"newmember",
         "account":{"suiteId":"v1",
           "identityPublicKey":"bWVtYmVyLXB1YmxpYy1rZXk=",
           "wrappedIdentityPrivateKey":"d3JhcHBlZC1pZGVudGl0eS1wcml2YXRlLWtleQ==",
           "authPublicKey":"ZWQyNTUxOS1hdXRoLXB1Yg==",
           "masterKeyWraps":[{"method":"PASSWORD","wrappedKey":"d3JhcHBlZC1tYXN0ZXIta2V5","kdfParams":"a2RmLXBhcmFtcw=="}],
           "recoveryMasterKeyTag":"cmVjb3ZlcnktdGFn"}}
    """.trimIndent()
}
