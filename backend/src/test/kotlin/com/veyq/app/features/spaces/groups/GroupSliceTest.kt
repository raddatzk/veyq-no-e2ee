package com.veyq.app.features.spaces.groups

import com.veyq.app.shared.content.ScopeContentKeyRepository
import com.veyq.app.shared.identity.ServerRole
import com.veyq.app.shared.identity.User
import com.veyq.app.shared.identity.UserRepository
import com.veyq.app.shared.keymgmt.Scope
import com.veyq.app.shared.keymgmt.ScopeGrant
import com.veyq.app.shared.keymgmt.ScopeGrantRepository
import com.veyq.app.shared.keymgmt.ScopeRepository
import com.veyq.app.shared.security.SessionService
import com.veyq.app.shared.tenancy.GroupMembershipRepository
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
import org.hamcrest.Matchers.hasItem
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** uc.spaces.groups — create a group sub-space within a tenant; it surfaces as a GROUP space in the roster. */
@QuarkusTest
class GroupSliceTest {
    @Inject lateinit var users: UserRepository
    @Inject lateinit var tenants: TenantRepository
    @Inject lateinit var memberships: MembershipRepository
    @Inject lateinit var scopes: ScopeRepository
    @Inject lateinit var grants: ScopeGrantRepository
    @Inject lateinit var contentKeys: ScopeContentKeyRepository
    @Inject lateinit var groupMembers: GroupMembershipRepository
    @Inject lateinit var sessions: SessionService

    private lateinit var ownerToken: String
    private lateinit var outsiderToken: String
    private val tenantId = "gsl-tenant"
    private val tenantScopeId = "gsl-tscope"

    private val body = """
        {"scopePublicKey":{"suiteId":"v1","kemPublicKey":"AAAA"},
         "wrappedScopePrivateKey":{"suiteId":"v1","bytes":"BBBB"},
         "contentKey":{"suiteId":"v1","bytes":"CCCC"},
         "sealedName":{"suiteId":"v1","bytes":"DDDD"}}
    """.trimIndent()

    @BeforeTest
    fun seed() {
        QuarkusTransaction.requiringNew().run {
            if (users.findByEmail("gsl-owner@example.com") != null) return@run
            val now = Instant.now()
            users.persist(User().apply { id = "gsl-owner"; email = "gsl-owner@example.com"; displayName = "O"; serverRole = ServerRole.ServerMember; createdAt = now })
            users.persist(User().apply { id = "gsl-out"; email = "gsl-out@example.com"; displayName = "X"; serverRole = ServerRole.ServerMember; createdAt = now })
            tenants.persist(Tenant().apply { id = tenantId; name = "gsl-t"; displayName = "T"; status = "ACTIVE"; termsVersion = 0; createdBy = "gsl-owner"; createdAt = now })
            memberships.persist(Membership().apply { id = "gsl-mb"; this.tenantId = this@GroupSliceTest.tenantId; userId = "gsl-owner"; role = "Owner"; status = "ACTIVE"; createdAt = now })
            scopes.persist(Scope().apply { id = tenantScopeId; this.tenantId = this@GroupSliceTest.tenantId; kind = "TENANT_ROOT"; suiteId = "v1"; kemPublicKey = "p".toByteArray(); createdAt = now })
            grants.persist(ScopeGrant().apply { id = "gsl-tg"; scopeId = tenantScopeId; userId = "gsl-owner"; suiteId = "v1"; wrappedPrivateKey = "g".toByteArray(); createdAt = now })
        }
        ownerToken = "Bearer ${sessions.issue("gsl-owner")}"
        outsiderToken = "Bearer ${sessions.issue("gsl-out")}"
    }

    @Test
    fun createsGroupAsGroupOwnerAndSurfacesItInTheRoster() {
        // a non-member of the tenant cannot create a group there
        given().header("Authorization", outsiderToken).contentType(ContentType.JSON).body(body)
            .post("/api/tenants/$tenantId/groups").then().statusCode(403)

        val scopeId = given().header("Authorization", ownerToken).contentType(ContentType.JSON).body(body)
            .post("/api/tenants/$tenantId/groups").then()
            .statusCode(200)
            .body("tenantId", `is`(tenantId))
            .body("role", `is`("GroupOwner"))
            .extract().path<String>("scopeId")

        // the group scope, self-grant, content key and GroupOwner membership all exist
        QuarkusTransaction.requiringNew().run {
            assertEquals("GROUP_ROOT", scopes.findById(scopeId)?.kind)
            assertNotNull(grants.find(scopeId, "gsl-owner"))
            assertNotNull(contentKeys.findByScopeId(scopeId))
            assertEquals("GroupOwner", groupMembers.find(scopeId, "gsl-owner")?.role)
        }

        // the roster now shows both the tenant-wide space and the group
        given().header("Authorization", ownerToken).get("/api/me/spaces").then()
            .statusCode(200)
            .body("spaces.kind", hasItem("TENANT"))
            .body("spaces.kind", hasItem("GROUP"))
    }
}
