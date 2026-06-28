package com.veyq.app.features.spaces.scope

import com.veyq.app.features.account.login.SessionService
import com.veyq.app.features.admin.tenant.MembershipEntity
import com.veyq.app.features.admin.tenant.TenantEntity
import com.veyq.app.shared.identity.VeyqUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.hasItems
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Slice tests for a member's scopes (uc.spaces.personal-scope, cr-0026): a member births their PERSONAL scope
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

    // --- helpers ---

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
                displayName = "Scopes Test"
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
