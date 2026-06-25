package com.veyq.app.features.spaces.switchactivespace

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
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.`is`
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import com.veyq.app.shared.tenancy.TenantStatus
import com.veyq.app.shared.tenancy.MembershipStatus
import com.veyq.app.shared.tenancy.MemberRole
import com.veyq.app.shared.keymgmt.ScopeKind

@QuarkusTest
class MySpacesSliceTest {

    @Inject lateinit var users: UserRepository
    @Inject lateinit var tenants: TenantRepository
    @Inject lateinit var scopes: ScopeRepository
    @Inject lateinit var grants: ScopeGrantRepository
    @Inject lateinit var memberships: MembershipRepository
    @Inject lateinit var sessions: SessionService

    private lateinit var multiToken: String
    private lateinit var soloToken: String
    private lateinit var bareToken: String

    @BeforeTest
    fun seed() {
        QuarkusTransaction.requiringNew().run {
            val now = Instant.now()
            if (users.findByEmail("sp-multi@example.com") != null) return@run
            // sp-multi: a tenant-bound personal space + tenant space (Owner)
            users.persist(User().apply { id = "sp-multi"; email = "sp-multi@example.com"; displayName = "Multi User"; serverRole = ServerRole.ServerMember; createdAt = now })
            tenants.persist(Tenant().apply { id = "sp-tenant"; name = "sp-acme"; domain = "sp-acme.test"; logoObjectKey = "tenant-logos/sp-tenant/logo"; logoContentType = "image/png"; displayName = "Acme Group"; status = TenantStatus.ACTIVE; createdBy = "sp-multi"; createdAt = now })
            scopes.persist(Scope().apply { id = "sp-personal"; tenantId = "sp-tenant"; ownerUserId = "sp-multi"; kind = ScopeKind.PERSONAL_ROOT; suiteId = "v1"; kemPublicKey = "pp".toByteArray(); createdAt = now })
            grants.persist(ScopeGrant().apply { id = "sp-g-personal"; scopeId = "sp-personal"; userId = "sp-multi"; suiteId = "v1"; wrappedPrivateKey = "p".toByteArray(); createdAt = now })
            scopes.persist(Scope().apply { id = "sp-scope"; tenantId = "sp-tenant"; kind = ScopeKind.TENANT_ROOT; suiteId = "v1"; kemPublicKey = "gp".toByteArray(); createdAt = now })
            grants.persist(ScopeGrant().apply { id = "sp-g-group"; scopeId = "sp-scope"; userId = "sp-multi"; suiteId = "v1"; wrappedPrivateKey = "g".toByteArray(); createdAt = now })
            memberships.persist(Membership().apply { id = "sp-m-group"; tenantId = "sp-tenant"; userId = "sp-multi"; role = MemberRole.Owner; status = MembershipStatus.ACTIVE; createdAt = now })

            // sp-solo: only a personal scope, plus a membership WITHOUT a grant (must not surface as a space)
            users.persist(User().apply { id = "sp-solo"; email = "sp-solo@example.com"; displayName = "Solo User"; serverRole = ServerRole.ServerMember; createdAt = now })
            scopes.persist(Scope().apply { id = "sp-solo-personal"; tenantId = "sp-tenant"; ownerUserId = "sp-solo"; kind = ScopeKind.PERSONAL_ROOT; suiteId = "v1"; kemPublicKey = "sp".toByteArray(); createdAt = now })
            grants.persist(ScopeGrant().apply { id = "sp-g-solo"; scopeId = "sp-solo-personal"; userId = "sp-solo"; suiteId = "v1"; wrappedPrivateKey = "s".toByteArray(); createdAt = now })
            memberships.persist(Membership().apply { id = "sp-m-nokey"; tenantId = "sp-tenant"; userId = "sp-solo"; role = MemberRole.Member; status = MembershipStatus.ACTIVE; createdAt = now })

            // sp-bare: a freshly registered account before any tenant exists for it.
            users.persist(User().apply { id = "sp-bare"; email = "sp-bare@example.com"; displayName = "Bare User"; serverRole = ServerRole.ServerMember; createdAt = now })
            scopes.persist(Scope().apply { id = "sp-bare-personal"; ownerUserId = "sp-bare"; kind = ScopeKind.PERSONAL_ROOT; suiteId = "v1"; kemPublicKey = "bp".toByteArray(); createdAt = now })
            grants.persist(ScopeGrant().apply { id = "sp-g-bare"; scopeId = "sp-bare-personal"; userId = "sp-bare"; suiteId = "v1"; wrappedPrivateKey = "b".toByteArray(); createdAt = now })
        }
        multiToken = "Bearer ${sessions.issue("sp-multi")}"
        soloToken = "Bearer ${sessions.issue("sp-solo")}"
        bareToken = "Bearer ${sessions.issue("sp-bare")}"
    }

    @Test
    fun listsPersonalPlusHeldTenantRootSpaces() {
        // the multi-space user sees their personal space + the one TENANT_ROOT they hold the key for
        given().header("Authorization", multiToken).get("/api/me/spaces").then().statusCode(200)
            .body("spaces.size()", `is`(2))
            .body("spaces.find { it.kind == 'PERSONAL' }.displayName", `is`("Multi User"))
            .body("spaces.find { it.kind == 'PERSONAL' }.scopeId", `is`("sp-personal"))
            .body("spaces.find { it.kind == 'PERSONAL' }.tenantId", `is`("sp-tenant"))
            .body("spaces.find { it.kind == 'TENANT' }.role", `is`("Owner"))
            .body("spaces.find { it.kind == 'TENANT' }.displayName", `is`("Acme Group"))
            .body("spaces.find { it.kind == 'TENANT' }.logoUrl", `is`("/api/tenants/sp-tenant/logo?v=logo"))
            .body("spaces.find { it.kind == 'TENANT' }.tenantId", `is`("sp-tenant"))

        // the solo user sees no standalone personal space — without an enterable tenant context, the
        // personal scope must not surface as a workspace.
        given().header("Authorization", soloToken).get("/api/me/spaces").then().statusCode(200)
            .body("spaces.size()", `is`(0))

        // a user without any tenant sees no standalone personal space either
        given().header("Authorization", bareToken).get("/api/me/spaces").then().statusCode(200)
            .body("spaces.size()", `is`(0))

        // a bogus session is rejected
        given().header("Authorization", "Bearer nope").get("/api/me/spaces").then().statusCode(401)
    }
}
