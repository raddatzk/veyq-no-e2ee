package com.veyq.app.features.spaces.usegroup

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
class MyGroupsSliceTest {

    @Inject lateinit var users: UserRepository
    @Inject lateinit var tenants: TenantRepository
    @Inject lateinit var scopes: ScopeRepository
    @Inject lateinit var grants: ScopeGrantRepository
    @Inject lateinit var memberships: MembershipRepository
    @Inject lateinit var sessions: SessionService

    private lateinit var ownerToken: String
    private lateinit var memberToken: String
    private lateinit var ghostToken: String

    @BeforeTest
    fun seed() {
        QuarkusTransaction.requiringNew().run {
            val now = Instant.now()
            if (users.findByEmail("grp-owner@example.com") != null) return@run
            users.persist(User().apply { id = "grp-owner"; email = "grp-owner@example.com"; displayName = "Owner"; serverRole = ServerRole.ServerMember; createdAt = now })
            users.persist(User().apply { id = "grp-member"; email = "grp-member@example.com"; displayName = "Member"; serverRole = ServerRole.ServerMember; createdAt = now })
            // a user who is an active member but holds NO grant (removed-but-not-rotated) — must not see the group
            users.persist(User().apply { id = "grp-ghost"; email = "grp-ghost@example.com"; displayName = "Ghost"; serverRole = ServerRole.ServerMember; createdAt = now })

            tenants.persist(Tenant().apply { id = "grp-tenant"; name = "grp-acme"; domain = "grp-acme.test"; logoObjectKey = "tenant-logos/grp-tenant/logo"; logoContentType = "image/png"; displayName = "Acme Group"; status = TenantStatus.ACTIVE; createdBy = "grp-owner"; createdAt = now })
            scopes.persist(Scope().apply { id = "grp-scope"; tenantId = "grp-tenant"; kind = ScopeKind.TENANT_ROOT; suiteId = "v1"; kemPublicKey = "grp-pub".toByteArray(); createdAt = now })
            // a second tenant the owner is NOT a member of — must not leak into the owner's roster
            tenants.persist(Tenant().apply { id = "grp-other"; name = "grp-other"; domain = "grp-other.test"; displayName = "Other"; status = TenantStatus.ACTIVE; createdBy = "grp-member"; createdAt = now })
            scopes.persist(Scope().apply { id = "grp-other-scope"; tenantId = "grp-other"; kind = ScopeKind.TENANT_ROOT; suiteId = "v1"; kemPublicKey = "oth-pub".toByteArray(); createdAt = now })

            grants.persist(ScopeGrant().apply { id = "grp-g-owner"; scopeId = "grp-scope"; userId = "grp-owner"; suiteId = "v1"; wrappedPrivateKey = "o".toByteArray(); createdAt = now })
            grants.persist(ScopeGrant().apply { id = "grp-g-member"; scopeId = "grp-scope"; userId = "grp-member"; suiteId = "v1"; wrappedPrivateKey = "m".toByteArray(); createdAt = now })
            memberships.persist(Membership().apply { id = "grp-m-owner"; tenantId = "grp-tenant"; userId = "grp-owner"; role = MemberRole.Owner; status = MembershipStatus.ACTIVE; createdAt = now })
            memberships.persist(Membership().apply { id = "grp-m-member"; tenantId = "grp-tenant"; userId = "grp-member"; role = MemberRole.Member; status = MembershipStatus.ACTIVE; createdAt = now })
            memberships.persist(Membership().apply { id = "grp-m-ghost"; tenantId = "grp-tenant"; userId = "grp-ghost"; role = MemberRole.Member; status = MembershipStatus.ACTIVE; createdAt = now })
        }
        ownerToken = "Bearer ${sessions.issue("grp-owner")}"
        memberToken = "Bearer ${sessions.issue("grp-member")}"
        ghostToken = "Bearer ${sessions.issue("grp-ghost")}"
    }

    @Test
    fun listsOnlyGroupsTheCallerHoldsTheKeyFor() {
        // the owner sees exactly the one group they hold the key for (the other tenant is not theirs)
        given().header("Authorization", ownerToken).get("/api/me/groups").then().statusCode(200)
            .body("groups.size()", `is`(1))
            .body("groups[0].tenantId", `is`("grp-tenant"))
            .body("groups[0].displayName", `is`("Acme Group"))
            .body("groups[0].logoUrl", `is`("/api/tenants/grp-tenant/logo?v=logo"))
            .body("groups[0].role", `is`("Owner"))
            .body("groups[0].scopeId", `is`("grp-scope"))
            .body("groups[0].scopePublicKey.kemPublicKey", `is`("Z3JwLXB1Yg")) // base64url("grp-pub")

        // a member of the same group sees it with their own role
        given().header("Authorization", memberToken).get("/api/me/groups").then().statusCode(200)
            .body("groups.size()", `is`(1))
            .body("groups[0].role", `is`("Member"))

        // a member WITHOUT the scope grant cannot enter — the group is absent (access = the wrap)
        given().header("Authorization", ghostToken).get("/api/me/groups").then().statusCode(200)
            .body("groups.size()", `is`(0))

        // a bogus session is rejected
        given().header("Authorization", "Bearer nope").get("/api/me/groups").then().statusCode(401)
    }
}
