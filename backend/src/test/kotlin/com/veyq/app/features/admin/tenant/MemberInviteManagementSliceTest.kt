package com.veyq.app.features.admin.tenant

import com.veyq.app.features.account.login.SessionService
import com.veyq.app.features.spaces.scope.ScopeEntity
import com.veyq.app.shared.crypto.Tokens
import com.veyq.app.shared.identity.VeyqUser
import com.veyq.app.shared.membership.MembershipEntity
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
 * link), and **revokes** one (soft-terminal so its secret replays as no-longer-valid). All membership-gated
 * first (a non-member gets 404, indistinguishable from an unknown tenant — ADR 0042), then owner-gated (403).
 * Distinct domains/emails **and seed secrets** so the JVM-lived H2 mem DB can't bleed state: `token_hash` carries
 * a **global** unique index (`UX_TENANT_INVITE_TOKEN_HASH`), so a secret reused across classes collides at commit
 * (green in isolation, `RollbackException` in the full suite) — treat a seeded secret as a suite-wide fixture.
 */
@QuarkusTest
class MemberInviteManagementSliceTest {

    @Inject lateinit var store: TenantStore
    @Inject lateinit var sessions: SessionService

    private fun bearer(userId: String) = "Bearer ${sessions.issue(userId).accessToken}"

    @Test
    fun ownerListsPendingMemberInvitesOutsiderIs404() {
        val (tenantId, owner) = seedActiveTenant("list-mi.example.com", "owner@list-mi.example.com")
        seedMemberInvite(tenantId, "a@list-mi.example.com", "secret-a")
        seedMemberInvite(tenantId, "b@list-mi.example.com", "secret-b")

        given().header("Authorization", bearer(owner))
            .`when`().get("/api/tenants/$tenantId/member-invites")
            .then().statusCode(200).body("size()", equalTo(2))
            .body("find { it.email == 'a@list-mi.example.com' }.inviteStatus", equalTo("ACTIVE"))
            .body("find { it.email == 'a@list-mi.example.com' }.inviteId", notNullValue())
            .body("find { it.email == 'a@list-mi.example.com' }.inviteExpiresAt", notNullValue())

        // An outsider is not a member of the tenant, so they get the same answer as for a tenant that does not
        // exist — not a 403 that would confirm it (membership is the first guard, ADR 0042 / cr-0162).
        val outsider = seedUser("outsider@list-mi.example.com")
        given().header("Authorization", bearer(outsider))
            .`when`().get("/api/tenants/$tenantId/member-invites")
            .then().statusCode(404).body("code", equalTo("tenant_not_found"))
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
    fun reissueReportsTheDeliveryResult() {
        // cr-email-invite-send-hardening: the reissue response carries the first-class delivery result. No SMTP
        // configured ⇒ FALLBACK_COPYLINK (the copy-link is the channel), threaded end-to-end from the mailer.
        val (tenantId, owner) = seedActiveTenant("reissue-del.example.com", "owner@reissue-del.example.com")
        seedMemberInvite(tenantId, "m@reissue-del.example.com", "reissue-del-seed-secret") // token_hash is globally unique
        val inviteId = firstPendingInviteId(tenantId)
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .`when`().post("/api/tenants/$tenantId/member-invites/$inviteId/reissue")
            .then().statusCode(200).body("delivery", equalTo("FALLBACK_COPYLINK"))
    }

    @Test
    fun reissuingOneRecipientPastTheLimitIs429WithRetryAfter() {
        // Re-issue is the sharpest bombing vector (repeated fresh links to the same address). The default
        // per-recipient cap is 3, so the 4th re-issue to the same email is refused with 429 + Retry-After — before
        // any fresh mint. Unique tenant + email keeps the shared in-memory limiter isolated from other tests.
        val (tenantId, owner) = seedActiveTenant("reissue-flood.example.com", "owner@reissue-flood.example.com")
        seedMemberInvite(tenantId, "victim@reissue-flood.example.com", "reissue-flood-seed-secret") // token_hash is globally unique
        repeat(3) {
            val id = firstPendingInviteId(tenantId)
            given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
                .`when`().post("/api/tenants/$tenantId/member-invites/$id/reissue")
                .then().statusCode(200)
        }
        val id = firstPendingInviteId(tenantId)
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .`when`().post("/api/tenants/$tenantId/member-invites/$id/reissue")
            .then().statusCode(429).header("Retry-After", notNullValue()).body("code", equalTo("rate_limited"))
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
