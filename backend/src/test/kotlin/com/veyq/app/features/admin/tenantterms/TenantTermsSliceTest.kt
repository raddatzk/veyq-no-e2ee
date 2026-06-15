package com.veyq.app.features.admin.tenantterms

import com.veyq.app.shared.content.Node
import com.veyq.app.shared.content.NodeRepository
import com.veyq.app.shared.content.ScopeContentKey
import com.veyq.app.shared.content.ScopeContentKeyRepository
import com.veyq.app.shared.identity.ServerRole
import com.veyq.app.shared.identity.User
import com.veyq.app.shared.identity.UserRepository
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
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test

/** uc.share.terms — a Tenant Owner sets ToS; guests see it as a TENANT notice on any share of the group. */
@QuarkusTest
class TenantTermsSliceTest {

    @Inject lateinit var users: UserRepository
    @Inject lateinit var tenants: TenantRepository
    @Inject lateinit var memberships: MembershipRepository
    @Inject lateinit var scopes: ScopeRepository
    @Inject lateinit var grants: ScopeGrantRepository
    @Inject lateinit var contentKeys: ScopeContentKeyRepository
    @Inject lateinit var nodes: NodeRepository
    @Inject lateinit var sessions: SessionService

    private lateinit var ownerToken: String
    private lateinit var memberToken: String
    private val tenantId = "tt-tenant"
    private val scopeId = "tt-scope"

    @BeforeTest
    fun seed() {
        QuarkusTransaction.requiringNew().run {
            val now = Instant.now()
            if (users.findByEmail("tt-owner@example.com") != null) return@run
            users.persist(User().apply { id = "tt-owner"; email = "tt-owner@example.com"; displayName = "O"; serverRole = ServerRole.ServerMember; createdAt = now })
            users.persist(User().apply { id = "tt-member"; email = "tt-member@example.com"; displayName = "M"; serverRole = ServerRole.ServerMember; createdAt = now })
            tenants.persist(Tenant().apply { id = tenantId; name = "tt-acme"; displayName = "Acme"; status = "ACTIVE"; createdBy = "tt-owner"; createdAt = now })
            memberships.persist(Membership().apply { id = "tt-m-o"; this.tenantId = this@TenantTermsSliceTest.tenantId; userId = "tt-owner"; role = "Owner"; status = "ACTIVE"; createdAt = now })
            memberships.persist(Membership().apply { id = "tt-m-m"; this.tenantId = this@TenantTermsSliceTest.tenantId; userId = "tt-member"; role = "Member"; status = "ACTIVE"; createdAt = now })
            scopes.persist(Scope().apply { id = scopeId; tenantId = this@TenantTermsSliceTest.tenantId; kind = "TENANT_ROOT"; suiteId = "v1"; kemPublicKey = "p".toByteArray(); createdAt = now })
            grants.persist(ScopeGrant().apply { id = "tt-grant"; this.scopeId = this@TenantTermsSliceTest.scopeId; userId = "tt-owner"; suiteId = "v1"; wrappedPrivateKey = "g".toByteArray(); createdAt = now })
            contentKeys.persist(ScopeContentKey().apply { id = "tt-ck"; this.scopeId = this@TenantTermsSliceTest.scopeId; suiteId = "v1"; wrappedKey = "ck".toByteArray(); createdAt = now })
            nodes.persist(Node().apply { id = "tt-mem"; scopeId = "tt-scope"; parentId = "tt-scope"; type = "MEMORY"; slugToken = "s-m"; orderIndex = 0; suiteId = "v1"; sealedContent = "n".toByteArray(); createdAt = now })
        }
        ownerToken = "Bearer ${sessions.issue("tt-owner")}"
        memberToken = "Bearer ${sessions.issue("tt-member")}"
    }

    @Test
    fun ownerSetsTermsGuestsAcceptThem() {
        // only an Owner may set the terms
        given().header("Authorization", memberToken).contentType(ContentType.JSON).body("""{"termsMarkdown":"## Hausregeln"}""")
            .put("/api/tenants/$tenantId/terms").then().statusCode(403)

        // owner sets terms — version bumps from 0 to 1
        given().header("Authorization", ownerToken).contentType(ContentType.JSON).body("""{"termsMarkdown":"## Hausregeln\n\nSei nett."}""")
            .put("/api/tenants/$tenantId/terms").then().statusCode(200).body("version", `is`(1)).body("termsMarkdown", `is`("## Hausregeln\n\nSei nett."))
        given().header("Authorization", ownerToken).get("/api/tenants/$tenantId/terms").then().statusCode(200).body("version", `is`(1))

        // a share of this tenant's memory now carries the tenant terms as a TENANT notice (no auth, for guests)
        val shareId = given().header("Authorization", ownerToken).contentType(ContentType.JSON)
            .body("""{"wrappedContentKey":{"suiteId":"v1","bytes":"d2Nr"}}""")
            .post("/api/scopes/$scopeId/nodes/tt-mem/shares").then().statusCode(200).extract().path<String>("shareId")
        given().get("/api/public/shares/$shareId").then().statusCode(200)
            .body("notices.size()", `is`(1)).body("notices[0].scope", `is`("TENANT"))
            .body("notices[0].markdown", `is`("## Hausregeln\n\nSei nett.")).body("notices[0].version", `is`(1))

        // editing the terms bumps the version (guests re-accept)
        given().header("Authorization", ownerToken).contentType(ContentType.JSON).body("""{"termsMarkdown":"## Neue Regeln"}""")
            .put("/api/tenants/$tenantId/terms").then().statusCode(200).body("version", `is`(2))
    }
}
