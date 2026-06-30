package com.veyq.api.security

import com.veyq.api.auth.PasswordService
import com.veyq.api.domain.GroupRole
import com.veyq.api.domain.ServerRole
import com.veyq.api.domain.Tenant
import com.veyq.api.domain.TenantGroup
import com.veyq.api.domain.TenantGroupMember
import com.veyq.api.domain.TenantGroupMemberId
import com.veyq.api.domain.TenantGroupMemberRepo
import com.veyq.api.domain.TenantGroupRepo
import com.veyq.api.domain.TenantMember
import com.veyq.api.domain.TenantMemberId
import com.veyq.api.domain.TenantMemberRepo
import com.veyq.api.domain.TenantRepo
import com.veyq.api.domain.TenantRole
import com.veyq.api.domain.User
import com.veyq.api.domain.UserRepo
import com.veyq.api.setup.ServerState
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import jakarta.transaction.UserTransaction
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

@QuarkusTest
class SecurityPipelineTest {
    @Inject lateinit var userRepo: UserRepo
    @Inject lateinit var tenantRepo: TenantRepo
    @Inject lateinit var tenantMemberRepo: TenantMemberRepo
    @Inject lateinit var tenantGroupRepo: TenantGroupRepo
    @Inject lateinit var tenantGroupMemberRepo: TenantGroupMemberRepo
    @Inject lateinit var passwordService: PasswordService
    @Inject lateinit var transaction: UserTransaction
    @Inject lateinit var serverState: ServerState

    private lateinit var runId: String
    private lateinit var owner: User
    private lateinit var member: User
    private lateinit var outsider: User
    private lateinit var tenant: Tenant
    private lateinit var group: TenantGroup

    @BeforeEach
    fun setupFixture() {
        runId = UUID.randomUUID().toString().take(8)
        owner = createUser("sec-owner-$runId", ServerRole.ServerOwner)
        member = createUser("sec-member-$runId", ServerRole.ServerMember)
        outsider = createUser("sec-outsider-$runId", ServerRole.ServerMember)
        tenant = createTenant("sec-$runId", "Security $runId")
        addTenantMember(tenant, owner, TenantRole.TenantOwner)
        addTenantMember(tenant, member, TenantRole.TenantMember)
        group = createGroup(tenant, "crew-$runId")
        addGroupMember(group, owner, GroupRole.GroupOwner)
        addGroupMember(group, member, GroupRole.GroupMember)
    }

    @Test
    fun `security policy permits public setup status and denies protected API without auth`() {
        given()
            .`when`().get("/api/setup")
            .then()
            .statusCode(200)

        given()
            .`when`().get("/api/users/me")
            .then()
            .statusCode(401)

        given()
            .`when`().get("/api/tenants")
            .then()
            .statusCode(401)
    }

    @Test
    fun `setup filter returns stable setup required code`() {
        serverState.setupCompleted = false
        try {
            given()
                .`when`().get("/api/users/me")
                .then()
                .statusCode(503)
                .body("code", equalTo("SETUP_REQUIRED"))
                .body("message", equalTo("Setup required"))
        } finally {
            serverState.markSetupCompleted()
        }
    }

    @Test
    fun `tenant membership filter handles unauthenticated missing outsider and member cases`() {
        given()
            .`when`().get("/api/${tenant.name}/security-test/member")
            .then()
            .statusCode(401)

        given()
            .cookie("VEYQ_ACCESS_TOKEN", login(owner))
            .`when`().get("/api/missing-$runId/security-test/member")
            .then()
            .statusCode(404)

        given()
            .cookie("VEYQ_ACCESS_TOKEN", login(outsider))
            .`when`().get("/api/${tenant.name}/security-test/member")
            .then()
            .statusCode(403)

        given()
            .cookie("VEYQ_ACCESS_TOKEN", login(member))
            .`when`().get("/api/${tenant.name}/security-test/member")
            .then()
            .statusCode(200)
            .body(equalTo("member:${tenant.name}"))
    }

    @Test
    fun `tenant owner required allows tenant owner and rejects tenant member`() {
        given()
            .cookie("VEYQ_ACCESS_TOKEN", login(owner))
            .`when`().get("/api/${tenant.name}/security-test/tenant-owner")
            .then()
            .statusCode(200)
            .body(equalTo("tenant-owner:${tenant.name}"))

        given()
            .cookie("VEYQ_ACCESS_TOKEN", login(member))
            .`when`().get("/api/${tenant.name}/security-test/tenant-owner")
            .then()
            .statusCode(403)
    }

    @Test
    fun `group owner required allows group owner and rejects member missing group and outsider`() {
        given()
            .cookie("VEYQ_ACCESS_TOKEN", login(owner))
            .`when`().get("/api/${tenant.name}/security-test/groups/${group.name}/owner")
            .then()
            .statusCode(200)
            .body(equalTo("group-owner:${tenant.name}:${group.name}"))

        given()
            .cookie("VEYQ_ACCESS_TOKEN", login(member))
            .`when`().get("/api/${tenant.name}/security-test/groups/${group.name}/owner")
            .then()
            .statusCode(403)

        given()
            .cookie("VEYQ_ACCESS_TOKEN", login(owner))
            .`when`().get("/api/${tenant.name}/security-test/groups/missing-$runId/owner")
            .then()
            .statusCode(404)

        given()
            .cookie("VEYQ_ACCESS_TOKEN", login(outsider))
            .`when`().get("/api/${tenant.name}/security-test/groups/${group.name}/owner")
            .then()
            .statusCode(403)
    }

    private fun login(user: User): String {
        val accessCookie = given()
            .contentType("application/json")
            .body("""{"email":"${user.email}","password":"password1234"}""")
            .`when`().post("/api/auth/login")
            .then()
            .statusCode(200)
            .extract()
            .cookie("VEYQ_ACCESS_TOKEN")

        assertThat(accessCookie)
            .describedAs("Login did not return an access cookie")
            .isNotBlank()
        return accessCookie
    }

    private fun createUser(name: String, role: ServerRole): User =
        inTransaction {
            User().also {
                it.passwordHash = passwordService.hash("password1234")
                it.displayName = name
                it.permalink = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
                it.email = "$name@example.com"
                it.role = role
                it.passwordSetAt = Instant.now()
                userRepo.persist(it)
            }
        }

    private fun createTenant(name: String, displayName: String): Tenant =
        inTransaction {
            Tenant().also {
                it.name = name
                it.displayName = displayName
                it.domain = "$name.example.test"
                it.activatedAt = java.time.Instant.now()
                tenantRepo.persist(it)
            }
        }

    private fun addTenantMember(tenant: Tenant, user: User, role: TenantRole) {
        inTransaction {
            val managedTenant = tenantRepo.findById(tenant.id) ?: throw IllegalStateException("Tenant not found")
            val managedUser = userRepo.findById(user.id) ?: throw IllegalStateException("User not found")
            TenantMember().also {
                it.id = TenantMemberId(managedTenant.id, managedUser.id)
                it.tenant = managedTenant
                it.user = managedUser
                it.role = role
                it.acceptedAt = java.time.Instant.now()
                tenantMemberRepo.persist(it)
            }
        }
    }

    private fun createGroup(tenant: Tenant, name: String): TenantGroup =
        inTransaction {
            val managedTenant = tenantRepo.findById(tenant.id) ?: throw IllegalStateException("Tenant not found")
            TenantGroup().also {
                it.tenant = managedTenant
                it.name = name
                it.displayName = name
                tenantGroupRepo.persist(it)
            }
        }

    private fun addGroupMember(group: TenantGroup, user: User, role: GroupRole) {
        inTransaction {
            val managedGroup = tenantGroupRepo.findById(group.id) ?: throw IllegalStateException("Group not found")
            val managedUser = userRepo.findById(user.id) ?: throw IllegalStateException("User not found")
            TenantGroupMember().also {
                it.id = TenantGroupMemberId(managedGroup.id, managedUser.id)
                it.group = managedGroup
                it.user = managedUser
                it.role = role
                tenantGroupMemberRepo.persist(it)
            }
        }
    }

    private fun <T> inTransaction(block: () -> T): T {
        transaction.begin()
        return try {
            val result = block()
            transaction.commit()
            result
        } catch (e: Exception) {
            transaction.rollback()
            throw e
        }
    }
}
