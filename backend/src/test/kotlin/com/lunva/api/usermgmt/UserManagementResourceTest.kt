package com.lunva.api.usermgmt

import com.lunva.api.auth.PasswordService
import com.lunva.api.domain.ServerRole
import com.lunva.api.domain.Tenant
import com.lunva.api.domain.TenantMember
import com.lunva.api.domain.TenantMemberId
import com.lunva.api.domain.TenantMemberRepo
import com.lunva.api.domain.TenantRepo
import com.lunva.api.domain.TenantRole
import com.lunva.api.domain.User
import com.lunva.api.domain.UserInvitationRepo
import com.lunva.api.domain.UserRepo
import com.lunva.api.setup.ServerState
import io.quarkus.mailer.MockMailbox
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import jakarta.transaction.UserTransaction
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

@QuarkusTest
class UserManagementResourceTest {
    @Inject lateinit var userRepo: UserRepo
    @Inject lateinit var tenantRepo: TenantRepo
    @Inject lateinit var tenantMemberRepo: TenantMemberRepo
    @Inject lateinit var invitationRepo: UserInvitationRepo
    @Inject lateinit var passwordService: PasswordService
    @Inject lateinit var serverState: ServerState
    @Inject lateinit var transaction: UserTransaction
    @Inject lateinit var mailbox: MockMailbox

    private lateinit var runId: String
    private lateinit var ownerCookie: String
    private lateinit var tenant: Tenant

    @BeforeEach
    fun setUp() {
        runId = UUID.randomUUID().toString().take(8)
        mailbox.clear()
        val owner = createUser("mgmt-owner-$runId", "admin1234567", ServerRole.ServerOwner)
        tenant = createTenant("mgmt-$runId", "Management $runId", "mgmt-$runId.example.test", owner)
        ownerCookie = login(owner.email, "admin1234567")
    }

    @Test
    fun `server admin creates invited tenant user and invitation can be accepted`() {
        val invitedUserId = given()
            .contentType("application/json")
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .body("""{"email":"invitee-$runId@example.com","displayName":"Invitee","serverRole":"ServerMember","tenantId":${tenant.id}}""")
            .`when`().post("/api/users")
            .then()
            .statusCode(200)
            .body("email", equalTo("invitee-$runId@example.com"))
            .body("status", equalTo("Pending"))
            .body("tenants.displayName", hasItem("Management $runId"))
            .extract()
            .path<Number>("userId")
            .toLong()

        given()
            .contentType("application/json")
            .body("""{"email":"invitee-$runId@example.com","password":"welcome123456"}""")
            .`when`().post("/api/auth/login")
            .then()
            .statusCode(401)

        val message = mailbox.getMailMessagesSentTo("invitee-$runId@example.com").single()
        assertThat(message.text).contains("https://${tenant.domain}/invite/$invitedUserId#")
        val token = message.text.substringAfter("/invite/$invitedUserId#").trim()

        given()
            .contentType("application/json")
            .body("""{"password":"welcome123456","token":"$token"}""")
            .`when`().post("/api/users/$invitedUserId/invite/accept")
            .then()
            .statusCode(200)
            .body("email", equalTo("invitee-$runId@example.com"))

        val invitation = invitationRepo.listAll().single { it.user.email == "invitee-$runId@example.com" }
        assertThat(invitation.acceptedAt).isNotNull

        given()
            .contentType("application/json")
            .body("""{"email":"invitee-$runId@example.com","password":"welcome123456"}""")
            .`when`().post("/api/auth/login")
            .then()
            .statusCode(200)
    }

    @Test
    fun `server admin cannot demote the only server owner in a single-admin instance`() {
        val owner = createIsolatedOwner("single-owner-$runId")
        val singleOwnerCookie = login(owner.email, "admin1234567")
        createPendingOwner("pending-owner-$runId", tenant)

        given()
            .contentType("application/json")
            .cookie("LUNVA_ACCESS_TOKEN", singleOwnerCookie)
            .body("""{"serverRole":"ServerMember"}""")
            .`when`().put("/api/users/${owner.id}/role")
            .then()
            .statusCode(409)
    }

    @Test
    fun `tenant creation validates required unique domain`() {
        given()
            .contentType("application/json")
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .body("""{"name":"missing-domain-$runId","displayName":"Missing"}""")
            .`when`().post("/api/tenants")
            .then()
            .statusCode(400)

        given()
            .contentType("application/json")
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .body("""{"name":"bad-domain-$runId","displayName":"Bad","domain":"https://bad.example.test"}""")
            .`when`().post("/api/tenants")
            .then()
            .statusCode(400)

        given()
            .contentType("application/json")
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .body("""{"name":"duplicate-domain-$runId","displayName":"Duplicate","domain":"${tenant.domain}"}""")
            .`when`().post("/api/tenants")
            .then()
            .statusCode(409)
    }

    private fun login(email: String, password: String): String =
        given()
            .contentType("application/json")
            .body("""{"email":"$email","password":"$password"}""")
            .`when`().post("/api/auth/login")
            .then()
            .statusCode(200)
            .extract()
            .cookie("LUNVA_ACCESS_TOKEN")

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

    private fun createIsolatedOwner(name: String): User =
        inTransaction {
            userRepo.listAll().forEach {
                it.role = ServerRole.ServerMember
            }
            User().also {
                it.passwordHash = passwordService.hash("admin1234567")
                it.displayName = name
                it.email = "$name@example.com"
                it.role = ServerRole.ServerOwner
                it.passwordSetAt = Instant.now()
                userRepo.persist(it)
            }
        }.also {
            serverState.markSetupCompleted()
        }

    private fun createPendingOwner(name: String, tenant: Tenant): User =
        inTransaction {
            val user = User().also {
                it.passwordHash = passwordService.hash("pending-password")
                it.displayName = name
                it.email = "$name@example.com"
                it.role = ServerRole.ServerOwner
                it.passwordSetAt = null
                userRepo.persist(it)
            }
            userRepo.flush()
            TenantMember().also {
                it.id = TenantMemberId(tenant.id, user.id)
                it.tenant = tenant
                it.user = user
                it.role = TenantRole.TenantMember
                tenantMemberRepo.persist(it)
            }
            user
        }

    private fun createTenant(name: String, displayName: String, domain: String, owner: User): Tenant =
        inTransaction {
            val tenant = Tenant().also {
                it.name = name
                it.displayName = displayName
                it.domain = domain
                tenantRepo.persist(it)
            }
            tenantRepo.flush()
            TenantMember().also {
                it.id = TenantMemberId(tenant.id, owner.id)
                it.tenant = tenant
                it.user = owner
                it.role = TenantRole.TenantOwner
                tenantMemberRepo.persist(it)
            }
            tenant
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
