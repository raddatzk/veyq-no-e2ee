package com.veyq.api.tenant

import com.veyq.api.auth.PasswordService
import com.veyq.api.domain.GroupRole
import com.veyq.api.domain.ServerRole
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
import com.veyq.api.setup.SetupFilter
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import jakarta.transaction.UserTransaction
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

@QuarkusTest
class MultiTenantResourceTest {
    @Inject lateinit var userRepo: UserRepo
    @Inject lateinit var tenantRepo: TenantRepo
    @Inject lateinit var tenantMemberRepo: TenantMemberRepo
    @Inject lateinit var tenantGroupRepo: TenantGroupRepo
    @Inject lateinit var tenantGroupMemberRepo: TenantGroupMemberRepo
    @Inject lateinit var passwordService: PasswordService
    @Inject lateinit var serverState: ServerState
    @Inject lateinit var transaction: UserTransaction

    private lateinit var runId: String
    private lateinit var ownerEmail: String
    private lateinit var ownerPassword: String

    @BeforeEach
    fun createOwner() {
        runId = UUID.randomUUID().toString().take(8)
        ownerEmail = "owner-$runId@example.com"
        ownerPassword = "admin1234567"
        createUser("owner-$runId", ownerPassword, ServerRole.ServerOwner)
    }

    @Test
    fun `admin setup is blocked after first admin exists`() {
        given()
            .contentType("application/json")
            .body("""{"email":"second-$runId@example.com","password":"admin1234567","displayName":"Second"}""")
            .`when`().post("/api/setup/admin")
            .then()
            .statusCode(401)
    }

    @Test
    fun `tenant creation validates name and requires server owner`() {
        val ownerCookie = login(ownerEmail, ownerPassword)

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .body("""{"name":"api","displayName":"API","domain":"api-$runId.example.test"}""")
            .`when`().post("/api/tenants")
            .then()
            .statusCode(400)

        createUser("plain-$runId", "member123456", ServerRole.ServerMember)
        val memberCookie = login("plain-$runId@example.com", "member123456")

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", memberCookie)
            .body("""{"name":"plain-$runId","displayName":"Plain","domain":"plain-$runId.example.test"}""")
            .`when`().post("/api/tenants")
            .then()
            .statusCode(403)
    }

    @Test
    fun `tenant creation creates owner membership and lists only current user tenants`() {
        val ownerCookie = login(ownerEmail, ownerPassword)
        ensureTenant(ownerCookie, "friends-$runId", "Friends")

        given()
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .`when`().get("/api/tenants")
            .then()
            .statusCode(200)
            .body("tenants.name", hasItem("friends-$runId"))
            .body("tenants.displayName", hasItem("Friends"))
            .body("tenants.role", hasItem(TenantRole.TenantOwner.name))
            .body("tenants.admins.email.flatten()", hasItem(ownerEmail))

        createUser("outside-$runId", "member123456", ServerRole.ServerMember)
        val outsideCookie = login("outside-$runId@example.com", "member123456")

        given()
            .cookie("VEYQ_ACCESS_TOKEN", outsideCookie)
            .`when`().get("/api/tenants")
            .then()
            .statusCode(200)
            .body("tenants", hasSize<Any>(0))
    }

    @Test
    fun `tenant filter protects tenant routes`() {
        val ownerCookie = login(ownerEmail, ownerPassword)
        ensureTenant(ownerCookie, "friends-$runId", "Friends")
        createUser("outside-$runId", "member123456", ServerRole.ServerMember)
        val outsideCookie = login("outside-$runId@example.com", "member123456")

        given()
            .`when`().get("/api/friends-$runId/media")
            .then()
            .statusCode(401)

        given()
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .`when`().get("/api/missing-$runId/media")
            .then()
            .statusCode(404)

        given()
            .cookie("VEYQ_ACCESS_TOKEN", outsideCookie)
            .`when`().get("/api/friends-$runId/media")
            .then()
            .statusCode(403)
    }

    @Test
    fun `groups are tenant scoped and creator is group owner`() {
        val ownerCookie = login(ownerEmail, ownerPassword)
        ensureTenant(ownerCookie, "friends-$runId", "Friends")
        ensureTenant(ownerCookie, "family-$runId", "Family")

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .body("""{"name":"Trip","displayName":"Trip"}""")
            .`when`().post("/api/friends-$runId/groups")
            .then()
            .statusCode(200)
            .body("name", equalTo("trip"))
            .body("displayName", equalTo("Trip"))
            .body("members.role", hasItem(GroupRole.GroupOwner.name))

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .body("""{"name":"Trip","displayName":"Trip"}""")
            .`when`().post("/api/friends-$runId/groups")
            .then()
            .statusCode(409)

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .body("""{"name":"Trip","displayName":"Trip"}""")
            .`when`().post("/api/family-$runId/groups")
            .then()
            .statusCode(200)
    }

    @Test
    fun `tenant members can create groups and only read accessible groups`() {
        val ownerCookie = login(ownerEmail, ownerPassword)
        ensureTenant(ownerCookie, "friends-$runId", "Friends")
        createUser("member-$runId", "member123456", ServerRole.ServerMember)
        addTenantMember("friends-$runId", "member-$runId")
        val memberCookie = login("member-$runId@example.com", "member123456")

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", memberCookie)
            .body("""{"name":"owned","displayName":"Owned"}""")
            .`when`().post("/api/friends-$runId/groups")
            .then()
            .statusCode(200)
            .body("name", equalTo("owned"))
            .body("members.role", hasItem(GroupRole.GroupOwner.name))

        createGroupWithMember("friends-$runId", "foreign-$runId", ownerEmail, GroupRole.GroupOwner)

        given()
            .cookie("VEYQ_ACCESS_TOKEN", memberCookie)
            .`when`().get("/api/friends-$runId/groups")
            .then()
            .statusCode(200)
            .body("groups", hasSize<Any>(1))
            .body("groups.name", hasItem("owned"))

        given()
            .cookie("VEYQ_ACCESS_TOKEN", memberCookie)
            .`when`().get("/api/friends-$runId/groups/foreign-$runId")
            .then()
            .statusCode(404)
    }

    @Test
    fun `group update requires active tenant members and a group owner`() {
        val ownerCookie = login(ownerEmail, ownerPassword)
        ensureTenant(ownerCookie, "friends-$runId", "Friends")
        val outsider = createUser("outsider-$runId", "member123456", ServerRole.ServerMember)
        val member = createUser("crew-member-$runId", "member123456", ServerRole.ServerMember)
        addTenantMember("friends-$runId", "crew-member-$runId")
        val ownerId = userRepo.findByEmail(ownerEmail)!!.id

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .body("""{"name":"crew","displayName":"Crew"}""")
            .`when`().post("/api/friends-$runId/groups")
            .then()
            .statusCode(200)

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .body("""{"name":"crew","displayName":"Crew","members":[]}""")
            .`when`().post("/api/friends-$runId/groups/crew")
            .then()
            .statusCode(409)

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .body("""{"name":"crew","displayName":"Crew","members":[{"id":${outsider.id},"role":"GroupOwner"}]}""")
            .`when`().post("/api/friends-$runId/groups/crew")
            .then()
            .statusCode(404)

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .body("""{"name":"crew","displayName":"Crew","members":[{"id":$ownerId,"role":"GroupOwner"},{"id":${member.id},"role":"GroupMember"}]}""")
            .`when`().post("/api/friends-$runId/groups/crew")
            .then()
            .statusCode(200)
            .body("members.user.userId", hasItem(member.id.toInt()))
    }

    private fun ensureTenant(accessCookie: String, name: String, displayName: String) {
        val response = given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", accessCookie)
            .body("""{"name":"$name","displayName":"$displayName","domain":"$name.example.test"}""")
            .`when`().post("/api/tenants")

        if (response.statusCode != 200 && response.statusCode != 409) {
            throw AssertionError("Tenant setup failed with ${response.statusCode}: ${response.body.asString()}")
        }
    }

    private fun login(email: String, password: String): String {
        val accessCookie = given()
            .contentType("application/json")
            .body("""{"email":"$email","password":"$password"}""")
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

    private fun createUser(name: String, password: String, role: ServerRole): User =
        inTransaction {
            User().also {
                it.passwordHash = passwordService.hash(password)
                it.displayName = name
                it.email = "$name@example.com"
                it.role = role
                it.passwordSetAt = Instant.now()
                userRepo.persist(it)
            }
        }.also {
            serverState.markSetupCompleted()
        }

    private fun addTenantMember(tenantName: String, userName: String) {
        inTransaction {
            val tenant = tenantRepo.findByName(tenantName) ?: throw IllegalStateException("Tenant not found")
            val user = userRepo.findByEmail("$userName@example.com") ?: throw IllegalStateException("User not found")
            if (tenantMemberRepo.findByTenantAndUser(tenant, user) == null) {
                TenantMember().also {
                    it.id = TenantMemberId(tenant.id, user.id)
                    it.tenant = tenant
                    it.user = user
                    it.role = TenantRole.TenantMember
                    it.acceptedAt = java.time.Instant.now()
                    tenantMemberRepo.persist(it)
                }
            }
        }
    }

    private fun createGroupWithMember(
        tenantName: String,
        groupName: String,
        userEmail: String,
        role: GroupRole = GroupRole.GroupMember,
    ): TenantGroup =
        inTransaction {
            val tenant = tenantRepo.findByName(tenantName) ?: throw IllegalStateException("Tenant not found")
            val user = userRepo.findByEmail(userEmail) ?: throw IllegalStateException("User not found")
            val group = TenantGroup().also {
                it.tenant = tenant
                it.name = groupName
                it.displayName = groupName
                tenantGroupRepo.persist(it)
            }
            tenantGroupRepo.flush()
            TenantGroupMember().also {
                it.id = TenantGroupMemberId(group.id, user.id)
                it.group = group
                it.user = user
                it.role = role
                tenantGroupMemberRepo.persist(it)
            }
            group
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
