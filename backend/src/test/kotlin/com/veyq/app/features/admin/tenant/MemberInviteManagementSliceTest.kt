package com.veyq.app.features.admin.tenant

import com.veyq.app.features.account.login.SessionService
import com.veyq.app.features.spaces.scope.ScopeEntity
import com.veyq.app.shared.crypto.Tokens
import com.veyq.app.shared.identity.VeyqUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.not
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Slice tests for member-invite management (uc.spaces.manage-member-invites, cr-0082): the tenant **owner**
 * lists their pending MEMBER invites, **re-issues** one (superseding the old secret + minting a fresh copyable
 * link), and **revokes** one (soft-terminal so its secret replays as no-longer-valid). All owner-gated; a
 * non-owner is uniformly 403. Distinct domains/emails so the JVM-lived H2 mem DB can't bleed state.
 */
@QuarkusTest
class MemberInviteManagementSliceTest {

    @Inject lateinit var store: TenantStore
    @Inject lateinit var sessions: SessionService

    private fun bearer(userId: String) = "Bearer ${sessions.issue(userId).accessToken}"

    @Test
    fun ownerListsPendingMemberInvitesNonOwnerIsForbidden() {
        val (tenantId, owner) = seedActiveTenant("list-mi.example.com", "owner@list-mi.example.com")
        seedMemberInvite(tenantId, "a@list-mi.example.com", "secret-a")
        seedMemberInvite(tenantId, "b@list-mi.example.com", "secret-b")

        given().header("Authorization", bearer(owner))
            .`when`().get("/api/tenants/$tenantId/member-invites")
            .then().statusCode(200).body("size()", equalTo(2))
            .body("find { it.email == 'a@list-mi.example.com' }.inviteStatus", equalTo("ACTIVE"))
            .body("find { it.email == 'a@list-mi.example.com' }.inviteId", notNullValue())
            .body("find { it.email == 'a@list-mi.example.com' }.inviteExpiresAt", notNullValue())

        val outsider = seedUser("outsider@list-mi.example.com")
        given().header("Authorization", bearer(outsider))
            .`when`().get("/api/tenants/$tenantId/member-invites")
            .then().statusCode(403).body("code", equalTo("not_tenant_owner"))
    }

    @Test
    fun reissueSupersedesOldSecretAndMintsAUsableFreshLink() {
        val (tenantId, owner) = seedActiveTenant("reissue.example.com", "owner@reissue.example.com")
        val oldSecret = "old-secret-1"
        seedMemberInvite(tenantId, "member@reissue.example.com", oldSecret)
        val inviteId = firstPendingInviteId(tenantId)

        val freshSecret = given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .`when`().post("/api/tenants/$tenantId/member-invites/$inviteId/reissue")
            .then().statusCode(200)
            .body("email", equalTo("member@reissue.example.com"))
            .body("inviteStatus", equalTo("ACTIVE"))
            .body("bootstrapSecret", notNullValue())
            .body("bootstrapSecret", not(equalTo(oldSecret)))
            .extract().path<String>("bootstrapSecret")

        // The OLD secret is dead — a register replay is rejected as no-longer-valid.
        given().contentType(ContentType.JSON).body(memberRegisterBody(oldSecret))
            .`when`().post("/api/tenants/accept/register")
            .then().statusCode(410).body("code", equalTo("invite_no_longer_valid"))

        // The FRESH secret actually joins the tenant grant-less.
        given().contentType(ContentType.JSON).body(memberRegisterBody(freshSecret))
            .`when`().post("/api/tenants/accept/register")
            .then().statusCode(201).body("status", equalTo("ACTIVE"))
    }

    @Test
    fun revokeMakesTheSecretNoLongerAcceptableAndDropsItFromTheList() {
        val (tenantId, owner) = seedActiveTenant("revoke.example.com", "owner@revoke.example.com")
        val secret = "revoke-secret-1"
        seedMemberInvite(tenantId, "member@revoke.example.com", secret)
        val inviteId = firstPendingInviteId(tenantId)

        given().header("Authorization", bearer(owner))
            .`when`().delete("/api/tenants/$tenantId/member-invites/$inviteId")
            .then().statusCode(204)

        // Gone from the owner's pending list.
        given().header("Authorization", bearer(owner))
            .`when`().get("/api/tenants/$tenantId/member-invites")
            .then().statusCode(200).body("size()", equalTo(0))

        // A replay of the revoked secret is rejected.
        given().contentType(ContentType.JSON).body(memberRegisterBody(secret))
            .`when`().post("/api/tenants/accept/register")
            .then().statusCode(410).body("code", equalTo("invite_no_longer_valid"))
    }

    @Test
    fun reissueAndRevokeOfAnUnknownInviteAre404() {
        val (tenantId, owner) = seedActiveTenant("unknown-mi.example.com", "owner@unknown-mi.example.com")
        val ghost = UUID.randomUUID().toString()

        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .`when`().post("/api/tenants/$tenantId/member-invites/$ghost/reissue")
            .then().statusCode(404).body("code", equalTo("invite_not_found"))

        given().header("Authorization", bearer(owner))
            .`when`().delete("/api/tenants/$tenantId/member-invites/$ghost")
            .then().statusCode(404).body("code", equalTo("invite_not_found"))
    }

    @Test
    fun manageOnAnUnknownTenantIs404NotFound() {
        val owner = seedUser("stray@nowhere-mi.example.com")
        val ghostTenant = UUID.randomUUID().toString()
        given().header("Authorization", bearer(owner))
            .`when`().get("/api/tenants/$ghostTenant/member-invites")
            .then().statusCode(404).body("code", equalTo("tenant_not_found"))
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

    private fun firstPendingInviteId(tenantId: String): String =
        QuarkusTransaction.requiringNew().call { store.listPendingMemberInvites(tenantId).first().inviteId }

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
