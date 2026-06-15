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

    @BeforeTest
    fun seed() {
        QuarkusTransaction.requiringNew().run {
            val now = Instant.now()
            if (users.findByEmail("sp-multi@example.com") != null) return@run
            // sp-multi: a personal space + a group space (Owner)
            users.persist(User().apply { id = "sp-multi"; email = "sp-multi@example.com"; displayName = "Multi User"; serverRole = ServerRole.ServerMember; createdAt = now })
            scopes.persist(Scope().apply { id = "sp-personal"; ownerUserId = "sp-multi"; kind = "PERSONAL_ROOT"; suiteId = "v1"; kemPublicKey = "pp".toByteArray(); createdAt = now })
            grants.persist(ScopeGrant().apply { id = "sp-g-personal"; scopeId = "sp-personal"; userId = "sp-multi"; suiteId = "v1"; wrappedPrivateKey = "p".toByteArray(); createdAt = now })
            tenants.persist(Tenant().apply { id = "sp-tenant"; name = "sp-acme"; domain = "sp-acme.test"; displayName = "Acme Group"; status = "ACTIVE"; createdBy = "sp-multi"; createdAt = now })
            scopes.persist(Scope().apply { id = "sp-scope"; tenantId = "sp-tenant"; kind = "TENANT_ROOT"; suiteId = "v1"; kemPublicKey = "gp".toByteArray(); createdAt = now })
            grants.persist(ScopeGrant().apply { id = "sp-g-group"; scopeId = "sp-scope"; userId = "sp-multi"; suiteId = "v1"; wrappedPrivateKey = "g".toByteArray(); createdAt = now })
            memberships.persist(Membership().apply { id = "sp-m-group"; tenantId = "sp-tenant"; userId = "sp-multi"; role = "Owner"; status = "ACTIVE"; createdAt = now })

            // sp-solo: only a personal space, plus a membership WITHOUT a grant (must not surface as a space)
            users.persist(User().apply { id = "sp-solo"; email = "sp-solo@example.com"; displayName = "Solo User"; serverRole = ServerRole.ServerMember; createdAt = now })
            scopes.persist(Scope().apply { id = "sp-solo-personal"; ownerUserId = "sp-solo"; kind = "PERSONAL_ROOT"; suiteId = "v1"; kemPublicKey = "sp".toByteArray(); createdAt = now })
            grants.persist(ScopeGrant().apply { id = "sp-g-solo"; scopeId = "sp-solo-personal"; userId = "sp-solo"; suiteId = "v1"; wrappedPrivateKey = "s".toByteArray(); createdAt = now })
            memberships.persist(Membership().apply { id = "sp-m-nokey"; tenantId = "sp-tenant"; userId = "sp-solo"; role = "Member"; status = "ACTIVE"; createdAt = now })
        }
        multiToken = "Bearer ${sessions.issue("sp-multi")}"
        soloToken = "Bearer ${sessions.issue("sp-solo")}"
    }

    @Test
    fun listsPersonalPlusHeldGroupSpaces() {
        // the multi-space user sees their personal space + the one group they hold the key for
        given().header("Authorization", multiToken).get("/api/me/spaces").then().statusCode(200)
            .body("spaces.size()", `is`(2))
            .body("spaces.find { it.kind == 'PERSONAL' }.displayName", `is`("Multi User"))
            .body("spaces.find { it.kind == 'PERSONAL' }.scopeId", `is`("sp-personal"))
            .body("spaces.find { it.kind == 'TENANT' }.role", `is`("Owner"))
            .body("spaces.find { it.kind == 'TENANT' }.displayName", `is`("Acme Group"))
            .body("spaces.find { it.kind == 'TENANT' }.tenantId", `is`("sp-tenant"))

        // the solo user sees only their personal space — the group membership without a grant is filtered out
        given().header("Authorization", soloToken).get("/api/me/spaces").then().statusCode(200)
            .body("spaces.size()", `is`(1))
            .body("spaces[0].kind", `is`("PERSONAL"))

        // a bogus session is rejected
        given().header("Authorization", "Bearer nope").get("/api/me/spaces").then().statusCode(401)
    }
}
