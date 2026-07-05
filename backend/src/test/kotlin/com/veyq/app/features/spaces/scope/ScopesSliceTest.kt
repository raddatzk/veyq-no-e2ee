package com.veyq.app.features.spaces.scope

import com.veyq.app.features.account.login.SessionService
import com.veyq.app.features.admin.tenant.MembershipEntity
import com.veyq.app.features.admin.tenant.TenantEntity
import com.veyq.app.shared.identity.TenantProfileEntity
import com.veyq.app.shared.identity.VeyqUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.hasItems
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Slice tests for a member's scopes (en.spaces.personal-scope, cr-0026): a member births their PERSONAL scope
 * (client-supplied ciphertext grant) and lists their scopes; one personal scope per (member, tenant); the
 * active tenant is resolved from the Host (X-Forwarded-Host); operator-blind (the stored grant is exactly the
 * ciphertext the client sent). Seeds use UUID-unique domains/emails so the JVM-lived H2 mem DB (shared across
 * test classes) can't bleed state.
 */
@QuarkusTest
class ScopesSliceTest {

    @Inject
    lateinit var sessions: SessionService

    private val personalGrant = "cGVyc29uYWwtc2NvcGUtZ3JhbnQ=" // "personal-scope-grant"
    private val groupGrant = "Z3JvdXAtc2NvcGUtZ3JhbnQ=" // "group-scope-grant"
    private val groupName = "c2VhbGVkLWdyb3VwLW5hbWU=" // "sealed-group-name"

    @Test
    fun createPersonalScopeThenListReturnsBoth() {
        val (userId, domain) = seedMember()
        val scopeId = given().header("Authorization", "Bearer ${sessions.issue(userId).accessToken}")
            .header("X-Forwarded-Host", domain).contentType(ContentType.JSON)
            .body("""{"wrappedGrant":"$personalGrant"}""")
            .`when`().post("/api/scopes")
            .then().statusCode(201)
            .body("kind", equalTo("PERSONAL"))
            .body("scopeId", notNullValue())
            .extract().path<String>("scopeId")

        // The list now shows the TENANT root + the new PERSONAL scope; the personal grant is the verbatim
        // ciphertext the client sent (operator-blind — the server stored it without interpreting it).
        given().header("Authorization", "Bearer ${sessions.issue(userId).accessToken}")
            .header("X-Forwarded-Host", domain)
            .`when`().get("/api/scopes")
            .then().statusCode(200)
            .body("kind", hasItems("TENANT", "PERSONAL"))
            .body("find { it.id == '$scopeId' }.wrappedGrant", equalTo(personalGrant))
    }

    @Test
    fun secondPersonalScopeIs409() {
        val (userId, domain) = seedMember()
        given().header("Authorization", "Bearer ${sessions.issue(userId).accessToken}")
            .header("X-Forwarded-Host", domain).contentType(ContentType.JSON)
            .body("""{"wrappedGrant":"$personalGrant"}""")
            .`when`().post("/api/scopes").then().statusCode(201)

        given().header("Authorization", "Bearer ${sessions.issue(userId).accessToken}")
            .header("X-Forwarded-Host", domain).contentType(ContentType.JSON)
            .body("""{"wrappedGrant":"$personalGrant"}""")
            .`when`().post("/api/scopes")
            .then().statusCode(409).body("code", equalTo("personal_scope_exists"))
    }

    @Test
    fun createGroupThenListShowsItWithSealedNameAndSelfGrant() {
        val (userId, domain) = seedMember()
        val scopeId = given().header("Authorization", "Bearer ${sessions.issue(userId).accessToken}")
            .header("X-Forwarded-Host", domain).contentType(ContentType.JSON)
            .body("""{"encryptedName":"$groupName","wrappedGrant":"$groupGrant"}""")
            .`when`().post("/api/scopes/groups")
            .then().statusCode(201)
            .body("kind", equalTo("GROUP"))
            .body("scopeId", notNullValue())
            .extract().path<String>("scopeId")

        // The list now carries the GROUP scope with the verbatim ciphertext name + the caller's self-grant —
        // both are exactly what the client sent (operator-blind: the server stored, never interpreted, them).
        given().header("Authorization", "Bearer ${sessions.issue(userId).accessToken}")
            .header("X-Forwarded-Host", domain)
            .`when`().get("/api/scopes")
            .then().statusCode(200)
            .body("kind", hasItems("TENANT", "GROUP"))
            .body("find { it.id == '$scopeId' }.encryptedName", equalTo(groupName))
            .body("find { it.id == '$scopeId' }.wrappedGrant", equalTo(groupGrant))
            // TENANT/PERSONAL scopes carry no sealed name.
            .body("find { it.kind == 'TENANT' }.encryptedName", equalTo(null))
    }

    @Test
    fun createGroupNonMemberIs404() {
        // A tenant exists, but this caller holds no membership in it → cannot create a group there.
        val (_, domain) = seedMember()
        val outsider = seedUser()
        given().header("Authorization", "Bearer ${sessions.issue(outsider).accessToken}")
            .header("X-Forwarded-Host", domain).contentType(ContentType.JSON)
            .body("""{"encryptedName":"$groupName","wrappedGrant":"$groupGrant"}""")
            .`when`().post("/api/scopes/groups")
            .then().statusCode(404).body("code", equalTo("tenant_not_found"))
    }

    @Test
    fun addGroupMemberByUsernameRecordsGrantlessMember() {
        val (owner, domain) = seedMember()
        val groupId = createGroupAs(owner, domain)
        seedTenantMember(tenantIdOf(domain), "partner")

        // The owner admits an existing tenant member by username → a grant-less MEMBER row (awaiting delivery);
        // the response never exposes email, and no key/name was touched (operator-blind admission).
        given().header("Authorization", "Bearer ${token(owner)}")
            .header("X-Forwarded-Host", domain).contentType(ContentType.JSON)
            .body("""{"username":"partner"}""")
            .`when`().post("/api/scopes/$groupId/members")
            .then().statusCode(201)
            .body("username", equalTo("partner"))
            .body("role", equalTo("MEMBER"))
            .body("hasGrant", equalTo(false))
            .body("email", nullValue())

        // The group directory now lists the pending member — grant-less, ready for grant-delivery to wrap K_SC.
        given().header("Authorization", "Bearer ${token(owner)}")
            .`when`().get("/api/scopes/$groupId/members")
            .then().statusCode(200)
            .body("find { it.username == 'partner' }.hasGrant", equalTo(false))
    }

    @Test
    fun addGroupMemberNonOwnerIs403() {
        // A fellow tenant member who is NOT the group's owner cannot admit anyone (admission is owner-gated).
        val (owner, domain) = seedMember()
        val groupId = createGroupAs(owner, domain)
        val nonOwner = seedTenantMember(tenantIdOf(domain), "partner")
        seedTenantMember(tenantIdOf(domain), "third")
        given().header("Authorization", "Bearer ${token(nonOwner)}")
            .header("X-Forwarded-Host", domain).contentType(ContentType.JSON)
            .body("""{"username":"third"}""")
            .`when`().post("/api/scopes/$groupId/members")
            .then().statusCode(403).body("code", equalTo("not_scope_owner"))
    }

    @Test
    fun addGroupMemberUnknownUsernameIs404() {
        val (owner, domain) = seedMember()
        val groupId = createGroupAs(owner, domain)
        given().header("Authorization", "Bearer ${token(owner)}")
            .header("X-Forwarded-Host", domain).contentType(ContentType.JSON)
            .body("""{"username":"nobody"}""")
            .`when`().post("/api/scopes/$groupId/members")
            .then().statusCode(404).body("code", equalTo("member_not_found"))
    }

    @Test
    fun addGroupMemberAlreadyMemberIs409() {
        val (owner, domain) = seedMember()
        val groupId = createGroupAs(owner, domain)
        seedTenantMember(tenantIdOf(domain), "partner")
        val add = {
            given().header("Authorization", "Bearer ${token(owner)}")
                .header("X-Forwarded-Host", domain).contentType(ContentType.JSON)
                .body("""{"username":"partner"}""").`when`().post("/api/scopes/$groupId/members")
        }
        add().then().statusCode(201)
        add().then().statusCode(409).body("code", equalTo("already_member"))
    }

    @Test
    fun unknownHostIs404() {
        val (userId, _) = seedMember()
        given().header("Authorization", "Bearer ${sessions.issue(userId).accessToken}")
            .header("X-Forwarded-Host", "nobody-${UUID.randomUUID()}.example.com").contentType(ContentType.JSON)
            .body("""{"wrappedGrant":"$personalGrant"}""")
            .`when`().post("/api/scopes")
            .then().statusCode(404).body("code", equalTo("tenant_not_found"))
    }

    @Test
    fun nonMemberIs404() {
        // A tenant exists, but this caller holds no membership in it → cannot birth a personal scope there.
        val (_, domain) = seedMember()
        val outsider = seedUser()
        given().header("Authorization", "Bearer ${sessions.issue(outsider).accessToken}")
            .header("X-Forwarded-Host", domain).contentType(ContentType.JSON)
            .body("""{"wrappedGrant":"$personalGrant"}""")
            .`when`().post("/api/scopes")
            .then().statusCode(404).body("code", equalTo("tenant_not_found"))
    }

    @Test
    fun unauthenticatedIs401() {
        given().header("X-Forwarded-Host", "x-${UUID.randomUUID()}.example.com").contentType(ContentType.JSON)
            .body("""{"wrappedGrant":"$personalGrant"}""")
            .`when`().post("/api/scopes")
            .then().statusCode(401).body("code", equalTo("authentication_failed"))
    }

    @Test
    fun listMyTenantsReturnsTheCallersTenants() {
        // Host-independent: lists every tenant the caller belongs to (name + domain + role), for the switcher.
        val (userId, domain) = seedMember()
        given().header("Authorization", "Bearer ${sessions.issue(userId).accessToken}")
            .`when`().get("/api/scopes/my-tenants")
            .then().statusCode(200)
            .body("size()", equalTo(1))
            .body("[0].name", equalTo("Fam"))
            .body("[0].domain", equalTo(domain))
            .body("[0].role", equalTo("OWNER"))

        // an outsider with no membership sees an empty list — only your own tenants (operator-blind).
        val outsider = seedUser()
        given().header("Authorization", "Bearer ${sessions.issue(outsider).accessToken}")
            .`when`().get("/api/scopes/my-tenants")
            .then().statusCode(200).body("size()", equalTo(0))
    }

    // --- helpers ---

    private fun token(userId: String) = sessions.issue(userId).accessToken

    /** The tenantId encoded in a domain seeded by [seedMember] (`t-<tenantId>.example.com`). */
    private fun tenantIdOf(domain: String) = domain.removePrefix("t-").removeSuffix(".example.com")

    /** Create a GROUP scope as the caller (the seeded name/grant are opaque here) → the new scopeId. */
    private fun createGroupAs(userId: String, domain: String): String =
        given().header("Authorization", "Bearer ${token(userId)}")
            .header("X-Forwarded-Host", domain).contentType(ContentType.JSON)
            .body("""{"encryptedName":"$groupName","wrappedGrant":"$groupGrant"}""")
            .`when`().post("/api/scopes/groups")
            .then().statusCode(201).extract().path("scopeId")

    /** Seed a second tenant member with a per-tenant username profile (the add-by-username target). */
    private fun seedTenantMember(tenantId: String, username: String): String {
        val userId = seedUser()
        QuarkusTransaction.requiringNew().run {
            MembershipEntity().apply {
                id = UUID.randomUUID().toString()
                this.userId = userId
                this.tenantId = tenantId
                scopeId = tenantId
                role = MembershipEntity.MEMBER
                wrappedGrant = "bWVtYmVyLWdyYW50" // "member-grant"
                createdAt = Instant.now()
            }.persist()
            TenantProfileEntity().apply {
                id = UUID.randomUUID().toString()
                this.userId = userId
                this.tenantId = tenantId
                this.username = username
                createdAt = Instant.now()
            }.persist()
        }
        return userId
    }

    /** Seed a user + an ACTIVE tenant (unique domain) + the user's TENANT membership (post-activation state).
     *  Returns the userId and the tenant's domain (use it as the X-Forwarded-Host). */
    private fun seedMember(): Pair<String, String> {
        val userId = seedUser()
        val tenantId = UUID.randomUUID().toString()
        val domain = "t-$tenantId.example.com"
        QuarkusTransaction.requiringNew().run {
            TenantEntity().apply {
                id = tenantId
                name = "Fam"
                this.domain = domain
                ownerEmail = "owner-$tenantId@example.com"
                status = TenantEntity.ACTIVE
                createdAt = Instant.now()
            }.persist()
            ScopeEntity().apply {
                id = tenantId
                this.tenantId = tenantId
                kind = ScopeEntity.TENANT
                createdAt = Instant.now()
            }.persist()
            MembershipEntity().apply {
                id = UUID.randomUUID().toString()
                this.userId = userId
                this.tenantId = tenantId
                scopeId = tenantId
                role = MembershipEntity.OWNER
                wrappedGrant = "dGVuYW50LWdyYW50" // "tenant-grant"
                createdAt = Instant.now()
            }.persist()
        }
        return userId to domain
    }

    private fun seedUser(): String {
        val userId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            VeyqUser().apply {
                id = userId
                email = "u-$userId@example.com"
                serverRole = "MEMBER"
                suiteId = "v1"
                identityPublicKey = "aWRlbnRpdHktcHVibGljLWtleQ=="
                wrappedIdentityPrivateKey = "aWRlbnRpdHktcHJpdg=="
                authPublicKey = "YXV0aC1wdWJsaWMta2V5"
                createdAt = Instant.now()
            }.persist()
        }
        return userId
    }
}
