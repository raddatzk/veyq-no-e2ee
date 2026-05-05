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
import com.lunva.api.domain.UserEmailChangeRepo
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
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

@QuarkusTest
class UserManagementResourceTest {
    @Inject lateinit var userRepo: UserRepo
    @Inject lateinit var tenantRepo: TenantRepo
    @Inject lateinit var tenantMemberRepo: TenantMemberRepo
    @Inject lateinit var invitationRepo: UserInvitationRepo
    @Inject lateinit var emailChangeRepo: UserEmailChangeRepo
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
    fun `email change requires confirmation before new address can be used`() {
        val currentEmail = "mgmt-owner-$runId@example.com"
        val pendingEmail = "changed-$runId@example.com"

        given()
            .contentType("application/json")
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .body("""{"displayName":"Changed Owner","email":"$pendingEmail"}""")
            .`when`().put("/api/users/me")
            .then()
            .statusCode(200)
            .body("email", equalTo(currentEmail))
            .body("displayName", equalTo("Changed Owner"))
            .body("pendingEmail", equalTo(pendingEmail))

        given()
            .contentType("application/json")
            .body("""{"email":"$pendingEmail","password":"admin1234567"}""")
            .`when`().post("/api/auth/login")
            .then()
            .statusCode(401)

        given()
            .contentType("application/json")
            .body("""{"email":"$currentEmail","password":"admin1234567"}""")
            .`when`().post("/api/auth/login")
            .then()
            .statusCode(200)

        val token = emailChangeTokenFromMailbox(pendingEmail)

        given()
            .contentType("application/json")
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .body("""{"token":"$token"}""")
            .`when`().post("/api/users/me/email/confirm")
            .then()
            .statusCode(200)
            .body("email", equalTo(pendingEmail))
            .body("pendingEmail", nullValue())

        given()
            .contentType("application/json")
            .body("""{"email":"$currentEmail","password":"admin1234567"}""")
            .`when`().post("/api/auth/login")
            .then()
            .statusCode(401)

        given()
            .contentType("application/json")
            .body("""{"email":"$pendingEmail","password":"admin1234567"}""")
            .`when`().post("/api/auth/login")
            .then()
            .statusCode(200)
    }

    @Test
    fun `display name update does not create an email confirmation`() {
        val currentEmail = "mgmt-owner-$runId@example.com"

        given()
            .contentType("application/json")
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .body("""{"displayName":"Display Only","email":"$currentEmail"}""")
            .`when`().put("/api/users/me")
            .then()
            .statusCode(200)
            .body("email", equalTo(currentEmail))
            .body("displayName", equalTo("Display Only"))
            .body("pendingEmail", nullValue())

        assertThat(mailbox.getMailMessagesSentTo(currentEmail)).isEmpty()
    }

    @Test
    fun `password reset can be requested from login email and completed with token`() {
        val currentEmail = "mgmt-owner-$runId@example.com"

        given()
            .contentType("application/json")
            .body("""{"email":"missing-$runId@example.com"}""")
            .`when`().post("/api/users/password/forgot")
            .then()
            .statusCode(204)
        assertThat(mailbox.getMailMessagesSentTo("missing-$runId@example.com")).isEmpty()

        given()
            .contentType("application/json")
            .body("""{"email":"$currentEmail"}""")
            .`when`().post("/api/users/password/forgot")
            .then()
            .statusCode(204)

        val message = mailbox.getMailMessagesSentTo(currentEmail).single()
        assertThat(message.text).contains("https://${tenant.domain}/password/reset/")
        assertThat(message.text).contains("#")
        val resetPath = message.text.substringAfter("${tenant.domain}/password/reset/").trim()
        val resetUserId = resetPath.substringBefore("#").toLong()
        val resetToken = resetPath.substringAfter("#")
        assertThat(resetToken).doesNotContain("/")

        given()
            .contentType("application/json")
            .body("""{"password":"new-admin1234567","token":"$resetToken"}""")
            .`when`().post("/api/users/$resetUserId/password/reset")
            .then()
            .statusCode(204)

        given()
            .contentType("application/json")
            .body("""{"email":"$currentEmail","password":"admin1234567"}""")
            .`when`().post("/api/auth/login")
            .then()
            .statusCode(401)

        given()
            .contentType("application/json")
            .body("""{"email":"$currentEmail","password":"new-admin1234567"}""")
            .`when`().post("/api/auth/login")
            .then()
            .statusCode(200)
    }

    @Test
    fun `email confirmation must belong to the logged in user`() {
        val pendingEmail = "wrong-user-$runId@example.com"
        requestEmailChange(pendingEmail)
        val token = emailChangeTokenFromMailbox(pendingEmail)

        val other = createUser("other-owner-$runId", "admin1234567", ServerRole.ServerOwner)
        createTenant("other-$runId", "Other $runId", "other-$runId.example.test", other)
        val otherCookie = login(other.email, "admin1234567")

        given()
            .contentType("application/json")
            .cookie("LUNVA_ACCESS_TOKEN", otherCookie)
            .body("""{"token":"$token"}""")
            .`when`().post("/api/users/me/email/confirm")
            .then()
            .statusCode(403)
    }

    @Test
    fun `email confirmation rejects stale and conflicting tokens`() {
        val expiredEmail = "expired-$runId@example.com"
        requestEmailChange(expiredEmail)
        val expiredToken = emailChangeTokenFromMailbox(expiredEmail)
        inTransaction {
            emailChangeRepo.findLatestOpenByUser(userRepo.findByEmail("mgmt-owner-$runId@example.com")!!)
                ?.expiresAt = Instant.now().minus(Duration.ofDays(1))
        }

        given()
            .contentType("application/json")
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .body("""{"token":"$expiredToken"}""")
            .`when`().post("/api/users/me/email/confirm")
            .then()
            .statusCode(400)

        val revokedEmail = "revoked-$runId@example.com"
        requestEmailChange(revokedEmail)
        val revokedToken = emailChangeTokenFromMailbox(revokedEmail)
        val latestEmail = "latest-$runId@example.com"
        requestEmailChange(latestEmail)

        given()
            .contentType("application/json")
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .body("""{"token":"$revokedToken"}""")
            .`when`().post("/api/users/me/email/confirm")
            .then()
            .statusCode(400)

        val conflictEmail = "conflict-$runId@example.com"
        requestEmailChange(conflictEmail)
        val conflictToken = emailChangeTokenFromMailbox(conflictEmail)
        val conflictOwner = createUser("conflict-owner-$runId", "admin1234567", ServerRole.ServerOwner)
        inTransaction {
            userRepo.findById(conflictOwner.id)!!.email = conflictEmail
        }

        given()
            .contentType("application/json")
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .body("""{"token":"$conflictToken"}""")
            .`when`().post("/api/users/me/email/confirm")
            .then()
            .statusCode(409)
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

    private fun requestEmailChange(pendingEmail: String) {
        given()
            .contentType("application/json")
            .cookie("LUNVA_ACCESS_TOKEN", ownerCookie)
            .body("""{"displayName":"Owner","email":"$pendingEmail"}""")
            .`when`().put("/api/users/me")
            .then()
            .statusCode(200)
            .body("pendingEmail", equalTo(pendingEmail))
    }

    private fun emailChangeTokenFromMailbox(pendingEmail: String): String {
        val message = mailbox.getMailMessagesSentTo(pendingEmail).last()
        assertThat(message.text).contains("/email/confirm#")
        return message.text.substringAfter("/email/confirm#").trim()
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
