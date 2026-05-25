package com.veyq.api.usermgmt

import com.veyq.api.auth.PasswordService
import com.veyq.api.domain.ResetPasswordTokenRepo
import com.veyq.api.domain.ServerRole
import com.veyq.api.domain.PasskeyCredential
import com.veyq.api.domain.PasskeyCredentialRepo
import com.veyq.api.domain.Tenant
import com.veyq.api.domain.TenantMember
import com.veyq.api.domain.TenantMemberId
import com.veyq.api.domain.TenantMemberRepo
import com.veyq.api.domain.TenantRepo
import com.veyq.api.domain.TenantRole
import com.veyq.api.domain.User
import com.veyq.api.domain.UserEmailChangeRepo
import com.veyq.api.domain.UserInvitationRepo
import com.veyq.api.domain.UserRepo
import com.veyq.api.security.RateLimitService
import com.veyq.api.setup.ServerState
import io.quarkus.mailer.MockMailbox
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.filter.cookie.CookieFilter
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.UserTransaction
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
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
    @Inject lateinit var resetPasswordTokenRepo: ResetPasswordTokenRepo
    @Inject lateinit var passkeyCredentialRepo: PasskeyCredentialRepo
    @Inject lateinit var passwordService: PasswordService
    @Inject lateinit var serverState: ServerState
    @Inject lateinit var entityManager: EntityManager
    @Inject lateinit var transaction: UserTransaction
    @Inject lateinit var mailbox: MockMailbox
    @Inject lateinit var rateLimitService: RateLimitService

    private lateinit var runId: String
    private lateinit var ownerCookie: String
    private lateinit var tenant: Tenant

    @BeforeEach
    fun setUp() {
        runId = UUID.randomUUID().toString().take(8)
        mailbox.clear()
        rateLimitService.clearAll()
        val owner = createUser("mgmt-owner-$runId", "admin1234567", ServerRole.ServerOwner)
        tenant = createTenant("mgmt-$runId", "Management $runId", "mgmt-$runId.example.test", owner)
        ownerCookie = login(owner.email, "admin1234567")
    }

    @Test
    fun `server admin creates invited tenant user and invitation can be accepted`() {
        val invitedUserId = given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .body("""{"email":"invitee-$runId@example.com"}""")
            .`when`().post("/api/users")
            .then()
            .statusCode(200)
            .body("email", equalTo("invitee-$runId@example.com"))
            .body("status", equalTo("Pending"))
            .body("tenants", equalTo(emptyList<Any>()))
            .extract()
            .path<Number>("userId")
            .toLong()

        assertThat(mailbox.getMailMessagesSentTo("invitee-$runId@example.com")).isEmpty()

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .body("""{"email":"invitee-$runId@example.com","role":"TenantMember"}""")
            .`when`().post("/api/tenants/${tenant.name}/users/invite")
            .then()
            .statusCode(200)
            .body("email", equalTo("invitee-$runId@example.com"))
            .body("status", equalTo("Pending"))

        given()
            .contentType("application/json")
            .body("""{"email":"invitee-$runId@example.com","password":"welcome123456"}""")
            .`when`().post("/api/auth/login")
            .then()
            .statusCode(401)

        val message = mailbox.getMailMessagesSentTo("invitee-$runId@example.com").single()
        assertThat(message.text).contains("https://${tenant.domain}/invite/$invitedUserId#")
        val token = message.text.substringAfter("/invite/$invitedUserId#").trim()
        val pendingInvitation = invitationRepo.listAll().single { it.user.email == "invitee-$runId@example.com" }
        assertExpiresAround(pendingInvitation.expiresAt, Duration.ofDays(7))

        given()
            .contentType("application/json")
            .body("""{"displayName":"Invitee","password":"welcome123456","token":"$token"}""")
            .`when`().post("/api/users/$invitedUserId/invite/accept")
            .then()
            .statusCode(200)
            .body("email", equalTo("invitee-$runId@example.com"))
            .body("displayName", equalTo("Invitee"))

        entityManager.clear()
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
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .body("""{"displayName":"Changed Owner","permalink":"mgmt-owner-$runId","email":"$pendingEmail"}""")
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
        val pendingChange = emailChangeRepo.findLatestOpenByUser(userRepo.findByEmail(currentEmail)!!)!!
        assertExpiresAround(pendingChange.expiresAt, Duration.ofHours(24))

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
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
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .body("""{"displayName":"Display Only","permalink":"mgmt-owner-$runId","email":"$currentEmail"}""")
            .`when`().put("/api/users/me")
            .then()
            .statusCode(200)
            .body("email", equalTo(currentEmail))
            .body("displayName", equalTo("Display Only"))
            .body("pendingEmail", nullValue())

        assertThat(mailbox.getMailMessagesSentTo(currentEmail)).isEmpty()
    }

    @Test
    fun `user settings can change permalink independently from display name`() {
        val currentEmail = "mgmt-owner-$runId@example.com"
        val nextPermalink = "kevin-$runId"

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .body("""{"displayName":"Display Only","permalink":"$nextPermalink","email":"$currentEmail"}""")
            .`when`().put("/api/users/me")
            .then()
            .statusCode(200)
            .body("displayName", equalTo("Display Only"))
            .body("permalink", equalTo(nextPermalink))

        given()
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .`when`().get("/api/${tenant.name}/profiles/$nextPermalink")
            .then()
            .statusCode(200)
            .body("displayName", equalTo("Display Only"))
            .body("permalink", equalTo(nextPermalink))
            .body("initials", equalTo("DO"))
    }

    @Test
    fun `user settings rejects duplicate and reserved permalinks`() {
        val other = createUser("other-user-$runId", "admin1234567", ServerRole.ServerMember)
        val currentEmail = "mgmt-owner-$runId@example.com"

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .body("""{"displayName":"Owner","permalink":"${other.permalink}","email":"$currentEmail"}""")
            .`when`().put("/api/users/me")
            .then()
            .statusCode(409)

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .body("""{"displayName":"Owner","permalink":"me","email":"$currentEmail"}""")
            .`when`().put("/api/users/me")
            .then()
            .statusCode(400)
    }

    @Test
    fun `profile endpoint returns only public header fields`() {
        @Suppress("UNCHECKED_CAST")
        val response = given()
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .`when`().get("/api/${tenant.name}/profiles/mgmt-owner-$runId")
            .then()
            .statusCode(200)
            .extract()
            .`as`(Map::class.java) as Map<String, Any?>

        assertThat(response).containsEntry("displayName", "mgmt-owner-$runId")
        assertThat(response).containsEntry("permalink", "mgmt-owner-$runId")
        assertThat(response).containsEntry("initials", "M")
        assertThat(response).doesNotContainKeys("userId", "email", "memories")

        given()
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .`when`().get("/api/${tenant.name}/profiles/unknown-$runId")
            .then()
            .statusCode(404)
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
        val resetPasswordToken = resetPasswordTokenRepo.listOpenByUser(userRepo.findByEmail(currentEmail)!!).single()
        assertExpiresAround(resetPasswordToken.expiresAt, Duration.ofHours(2))

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
    fun `user can list and delete own passkeys`() {
        val currentUser = userRepo.findByEmail("mgmt-owner-$runId@example.com")!!
        val passkey = createPasskey(currentUser, "Laptop")

        given()
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .`when`().get("/api/users/me/passkeys")
            .then()
            .statusCode(200)
            .body("passkeys.displayName", hasItem("Laptop"))

        given()
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .`when`().get("/api/users/me")
            .then()
            .statusCode(200)
            .body("passkeyCount", equalTo(1))

        given()
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .`when`().delete("/api/users/me/passkeys/${passkey.id}")
            .then()
            .statusCode(204)

        assertThat(passkeyCredentialRepo.findById(passkey.id)).isNull()
    }

    @Test
    fun `creating passkey requires display name`() {
        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .body(
                """
                {
                  "displayName": "   ",
                  "credential": ${passkeyRegistrationJson()}
                }
                """.trimIndent(),
            )
            .`when`().post("/api/users/me/passkeys")
            .then()
            .statusCode(400)
            .body("message", equalTo("displayName must not be blank"))
    }

    @Test
    fun `user can rename own passkey`() {
        val currentUser = userRepo.findByEmail("mgmt-owner-$runId@example.com")!!
        val passkey = createPasskey(currentUser, "Laptop")

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .body("""{"displayName":"  MacBook Touch ID  "}""")
            .`when`().put("/api/users/me/passkeys/${passkey.id}")
            .then()
            .statusCode(200)
            .body("passkeyId", equalTo(passkey.id.toInt()))
            .body("displayName", equalTo("MacBook Touch ID"))

        entityManager.clear()
        assertThat(passkeyCredentialRepo.findById(passkey.id)!!.displayName).isEqualTo("MacBook Touch ID")
    }

    @Test
    fun `renaming passkey requires display name`() {
        val currentUser = userRepo.findByEmail("mgmt-owner-$runId@example.com")!!
        val passkey = createPasskey(currentUser, "Laptop")

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .body("""{"displayName":"   "}""")
            .`when`().put("/api/users/me/passkeys/${passkey.id}")
            .then()
            .statusCode(400)
            .body("message", equalTo("displayName must not be blank"))
    }

    @Test
    fun `user cannot rename another users passkey`() {
        val other = createUser("passkey-other-$runId", "admin1234567", ServerRole.ServerMember)
        val passkey = createPasskey(other, "Other Laptop")

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .body("""{"displayName":"Owner Laptop"}""")
            .`when`().put("/api/users/me/passkeys/${passkey.id}")
            .then()
            .statusCode(404)
    }

    @Test
    fun `passkey registration options use email as username`() {
        val currentUser = userRepo.findByEmail("mgmt-owner-$runId@example.com")!!
        val cookieFilter = CookieFilter()

        val firstChallenge = given()
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .filter(cookieFilter)
            .`when`().post("/api/users/me/passkeys/options")
            .then()
            .statusCode(200)
            .body("user.name", equalTo(currentUser.email))
            .body("user.displayName", equalTo(currentUser.displayName))
            .body("user.id", not(equalTo(currentUser.email)))
            .extract()
            .path<String>("challenge")

        val secondChallenge = given()
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .filter(cookieFilter)
            .`when`().post("/api/users/me/passkeys/options")
            .then()
            .statusCode(200)
            .body("user.name", equalTo(currentUser.email))
            .body("user.displayName", equalTo(currentUser.displayName))
            .body("user.id", not(equalTo(currentUser.email)))
            .extract()
            .path<String>("challenge")

        assertThat(secondChallenge).isNotEqualTo(firstChallenge)
    }

    @Test
    fun `forgot password requests are rate limited`() {
        val currentEmail = "mgmt-owner-$runId@example.com"

        repeat(3) {
            given()
                .contentType("application/json")
                .body("""{"email":"$currentEmail"}""")
                .`when`().post("/api/users/password/forgot")
                .then()
                .statusCode(204)
        }

        given()
            .contentType("application/json")
            .body("""{"email":"$currentEmail"}""")
            .`when`().post("/api/users/password/forgot")
            .then()
            .statusCode(429)
            .header("Retry-After", org.hamcrest.Matchers.notNullValue())
    }

    @Test
    fun `reset password token failures are rate limited and success clears the failure counter`() {
        val currentEmail = "mgmt-owner-$runId@example.com"
        val userId = userRepo.findByEmail(currentEmail)!!.id

        given()
            .contentType("application/json")
            .body("""{"email":"$currentEmail"}""")
            .`when`().post("/api/users/password/forgot")
            .then()
            .statusCode(204)
        val token = resetPasswordTokenFromMailbox(currentEmail)

        given()
            .contentType("application/json")
            .body("""{"password":"ignored123456","token":"wrong"}""")
            .`when`().post("/api/users/$userId/password/reset")
            .then()
            .statusCode(403)

        given()
            .contentType("application/json")
            .body("""{"password":"new-admin1234567","token":"$token"}""")
            .`when`().post("/api/users/$userId/password/reset")
            .then()
            .statusCode(204)

        repeat(5) {
            given()
                .contentType("application/json")
                .body("""{"password":"ignored123456","token":"wrong-$it"}""")
                .`when`().post("/api/users/$userId/password/reset")
                .then()
                .statusCode(403)
        }

        given()
            .contentType("application/json")
            .body("""{"password":"ignored123456","token":"blocked"}""")
            .`when`().post("/api/users/$userId/password/reset")
            .then()
            .statusCode(429)
            .header("Retry-After", org.hamcrest.Matchers.notNullValue())
    }

    @Test
    fun `used reset password token returns password reset error`() {
        val currentEmail = "mgmt-owner-$runId@example.com"
        val userId = userRepo.findByEmail(currentEmail)!!.id

        given()
            .contentType("application/json")
            .body("""{"email":"$currentEmail"}""")
            .`when`().post("/api/users/password/forgot")
            .then()
            .statusCode(204)
        val token = resetPasswordTokenFromMailbox(currentEmail)

        given()
            .contentType("application/json")
            .body("""{"password":"new-admin1234567","token":"$token"}""")
            .`when`().post("/api/users/$userId/password/reset")
            .then()
            .statusCode(204)

        given()
            .contentType("application/json")
            .body("""{"password":"another-admin1234567","token":"$token"}""")
            .`when`().post("/api/users/$userId/password/reset")
            .then()
            .statusCode(400)
            .body("code", equalTo("BAD_REQUEST"))
            .body("message", equalTo("Password reset token is no longer valid"))
    }

    @Test
    fun `tenant owner invites and activates existing tenant user by email`() {
        val existingUser = createUser("searchable-$runId", "admin1234567", ServerRole.ServerMember)

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .body("""{"email":"${existingUser.email}","role":"TenantMember"}""")
            .`when`().post("/api/tenants/${tenant.name}/users/invite")
            .then()
            .statusCode(200)
            .body("email", equalTo(existingUser.email))
            .body("role", equalTo("TenantMember"))
            .body("status", equalTo("Pending"))

        val firstInviteMessage = mailbox.getMailMessagesSentTo(existingUser.email).single()
        val token = firstInviteMessage.text.substringAfter("/invite/${existingUser.id}#").trim()
        val pendingMembership = tenantMemberRepo.findByTenantAndUser(tenant, existingUser)!!
        assertThat(pendingMembership.acceptedAt).isNull()

        given()
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .`when`().get("/api/tenants/${tenant.name}/users")
            .then()
            .statusCode(200)
            .body("users.email", hasItem(existingUser.email))
            .body("users.status", hasItem("Pending"))

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .body("""{"email":"${existingUser.email}","role":"TenantMember"}""")
            .`when`().post("/api/tenants/${tenant.name}/users/invite")
            .then()
            .statusCode(409)

        given()
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .`when`().post("/api/tenants/${tenant.name}/users/${existingUser.id}/reinvite")
            .then()
            .statusCode(200)
            .body("status", equalTo("Pending"))
        assertThat(mailbox.getMailMessagesSentTo(existingUser.email)).hasSize(2)
        val latestToken = mailbox.getMailMessagesSentTo(existingUser.email)
            .last()
            .text
            .substringAfter("/invite/${existingUser.id}#")
            .trim()

        given()
            .contentType("application/json")
            .body("""{"token":"$latestToken"}""")
            .`when`().post("/api/users/${existingUser.id}/invite/accept")
            .then()
            .statusCode(401)

        val existingUserCookie = login(existingUser.email, "admin1234567")
        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", existingUserCookie)
            .body("""{"displayName":"Ignored","password":"ignored123456","token":"$latestToken"}""")
            .`when`().post("/api/users/${existingUser.id}/invite/accept")
            .then()
            .statusCode(200)

        entityManager.clear()
        val acceptedUser = userRepo.findById(existingUser.id)!!
        assertThat(acceptedUser.displayName).isEqualTo("searchable-$runId")
        assertThat(tenantMemberRepo.findByTenantAndUser(tenant, acceptedUser)!!.acceptedAt).isNotNull
    }

    @Test
    fun `accepting invitation with passkey requires passkey display name`() {
        val invitedUserId = given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .body("""{"email":"passkey-invitee-$runId@example.com"}""")
            .`when`().post("/api/users")
            .then()
            .statusCode(200)
            .extract()
            .path<Number>("userId")
            .toLong()

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .body("""{"email":"passkey-invitee-$runId@example.com","role":"TenantMember"}""")
            .`when`().post("/api/tenants/${tenant.name}/users/invite")
            .then()
            .statusCode(200)

        val token = mailbox.getMailMessagesSentTo("passkey-invitee-$runId@example.com")
            .single()
            .text
            .substringAfter("/invite/$invitedUserId#")
            .trim()

        given()
            .contentType("application/json")
            .body(
                """
                {
                  "displayName": "Invitee",
                  "passkeyDisplayName": "   ",
                  "token": "$token",
                  "credential": ${passkeyRegistrationJson()}
                }
                """.trimIndent(),
            )
            .`when`().post("/api/users/$invitedUserId/invite/passkey/accept")
            .then()
            .statusCode(400)
            .body("message", equalTo("displayName must not be blank"))
    }

    @Test
    fun `tenant owner invites new tenant user by email without server role escalation and manages tenant role`() {
        val tenantAdmin = createUser("tenant-admin-$runId", "admin1234567", ServerRole.ServerMember)
        addTenantMember(tenantAdmin, tenant, TenantRole.TenantOwner)
        val tenantAdminCookie = login(tenantAdmin.email, "admin1234567")

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", tenantAdminCookie)
            .body("""{"email":"global-created-$runId@example.com"}""")
            .`when`().post("/api/users")
            .then()
            .statusCode(403)

        val createdUserId = given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", tenantAdminCookie)
            .body("""{"email":"tenant-created-$runId@example.com","role":"TenantOwner"}""")
            .`when`().post("/api/tenants/${tenant.name}/users/invite")
            .then()
            .statusCode(200)
            .body("email", equalTo("tenant-created-$runId@example.com"))
            .body("role", equalTo("TenantOwner"))
            .body("status", equalTo("Pending"))
            .extract()
            .path<Number>("user.userId")
            .toLong()

        val createdUser = userRepo.findById(createdUserId)!!
        assertThat(createdUser.role).isEqualTo(ServerRole.ServerMember)

        entityManager.clear()
        val createdMembership = tenantMemberRepo.findByTenantAndUser(tenant, createdUser)!!
        assertThat(createdMembership.role).isEqualTo(TenantRole.TenantOwner)
        assertThat(createdMembership.acceptedAt).isNull()

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .body("""{"role":"TenantMember"}""")
            .`when`().put("/api/tenants/${tenant.name}/users/${tenantAdmin.id}/role")
            .then()
            .statusCode(200)
            .body("role", equalTo("TenantMember"))

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .body("""{"role":"TenantMember"}""")
            .`when`().put("/api/tenants/${tenant.name}/users/${ownerUserId()}/role")
            .then()
            .statusCode(409)
    }

    @Test
    fun `tenant member can list tenant users but cannot mutate tenant users`() {
        val tenantMember = createUser("tenant-member-$runId", "admin1234567", ServerRole.ServerMember)
        addTenantMember(tenantMember, tenant, TenantRole.TenantMember)
        val tenantMemberCookie = login(tenantMember.email, "admin1234567")

        given()
            .cookie("VEYQ_ACCESS_TOKEN", tenantMemberCookie)
            .`when`().get("/api/tenants/${tenant.name}/users")
            .then()
            .statusCode(200)
            .body("users.user.displayName", hasItem(tenantMember.displayName))
            .body("users.email", hasItem(nullValue()))
            .body("users.role", hasItem(nullValue()))
            .body("users.status", hasItem(nullValue()))

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", tenantMemberCookie)
            .body("""{"role":"TenantMember"}""")
            .`when`().put("/api/tenants/${tenant.name}/users/${tenantMember.id}/role")
            .then()
            .statusCode(403)

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", tenantMemberCookie)
            .body("""{"email":"blocked-$runId@example.com","role":"TenantMember"}""")
            .`when`().post("/api/tenants/${tenant.name}/users/invite")
            .then()
            .statusCode(403)
    }

    @Test
    fun `duplicate global pending user can be invited into another tenant`() {
        val email = "multi-pending-$runId@example.com"
        val userId = given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .body("""{"email":"$email"}""")
            .`when`().post("/api/users")
            .then()
            .statusCode(200)
            .extract()
            .path<Number>("userId")
            .toLong()

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .body("""{"email":"$email","role":"TenantMember"}""")
            .`when`().post("/api/tenants/${tenant.name}/users/invite")
            .then()
            .statusCode(200)

        val otherTenant = createTenant("mgmt-other-$runId", "Other $runId", "other-$runId.example.test", userRepo.findByEmail("mgmt-owner-$runId@example.com")!!)

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .body("""{"email":"$email"}""")
            .`when`().post("/api/users")
            .then()
            .statusCode(409)

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .body("""{"email":"$email","role":"TenantOwner"}""")
            .`when`().post("/api/tenants/${otherTenant.name}/users/invite")
            .then()
            .statusCode(200)
            .body("user.userId", equalTo(userId.toInt()))
            .body("role", equalTo("TenantOwner"))
            .body("status", equalTo("Pending"))

        entityManager.clear()
        val user = userRepo.findById(userId)!!
        assertThat(tenantMemberRepo.findByTenantAndUser(tenant, user)).isNotNull
        assertThat(tenantMemberRepo.findByTenantAndUser(otherTenant, user)).isNotNull
        assertThat(mailbox.getMailMessagesSentTo(email)).hasSize(2)
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
            .cookie("VEYQ_ACCESS_TOKEN", otherCookie)
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
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
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
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
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
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
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
            .cookie("VEYQ_ACCESS_TOKEN", singleOwnerCookie)
            .body("""{"serverRole":"ServerMember"}""")
            .`when`().put("/api/users/${owner.id}/role")
            .then()
            .statusCode(409)
    }

    @Test
    fun `tenant creation validates required unique domain`() {
        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .body("""{"name":"missing-domain-$runId","displayName":"Missing"}""")
            .`when`().post("/api/tenants")
            .then()
            .statusCode(400)

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .body("""{"name":"bad-domain-$runId","displayName":"Bad","domain":"https://bad.example.test"}""")
            .`when`().post("/api/tenants")
            .then()
            .statusCode(400)

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
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
            .cookie("VEYQ_ACCESS_TOKEN")

    private fun requestEmailChange(pendingEmail: String) {
        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .body("""{"displayName":"Owner","permalink":"mgmt-owner-$runId","email":"$pendingEmail"}""")
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

    private fun resetPasswordTokenFromMailbox(email: String): String {
        val message = mailbox.getMailMessagesSentTo(email).last()
        assertThat(message.text).contains("/password/reset/")
        return message.text.substringAfter("#").trim()
    }

    private fun ownerUserId(): Long = userRepo.findByEmail("mgmt-owner-$runId@example.com")!!.id

    private fun assertExpiresAround(expiresAt: Instant, expectedAfter: Duration) {
        val expected = Instant.now().plus(expectedAfter)
        assertThat(expiresAt)
            .isAfter(expected.minus(Duration.ofMinutes(1)))
            .isBefore(expected.plus(Duration.ofMinutes(1)))
    }

    private fun createUser(name: String, password: String, role: ServerRole): User =
        inTransaction {
            User().also {
                it.passwordHash = passwordService.hash(password)
                it.displayName = name
                it.permalink = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
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
                it.permalink = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
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
                it.permalink = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
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
                it.acceptedAt = java.time.Instant.now()
                tenantMemberRepo.persist(it)
            }
            user
        }

    private fun createPasskey(user: User, displayName: String): PasskeyCredential =
        inTransaction {
            PasskeyCredential().also {
                it.user = userRepo.findById(user.id)!!
                it.credentialId = "credential-$runId-${displayName.lowercase()}"
                it.publicKey = "public-key-$runId".toByteArray()
                it.publicKeyAlgorithm = -7
                it.counter = 0
                it.aaguid = "00000000-0000-0000-0000-000000000000"
                it.displayName = displayName
                it.createdAt = Instant.now()
                passkeyCredentialRepo.persist(it)
            }
        }

    private fun passkeyRegistrationJson(): String =
        """
        {
          "id": "credential-id",
          "rawId": "credential-id",
          "type": "public-key",
          "response": {
            "clientDataJSON": "client-data",
            "attestationObject": "attestation-object"
          }
        }
        """.trimIndent()

    private fun addTenantMember(user: User, tenant: Tenant, role: TenantRole) {
        inTransaction {
            TenantMember().also {
                it.id = TenantMemberId(tenant.id, user.id)
                it.tenant = tenant
                it.user = user
                it.role = role
                it.acceptedAt = java.time.Instant.now()
                tenantMemberRepo.persist(it)
            }
        }
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
                it.acceptedAt = java.time.Instant.now()
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
