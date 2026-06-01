package com.veyq.api.usermgmt

import com.veyq.api.auth.PasswordService
import com.veyq.api.domain.ResetPasswordTokenRepo
import com.veyq.api.domain.ServerRole
import com.veyq.api.domain.PasskeyCredential
import com.veyq.api.domain.PasskeyCredentialRepo
import com.veyq.api.domain.PasskeyLinkTokenRepo
import com.veyq.api.domain.LibraryScopeType
import com.veyq.api.domain.Media
import com.veyq.api.domain.MediaBlob
import com.veyq.api.domain.Memory
import com.veyq.api.domain.MemoryFavorite
import com.veyq.api.domain.MemoryMediaBucket
import com.veyq.api.domain.MemoryParticipant
import com.veyq.api.domain.MemoryPlaylist
import com.veyq.api.domain.MemoryPlaylistProvider
import com.veyq.api.domain.MemoryTrack
import com.veyq.api.domain.MemoryTrackActivityType
import com.veyq.api.domain.Tenant
import com.veyq.api.domain.TenantFolder
import com.veyq.api.domain.TenantMember
import com.veyq.api.domain.TenantMemberId
import com.veyq.api.domain.TenantMemberRepo
import com.veyq.api.domain.TenantMemberStatus
import com.veyq.api.domain.TenantRepo
import com.veyq.api.domain.TenantRole
import com.veyq.api.domain.TotpCredential
import com.veyq.api.domain.TotpCredentialRepo
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
import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.imageio.ImageIO

@QuarkusTest
class UserManagementResourceTest {
    @Inject lateinit var userRepo: UserRepo
    @Inject lateinit var tenantRepo: TenantRepo
    @Inject lateinit var tenantMemberRepo: TenantMemberRepo
    @Inject lateinit var invitationRepo: UserInvitationRepo
    @Inject lateinit var emailChangeRepo: UserEmailChangeRepo
    @Inject lateinit var resetPasswordTokenRepo: ResetPasswordTokenRepo
    @Inject lateinit var passkeyCredentialRepo: PasskeyCredentialRepo
    @Inject lateinit var passkeyLinkTokenRepo: PasskeyLinkTokenRepo
    @Inject lateinit var totpCredentialRepo: TotpCredentialRepo
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
    fun `uploads serves and deletes own avatar`() {
        val avatar = createAvatarImage()

        val avatarUrl = given()
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .multiPart("file", avatar.toFile(), "image/png")
            .`when`().put("/api/users/me/avatar")
            .then()
            .statusCode(200)
            .body("avatarUrl", org.hamcrest.Matchers.startsWith("/api/users/me/avatar?v="))
            .extract()
            .path<String>("avatarUrl")

        given()
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .`when`().get(avatarUrl)
            .then()
            .statusCode(200)
            .contentType("image/png")

        given()
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .`when`().delete("/api/users/me/avatar")
            .then()
            .statusCode(200)
            .body("avatarUrl", nullValue())

        given()
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .`when`().get("/api/users/me/avatar")
            .then()
            .statusCode(404)
    }

    @Test
    fun `profile avatar follows tenant profile visibility`() {
        val avatar = createAvatarImage()
        val owner = userRepo.findByEmail("mgmt-owner-$runId@example.com")!!
        given()
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .multiPart("file", avatar.toFile(), "image/png")
            .`when`().put("/api/users/me/avatar")
            .then()
            .statusCode(200)

        given()
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .`when`().get("/api/${tenant.name}/profiles/${owner.permalink}/avatar")
            .then()
            .statusCode(200)
            .contentType("image/png")

        val outsider = createUser("avatar-outsider-$runId", "admin1234567", ServerRole.ServerMember)
        given()
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .`when`().get("/api/${tenant.name}/profiles/${outsider.permalink}/avatar")
            .then()
            .statusCode(404)
    }

    @Test
    fun `avatar upload rejects invalid image data`() {
        val invalid = Files.createTempFile("veyq-avatar-invalid-", ".png")
        Files.writeString(invalid, "not an image")

        given()
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .multiPart("file", invalid.toFile(), "image/png")
            .`when`().put("/api/users/me/avatar")
            .then()
            .statusCode(400)
    }

    @Test
    fun `avatar upload rejects oversized files`() {
        val oversized = Files.createTempFile("veyq-avatar-oversized-", ".png")
        Files.write(oversized, ByteArray((5 * 1024 * 1024) + 1))

        given()
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .multiPart("file", oversized.toFile(), "image/png")
            .`when`().put("/api/users/me/avatar")
            .then()
            .statusCode(413)
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
            .body("""{"token":"$token"}""")
            .`when`().post("/api/users/$invitedUserId/invite/metadata")
            .then()
            .statusCode(200)
            .body("email", equalTo("invitee-$runId@example.com"))

        given()
            .contentType("application/json")
            .body("""{"displayName":"Invitee","password":"welcome123456","token":"$token"}""")
            .`when`().post("/api/users/$invitedUserId/invite/accept")
            .then()
            .statusCode(200)
            .body("user.email", equalTo("invitee-$runId@example.com"))
            .body("user.displayName", equalTo("Invitee"))
            .body("accessTokenExpiresAt", notNullValue())

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
        assertThat(response).containsKey("memories")
        assertThat(response).doesNotContainKeys("userId", "email")

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
    fun `user can list own passkeys`() {
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
            .body("hasPassword", equalTo(true))
            .body("totpEnabled", equalTo(false))
    }

    @Test
    fun `password can be deleted with current password when passkey exists and removes totp`() {
        val currentUser = userRepo.findByEmail("mgmt-owner-$runId@example.com")!!
        createPasskey(currentUser, "Laptop")
        createTotpCredential(currentUser)

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .body("""{"password":"admin1234567"}""")
            .`when`().post("/api/users/me/password/delete")
            .then()
            .statusCode(200)
            .body("hasPassword", equalTo(false))
            .body("totpEnabled", equalTo(false))
            .body("passkeyCount", equalTo(1))

        entityManager.clear()
        val user = userRepo.findById(currentUser.id)!!
        assertThat(user.passwordSetAt).isNull()
        assertThat(totpCredentialRepo.findByUser(user)).isNull()

        given()
            .contentType("application/json")
            .body("""{"email":"${currentUser.email}","password":"admin1234567"}""")
            .`when`().post("/api/auth/login")
            .then()
            .statusCode(401)
    }

    @Test
    fun `password delete rejects wrong password and missing passkey fallback`() {
        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .body("""{"password":"wrong-password"}""")
            .`when`().post("/api/users/me/password/delete")
            .then()
            .statusCode(401)

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .body("""{"password":"admin1234567"}""")
            .`when`().post("/api/users/me/password/delete")
            .then()
            .statusCode(409)
    }

    @Test
    fun `passkey delete requires valid own passkey assertion`() {
        val currentUser = userRepo.findByEmail("mgmt-owner-$runId@example.com")!!
        val passkey = createPasskey(currentUser, "Laptop")

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .body(deleteMyPasskeyJson())
            .`when`().post("/api/users/me/passkeys/${passkey.id}/delete")
            .then()
            .statusCode(401)

        entityManager.clear()
        assertThat(passkeyCredentialRepo.findById(passkey.id)).isNotNull
    }

    @Test
    fun `last passkey without password cannot be deleted`() {
        val currentUser = userRepo.findByEmail("mgmt-owner-$runId@example.com")!!
        val passkey = createPasskey(currentUser, "Laptop")
        inTransaction {
            userRepo.findById(currentUser.id)!!.passwordSetAt = null
        }

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .body(deleteMyPasskeyJson())
            .`when`().post("/api/users/me/passkeys/${passkey.id}/delete")
            .then()
            .statusCode(409)
    }

    @Test
    fun `foreign passkey cannot be deleted`() {
        val other = createUser("passkey-delete-other-$runId", "admin1234567", ServerRole.ServerMember)
        val passkey = createPasskey(other, "Other Laptop")

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .body(deleteMyPasskeyJson())
            .`when`().post("/api/users/me/passkeys/${passkey.id}/delete")
            .then()
            .statusCode(404)
    }

    @Test
    fun `passkey only user can set first password without current password`() {
        val currentUser = userRepo.findByEmail("mgmt-owner-$runId@example.com")!!
        createPasskey(currentUser, "Laptop")
        inTransaction {
            userRepo.findById(currentUser.id)!!.passwordSetAt = null
        }

        given()
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .`when`().get("/api/users/me")
            .then()
            .statusCode(200)
            .body("hasPassword", equalTo(false))
            .body("passkeyCount", equalTo(1))

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .body("""{"newPassword":"new-admin1234567"}""")
            .`when`().post("/api/users/me/password/change")
            .then()
            .statusCode(204)

        entityManager.clear()
        assertThat(userRepo.findById(currentUser.id)!!.passwordSetAt).isNotNull

        given()
            .contentType("application/json")
            .body("""{"email":"${currentUser.email}","password":"new-admin1234567"}""")
            .`when`().post("/api/auth/login")
            .then()
            .statusCode(200)
    }

    @Test
    fun `password user must provide current password when changing password`() {
        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .body("""{"newPassword":"new-admin1234567"}""")
            .`when`().post("/api/users/me/password/change")
            .then()
            .statusCode(401)

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .body("""{"currentPassword":"wrong-password","newPassword":"new-admin1234567"}""")
            .`when`().post("/api/users/me/password/change")
            .then()
            .statusCode(401)

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .body("""{"currentPassword":"admin1234567","newPassword":"new-admin1234567"}""")
            .`when`().post("/api/users/me/password/change")
            .then()
            .statusCode(204)

        given()
            .contentType("application/json")
            .body("""{"email":"mgmt-owner-$runId@example.com","password":"new-admin1234567"}""")
            .`when`().post("/api/auth/login")
            .then()
            .statusCode(200)
    }

    @Test
    fun `creating passkey does not require display name`() {
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
            .body("message", equalTo("Invalid passkey registration"))
    }

    @Test
    fun `passkey domains list other tenant domains`() {
        val owner = userRepo.findByEmail("mgmt-owner-$runId@example.com")!!
        createTenant("mgmt-alt-$runId", "Alternate $runId", "alt-$runId.example.test", owner)

        given()
            .header("Host", tenant.domain)
            .`when`().get("/api/auth/passkeys/domains")
            .then()
            .statusCode(200)
            .body("domains.domain", hasItem("alt-$runId.example.test"))
            .body("domains.domain", not(hasItem(tenant.domain)))
    }

    @Test
    fun `passkey domains reject unknown hosts`() {
        given()
            .header("Host", "unknown-$runId.example.test")
            .`when`().get("/api/auth/passkeys/domains")
            .then()
            .statusCode(403)
    }

    @Test
    fun `passkey registration options reject unknown hosts`() {
        given()
            .header("Host", "unknown-$runId.example.test")
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .`when`().post("/api/users/me/passkeys/options")
            .then()
            .statusCode(403)
    }

    @Test
    fun `passkey link code is exchanged into target-domain grant`() {
        val owner = userRepo.findByEmail("mgmt-owner-$runId@example.com")!!
        val targetTenant = createTenant("mgmt-link-$runId", "Link $runId", "link-$runId.example.test", owner)
        val code = createPasskeyLink(targetTenant.domain, "/${targetTenant.name}")

        entityManager.clear()
        val storedToken = passkeyLinkTokenRepo.listAll().single { it.targetDomain == targetTenant.domain }
        assertThat(storedToken.codeHash).doesNotContain(code)
        assertThat(storedToken.codeHash).hasSize(64)
        assertThat(storedToken.grantHash).isNull()
        assertThat(storedToken.authDomain).isEqualTo(tenant.domain)
        assertThat(storedToken.targetDomain).isEqualTo(targetTenant.domain)
        assertThat(storedToken.redirectPath).isEqualTo("/${targetTenant.name}")
        assertExpiresAround(storedToken.expiresAt, Duration.ofMinutes(5))

        given()
            .header("Host", tenant.domain)
            .contentType("application/json")
            .body("""{"code":"$code"}""")
            .`when`().post("/api/auth/passkeys/link/exchange")
            .then()
            .statusCode(403)

        val grant = given()
            .header("Host", targetTenant.domain)
            .contentType("application/json")
            .body("""{"code":"$code"}""")
            .`when`().post("/api/auth/passkeys/link/exchange")
            .then()
            .statusCode(200)
            .body("redirectPath", equalTo("/${targetTenant.name}"))
            .extract()
            .path<String>("grant")

        entityManager.clear()
        val exchangedToken = passkeyLinkTokenRepo.listAll().single { it.targetDomain == targetTenant.domain }
        assertThat(exchangedToken.grantHash).doesNotContain(grant)
        assertThat(exchangedToken.grantHash).hasSize(64)
        assertThat(exchangedToken.exchangedAt).isNotNull

        given()
            .header("Host", targetTenant.domain)
            .contentType("application/json")
            .body("""{"grant":"$grant"}""")
            .`when`().post("/api/auth/passkeys/link/options")
            .then()
            .statusCode(200)

        given()
            .header("Host", targetTenant.domain)
            .contentType("application/json")
            .body("""{"code":"$code"}""")
            .`when`().post("/api/auth/passkeys/link/exchange")
            .then()
            .statusCode(400)
    }

    @Test
    fun `passkey link creation rejects unauthenticated same unknown and malformed targets`() {
        val owner = userRepo.findByEmail("mgmt-owner-$runId@example.com")!!
        val targetTenant = createTenant("mgmt-link-reject-$runId", "Link Reject $runId", "link-reject-$runId.example.test", owner)

        given()
            .header("Host", tenant.domain)
            .contentType("application/json")
            .body("""{"targetOrigin":"https://${targetTenant.domain}","redirectPath":"/"}""")
            .`when`().post("/api/auth/passkeys/link")
            .then()
            .statusCode(401)

        given()
            .header("Host", tenant.domain)
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .contentType("application/json")
            .body("""{"targetOrigin":"https://${tenant.domain}","redirectPath":"/"}""")
            .`when`().post("/api/auth/passkeys/link")
            .then()
            .statusCode(400)

        given()
            .header("Host", tenant.domain)
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .contentType("application/json")
            .body("""{"targetOrigin":"https://unknown-$runId.example.test","redirectPath":"/"}""")
            .`when`().post("/api/auth/passkeys/link")
            .then()
            .statusCode(403)

        given()
            .header("Host", tenant.domain)
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .contentType("application/json")
            .body("""{"targetOrigin":"https://${targetTenant.domain}/login","redirectPath":"/"}""")
            .`when`().post("/api/auth/passkeys/link")
            .then()
            .statusCode(400)

        given()
            .header("Host", tenant.domain)
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .contentType("application/json")
            .body("""{"targetOrigin":"https://${targetTenant.domain}","redirectPath":"//evil.example.test"}""")
            .`when`().post("/api/auth/passkeys/link")
            .then()
            .statusCode(400)
    }

    @Test
    fun `passkey link token requires accepted target tenant membership`() {
        val outsider = createUser("link-outsider-$runId", "admin1234567", ServerRole.ServerMember)
        val outsiderCookie = login(outsider.email, "admin1234567")
        val targetTenant = createTenant("mgmt-link-member-$runId", "Link Member $runId", "link-member-$runId.example.test", userRepo.findByEmail("mgmt-owner-$runId@example.com")!!)

        given()
            .header("Host", tenant.domain)
            .cookie("VEYQ_ACCESS_TOKEN", outsiderCookie)
            .contentType("application/json")
            .body("""{"targetOrigin":"https://${targetTenant.domain}","redirectPath":"/"}""")
            .`when`().post("/api/auth/passkeys/link")
            .then()
            .statusCode(403)

        inTransaction {
            TenantMember().also {
                it.id = TenantMemberId(targetTenant.id, outsider.id)
                it.tenant = targetTenant
                it.user = outsider
                it.role = TenantRole.TenantMember
                it.acceptedAt = null
                tenantMemberRepo.persist(it)
            }
        }

        given()
            .header("Host", tenant.domain)
            .cookie("VEYQ_ACCESS_TOKEN", outsiderCookie)
            .contentType("application/json")
            .body("""{"targetOrigin":"https://${targetTenant.domain}","redirectPath":"/"}""")
            .`when`().post("/api/auth/passkeys/link")
            .then()
            .statusCode(403)
    }

    @Test
    fun `passkey link creation rejects existing passkey for target domain`() {
        val owner = userRepo.findByEmail("mgmt-owner-$runId@example.com")!!
        val targetTenant = createTenant("mgmt-link-existing-$runId", "Link Existing $runId", "link-existing-$runId.example.test", owner)
        createPasskey(owner, "Existing Target", targetTenant.domain)

        given()
            .header("Host", tenant.domain)
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .contentType("application/json")
            .body("""{"targetOrigin":"https://${targetTenant.domain}","redirectPath":"/"}""")
            .`when`().post("/api/auth/passkeys/link")
            .then()
            .statusCode(409)
    }

    @Test
    fun `passkey link grant is rejected if target passkey appears after exchange`() {
        val owner = userRepo.findByEmail("mgmt-owner-$runId@example.com")!!
        val targetTenant = createTenant("mgmt-link-race-$runId", "Link Race $runId", "link-race-$runId.example.test", owner)
        val grant = exchangePasskeyLink(createPasskeyLink(targetTenant.domain), targetTenant.domain)
        createPasskey(owner, "Existing Target", targetTenant.domain)

        given()
            .header("Host", targetTenant.domain)
            .contentType("application/json")
            .body("""{"grant":"$grant"}""")
            .`when`().post("/api/auth/passkeys/link/options")
            .then()
            .statusCode(409)
    }

    @Test
    fun `passkey link token cannot be used as a normal session`() {
        val owner = userRepo.findByEmail("mgmt-owner-$runId@example.com")!!
        val targetTenant = createTenant("mgmt-link-session-$runId", "Link Session $runId", "link-session-$runId.example.test", owner)
        val code = createPasskeyLink(targetTenant.domain)

        given()
            .header("Host", targetTenant.domain)
            .header("Authorization", "Bearer $code")
            .`when`().get("/api/users/me")
            .then()
            .statusCode(401)
    }

    @Test
    fun `passkey link options reject consumed and expired tokens`() {
        val owner = userRepo.findByEmail("mgmt-owner-$runId@example.com")!!
        val consumedTenant = createTenant("mgmt-link-consumed-$runId", "Link Consumed $runId", "link-consumed-$runId.example.test", owner)
        val consumedGrant = exchangePasskeyLink(createPasskeyLink(consumedTenant.domain), consumedTenant.domain)

        inTransaction {
            passkeyLinkTokenRepo.listAll().single { it.targetDomain == consumedTenant.domain }.consumedAt = Instant.now()
        }

        given()
            .header("Host", consumedTenant.domain)
            .contentType("application/json")
            .body("""{"grant":"$consumedGrant"}""")
            .`when`().post("/api/auth/passkeys/link/options")
            .then()
            .statusCode(400)

        val expiredTenant = createTenant("mgmt-link-expired-$runId", "Link Expired $runId", "link-expired-$runId.example.test", owner)
        val expiredGrant = exchangePasskeyLink(createPasskeyLink(expiredTenant.domain), expiredTenant.domain)

        inTransaction {
            passkeyLinkTokenRepo.listAll().single { it.targetDomain == expiredTenant.domain }.expiresAt = Instant.now().minusSeconds(1)
        }

        given()
            .header("Host", expiredTenant.domain)
            .contentType("application/json")
            .body("""{"grant":"$expiredGrant"}""")
            .`when`().post("/api/auth/passkeys/link/options")
            .then()
            .statusCode(400)
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
            .body("rp.name", equalTo("veyq"))
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
    fun `passkey reset sends domain-bound reset link and options reject wrong domain`() {
        val currentUser = userRepo.findByEmail("mgmt-owner-$runId@example.com")!!
        createPasskey(currentUser, "Laptop", tenant.domain)

        given()
            .header("Host", tenant.domain)
            .contentType("application/json")
            .body("""{"email":"${currentUser.email}"}""")
            .`when`().post("/api/users/passkeys/forgot")
            .then()
            .statusCode(204)

        val message = mailbox.getMailMessagesSentTo(currentUser.email).last()
        assertThat(message.text).contains("https://${tenant.domain}/passkey/reset/${currentUser.id}#")
        val token = message.text.substringAfter("#").trim()

        entityManager.clear()
        val resetToken = resetPasswordTokenRepo.listOpenByUser(userRepo.findById(currentUser.id)!!).single()
        assertThat(resetToken.resetDomain).isEqualTo(tenant.domain)

        val altTenant = createTenant("mgmt-passkey-reset-$runId", "Passkey Reset $runId", "passkey-reset-$runId.example.test", currentUser)

        given()
            .header("Host", altTenant.domain)
            .contentType("application/json")
            .body("""{"token":"$token"}""")
            .`when`().post("/api/users/${currentUser.id}/passkeys/reset/options")
            .then()
            .statusCode(403)

        given()
            .header("Host", tenant.domain)
            .contentType("application/json")
            .body("""{"token":"$token"}""")
            .`when`().post("/api/users/${currentUser.id}/passkeys/reset/options")
            .then()
            .statusCode(200)
    }

    @Test
    fun `passkey reset uses the request domain for multi-tenant users`() {
        val currentUser = userRepo.findByEmail("mgmt-owner-$runId@example.com")!!
        createPasskey(currentUser, "Laptop", tenant.domain)
        val altTenant = createTenant("mgmt-passkey-reset-domain-$runId", "Passkey Reset Domain $runId", "passkey-reset-domain-$runId.example.test", currentUser)

        given()
            .header("Host", altTenant.domain)
            .contentType("application/json")
            .body("""{"email":"${currentUser.email}"}""")
            .`when`().post("/api/users/passkeys/forgot")
            .then()
            .statusCode(204)

        val message = mailbox.getMailMessagesSentTo(currentUser.email).last()
        assertThat(message.text).contains("https://${altTenant.domain}/passkey/reset/${currentUser.id}#")

        entityManager.clear()
        val resetToken = resetPasswordTokenRepo.listOpenByUser(userRepo.findById(currentUser.id)!!).single()
        assertThat(resetToken.resetDomain).isEqualTo(altTenant.domain)
    }

    @Test
    fun `passkey reset is not sent for password-only users`() {
        val currentUser = userRepo.findByEmail("mgmt-owner-$runId@example.com")!!

        given()
            .header("Host", tenant.domain)
            .contentType("application/json")
            .body("""{"email":"${currentUser.email}"}""")
            .`when`().post("/api/users/passkeys/forgot")
            .then()
            .statusCode(204)

        assertThat(mailbox.getMailMessagesSentTo(currentUser.email)).isEmpty()
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

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", existingUserCookie)
            .body("""{"token":"$latestToken"}""")
            .`when`().post("/api/users/${existingUser.id}/invite/accept")
            .then()
            .statusCode(200)

        entityManager.clear()
        val acceptedUser = userRepo.findById(existingUser.id)!!
        assertThat(acceptedUser.displayName).isEqualTo("searchable-$runId")
        assertThat(tenantMemberRepo.findByTenantAndUser(tenant, acceptedUser)!!.acceptedAt).isNotNull
    }

    @Test
    fun `passkey only user can accept tenant invitation without display name`() {
        val existingUser = createUser("passkey-only-$runId", "admin1234567", ServerRole.ServerMember)
        val existingUserCookie = login(existingUser.email, "admin1234567")
        inTransaction {
            val user = userRepo.findById(existingUser.id)!!
            user.passwordSetAt = null
        }
        createPasskey(existingUser, "Laptop", tenant.domain)

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .body("""{"email":"${existingUser.email}","role":"TenantMember"}""")
            .`when`().post("/api/tenants/${tenant.name}/users/invite")
            .then()
            .statusCode(200)
            .body("status", equalTo("Pending"))

        val token = mailbox.getMailMessagesSentTo(existingUser.email)
            .single()
            .text
            .substringAfter("/invite/${existingUser.id}#")
            .trim()

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", existingUserCookie)
            .body("""{"token":"$token"}""")
            .`when`().post("/api/users/${existingUser.id}/invite/accept")
            .then()
            .statusCode(200)

        entityManager.clear()
        assertThat(tenantMemberRepo.findByTenantAndUser(tenant, userRepo.findById(existingUser.id)!!)!!.acceptedAt).isNotNull
    }

    @Test
    fun `accepting invitation with passkey does not require passkey display name`() {
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
                  "token": "$token",
                  "credential": ${passkeyRegistrationJson()}
                }
                """.trimIndent(),
            )
            .`when`().post("/api/users/$invitedUserId/invite/passkey/accept")
            .then()
            .statusCode(400)
            .body("message", equalTo("Invalid passkey registration"))
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
    fun `server admin disables and enables user sessions`() {
        val member = createUser("disable-member-$runId", "admin1234567", ServerRole.ServerMember)
        val memberCookie = login(member.email, "admin1234567")

        given()
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .`when`().post("/api/users/${member.id}/disable")
            .then()
            .statusCode(200)
            .body("status", equalTo("Disabled"))

        given()
            .cookie("VEYQ_ACCESS_TOKEN", memberCookie)
            .`when`().get("/api/users/me")
            .then()
            .statusCode(401)

        given()
            .contentType("application/json")
            .body("""{"email":"${member.email}","password":"admin1234567"}""")
            .`when`().post("/api/auth/login")
            .then()
            .statusCode(401)

        given()
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .`when`().post("/api/users/${member.id}/enable")
            .then()
            .statusCode(200)
            .body("status", equalTo("Active"))

        login(member.email, "admin1234567")
    }

    @Test
    fun `server admin cannot disable its own account`() {
        val owner = userRepo.findByEmail("mgmt-owner-$runId@example.com")!!

        given()
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .`when`().post("/api/users/${owner.id}/disable")
            .then()
            .statusCode(409)
    }

    @Test
    fun `user deletes own account with password anonymizes visible references deletes private content and releases email`() {
        val user = createUser("delete-member-$runId", "admin1234567", ServerRole.ServerMember)
        addTenantMember(user, tenant, TenantRole.TenantMember)
        val userCookie = login(user.email, "admin1234567")
        val originalEmail = user.email
        val content = createLifecycleContent(user)

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", userCookie)
            .body("""{"password":"admin1234567"}""")
            .`when`().post("/api/users/me/delete")
            .then()
            .statusCode(204)

        entityManager.clear()
        val deletedUser = userRepo.findById(user.id)!!
        val replacement = userRepo.deletedUserReplacement()!!

        assertThat(deletedUser.deletedAt).isNotNull()
        assertThat(deletedUser.deletedReplacementUser?.id).isEqualTo(replacement.id)
        assertThat(deletedUser.email).isNotEqualTo(originalEmail)
        assertThat(deletedUser.email).startsWith("deleted-user-${user.id}@")
        assertThat(deletedUser.passwordSetAt).isNull()
        assertThat(tenantMemberRepo.findByTenantAndUser(tenant, deletedUser)).isNull()

        assertThat(nativeCount("veyq.veyq_memory", content.privateMemoryId)).isZero()
        assertThat(nativeCount("veyq.veyq_tenant_folder", content.privateFolderId)).isZero()
        assertThat(nativeCount("veyq.veyq_media", content.privateMediaId)).isZero()
        assertThat(nativeCount("veyq.veyq_memory_track", content.privateTrackId)).isZero()
        assertThat(nativeCount("veyq.veyq_memory_playlist", content.privatePlaylistId)).isZero()
        assertThat(nativeCount("veyq.veyq_memory_participant", content.removedParticipantId)).isZero()

        assertThat(nativeUserReference("veyq.veyq_memory", content.visibleMemoryId, "owner_user_id")).isEqualTo(replacement.id)
        assertThat(nativeUserReference("veyq.veyq_tenant_folder", content.visibleFolderId, "owner_user_id")).isEqualTo(replacement.id)
        assertThat(nativeUserReference("veyq.veyq_media", content.visibleMediaId, "uploader_user_id")).isEqualTo(replacement.id)
        assertThat(nativeUserReference("veyq.veyq_memory_media_bucket", content.visibleBucketId, "uploader_user_id")).isEqualTo(replacement.id)
        assertThat(nativeUserReference("veyq.veyq_memory_playlist", content.visiblePlaylistId, "created_by_user_id")).isEqualTo(replacement.id)
        assertThat(nativeUserReference("veyq.veyq_memory_participant", content.visibleParticipantId, "added_by_user_id")).isEqualTo(replacement.id)

        given()
            .contentType("application/json")
            .body("""{"email":"$originalEmail","password":"admin1234567"}""")
            .`when`().post("/api/auth/login")
            .then()
            .statusCode(401)

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .body("""{"email":"$originalEmail","displayName":"Replacement","serverRole":"ServerMember"}""")
            .`when`().post("/api/users")
            .then()
            .statusCode(200)
            .body("email", equalTo(originalEmail))
    }

    @Test
    fun `last server owner cannot delete own account`() {
        val owner = createIsolatedOwner("delete-single-owner-$runId")
        val singleOwnerCookie = login(owner.email, "admin1234567")

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", singleOwnerCookie)
            .body("""{"password":"admin1234567"}""")
            .`when`().post("/api/users/me/delete")
            .then()
            .statusCode(409)
    }

    @Test
    fun `last tenant owner cannot delete own account`() {
        val secondOwner = createUser("delete-second-owner-$runId", "admin1234567", ServerRole.ServerOwner)
        val tenantOwner = createUser("delete-tenant-owner-$runId", "admin1234567", ServerRole.ServerMember)
        val ownerOnlyTenant = createTenant("delete-owner-tenant-$runId", "Delete Owner Tenant", "delete-owner-tenant-$runId.example.test", tenantOwner)
        val tenantOwnerCookie = login(tenantOwner.email, "admin1234567")

        assertThat(secondOwner.id).isNotZero()
        assertThat(ownerOnlyTenant.id).isNotZero()

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", tenantOwnerCookie)
            .body("""{"password":"admin1234567"}""")
            .`when`().post("/api/users/me/delete")
            .then()
            .statusCode(409)
    }

    @Test
    fun `tenant owner soft removes user and reinvite restores pending membership`() {
        val member = createUser("removed-member-$runId", "admin1234567", ServerRole.ServerMember)
        addTenantMember(member, tenant, TenantRole.TenantMember)

        given()
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .`when`().delete("/api/tenants/${tenant.name}/users/${member.id}")
            .then()
            .statusCode(204)

        entityManager.clear()
        val removedMembership = tenantMemberRepo.findByTenantAndUser(tenant, member)!!
        assertThat(removedMembership.status).isEqualTo(TenantMemberStatus.Removed)
        assertThat(removedMembership.removedAt).isNotNull()

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .body("""{"email":"${member.email}","role":"TenantMember"}""")
            .`when`().post("/api/tenants/${tenant.name}/users/invite")
            .then()
            .statusCode(200)
            .body("status", equalTo("Pending"))

        entityManager.clear()
        val pendingMembership = tenantMemberRepo.findByTenantAndUser(tenant, member)!!
        assertThat(pendingMembership.status).isEqualTo(TenantMemberStatus.Pending)
        assertThat(pendingMembership.removedAt).isNull()
    }

    @Test
    fun `tenant owner cannot soft remove the last tenant owner`() {
        val owner = userRepo.findByEmail("mgmt-owner-$runId@example.com")!!

        given()
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .`when`().delete("/api/tenants/${tenant.name}/users/${owner.id}")
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
            .body("""{"name":"duplicate-domain-$runId","displayName":"Duplicate","domain":"${tenant.domain}","ownerEmail":"duplicate-owner-$runId@example.com"}""")
            .`when`().post("/api/tenants")
            .then()
            .statusCode(409)

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .body("""{"name":"localhost-domain-$runId","displayName":"Localhost","domain":"localhost:8081","ownerEmail":"localhost-owner-$runId@example.com"}""")
            .`when`().post("/api/tenants")
            .then()
            .statusCode(200)
            .body("domain", equalTo("localhost:8081"))
    }

    @Test
    fun `tenant creation activates tenant when invited owner accepts`() {
        val tenantName = "invited-owner-$runId"
        val invitedEmail = "invited-owner-$runId@example.com"

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .body("""{"name":"$tenantName","displayName":"Invited Owner","domain":"$tenantName.example.test","ownerEmail":"$invitedEmail"}""")
            .`when`().post("/api/tenants")
            .then()
            .statusCode(200)
            .body("status", equalTo("Pending"))
            .body("invitedOwnerEmail", equalTo(invitedEmail))

        val invitedTenant = tenantRepo.findByName(tenantName) ?: throw IllegalStateException("Tenant not found")
        val invitedUser = userRepo.findByEmail(invitedEmail) ?: throw IllegalStateException("Invited user not found")
        assertThat(invitedTenant.activatedAt).isNull()
        assertThat(tenantMemberRepo.findByTenantAndUser(invitedTenant, userRepo.findByEmail("mgmt-owner-$runId@example.com")!!)).isNull()

        given()
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .`when`().get("/api/tenants")
            .then()
            .statusCode(200)
            .body("tenants.name", org.hamcrest.Matchers.not(hasItem(tenantName)))

        val token = mailbox.getMailMessagesSentTo(invitedEmail)
            .single()
            .text
            .substringAfter("/invite/${invitedUser.id}#")
            .trim()

        val invitedOwnerCookie = given()
            .contentType("application/json")
            .body("""{"token":"$token","displayName":"Invited Owner","password":"admin1234567"}""")
            .`when`().post("/api/users/${invitedUser.id}/invite/accept")
            .then()
            .statusCode(200)
            .extract()
            .cookie("VEYQ_ACCESS_TOKEN")

        entityManager.clear()
        assertThat(tenantRepo.findByName(tenantName)?.activatedAt).isNotNull()

        given()
            .cookie("VEYQ_ACCESS_TOKEN", invitedOwnerCookie)
            .`when`().get("/api/tenants")
            .then()
            .statusCode(200)
            .body("tenants.name", hasItem(tenantName))
            .body("tenants.role", hasItem("TenantOwner"))
            .body("tenants.admins.email.flatten()", hasItem(invitedEmail))
    }

    private data class LifecycleContent(
        val visibleMemoryId: Long,
        val privateMemoryId: Long,
        val visibleFolderId: Long,
        val privateFolderId: Long,
        val visibleBucketId: Long,
        val visibleMediaId: Long,
        val privateMediaId: Long,
        val privateTrackId: Long,
        val visiblePlaylistId: Long,
        val privatePlaylistId: Long,
        val visibleParticipantId: Long,
        val removedParticipantId: Long,
    )

    private fun createLifecycleContent(user: User): LifecycleContent =
        inTransaction {
            val managedUser = userRepo.findById(user.id)!!
            val owner = userRepo.findByEmail("mgmt-owner-$runId@example.com")!!
            val visibleFolder = TenantFolder().also {
                it.tenant = tenant
                it.owner = managedUser
                it.scopeType = LibraryScopeType.Tenant
                it.name = "visible-folder-$runId"
                it.displayName = "Visible Folder"
                entityManager.persist(it)
            }
            val privateFolder = TenantFolder().also {
                it.tenant = tenant
                it.owner = managedUser
                it.scopeType = LibraryScopeType.User
                it.scopeUser = managedUser
                it.name = "private-folder-$runId"
                it.displayName = "Private Folder"
                entityManager.persist(it)
            }
            val visibleMemory = Memory().also {
                it.tenant = tenant
                it.owner = managedUser
                it.scopeType = LibraryScopeType.Tenant
                it.name = "visible-memory-$runId"
                it.displayName = "Visible Memory"
                it.startDate = LocalDate.now()
                it.endDate = LocalDate.now()
                entityManager.persist(it)
            }
            val privateMemory = Memory().also {
                it.tenant = tenant
                it.owner = managedUser
                it.scopeType = LibraryScopeType.User
                it.scopeUser = managedUser
                it.name = "private-memory-$runId"
                it.displayName = "Private Memory"
                it.startDate = LocalDate.now()
                it.endDate = LocalDate.now()
                entityManager.persist(it)
            }
            entityManager.flush()

            val visibleBucket = MemoryMediaBucket().also {
                it.tenant = tenant
                it.memory = visibleMemory
                it.name = "visible-bucket-$runId"
                it.displayName = "Visible Bucket"
                it.uploaderUser = managedUser
                entityManager.persist(it)
            }
            val blob = MediaBlob().also {
                it.tenant = tenant
                it.contentSha256 = "0".repeat(56) + runId
                it.storageKey = "test/$runId/blob"
                it.mimeType = "image/jpeg"
                it.sizeBytes = 1
                entityManager.persist(it)
            }
            entityManager.flush()

            val visibleMedia = Media().also {
                it.tenant = tenant
                it.scopeType = LibraryScopeType.Tenant
                it.memory = visibleMemory
                it.bucket = visibleBucket
                it.title = "Visible Media"
                it.blob = blob
                it.uploaderUser = managedUser
                entityManager.persist(it)
            }
            val privateMedia = Media().also {
                it.tenant = tenant
                it.scopeType = LibraryScopeType.User
                it.scopeUser = managedUser
                it.memory = privateMemory
                it.title = "Private Media"
                it.blob = blob
                it.uploaderUser = managedUser
                entityManager.persist(it)
            }
            val privateTrack = MemoryTrack().also {
                it.tenant = tenant
                it.memory = privateMemory
                it.scopeType = LibraryScopeType.User
                it.scopeUser = managedUser
                it.title = "Private Track"
                it.activityType = MemoryTrackActivityType.HIKING
                it.originalFilename = "track.gpx"
                it.storageKey = "test/$runId/track.gpx"
                it.geometryJson = """{"type":"LineString","coordinates":[]}"""
                entityManager.persist(it)
            }
            val visiblePlaylist = MemoryPlaylist().also {
                it.tenant = tenant
                it.memory = visibleMemory
                it.scopeType = LibraryScopeType.Tenant
                it.title = "Visible Playlist"
                it.provider = MemoryPlaylistProvider.SPOTIFY
                it.url = "https://open.spotify.com/playlist/$runId"
                it.externalId = runId
                it.createdByUser = managedUser
                entityManager.persist(it)
            }
            val privatePlaylist = MemoryPlaylist().also {
                it.tenant = tenant
                it.memory = privateMemory
                it.scopeType = LibraryScopeType.User
                it.scopeUser = managedUser
                it.title = "Private Playlist"
                it.provider = MemoryPlaylistProvider.SPOTIFY
                it.url = "https://open.spotify.com/playlist/private-$runId"
                it.externalId = "private-$runId"
                it.createdByUser = managedUser
                entityManager.persist(it)
            }
            val visibleParticipant = MemoryParticipant().also {
                it.tenant = tenant
                it.memory = visibleMemory
                it.user = owner
                it.addedBy = managedUser
                entityManager.persist(it)
            }
            val removedParticipant = MemoryParticipant().also {
                it.tenant = tenant
                it.memory = visibleMemory
                it.user = managedUser
                it.addedBy = owner
                entityManager.persist(it)
            }
            MemoryFavorite().also {
                it.tenant = tenant
                it.memory = visibleMemory
                it.user = managedUser
                entityManager.persist(it)
            }
            entityManager.flush()

            LifecycleContent(
                visibleMemoryId = visibleMemory.id,
                privateMemoryId = privateMemory.id,
                visibleFolderId = visibleFolder.id,
                privateFolderId = privateFolder.id,
                visibleBucketId = visibleBucket.id,
                visibleMediaId = visibleMedia.id,
                privateMediaId = privateMedia.id,
                privateTrackId = privateTrack.id,
                visiblePlaylistId = visiblePlaylist.id,
                privatePlaylistId = privatePlaylist.id,
                visibleParticipantId = visibleParticipant.id,
                removedParticipantId = removedParticipant.id,
            )
        }

    private fun nativeCount(tableName: String, id: Long): Long =
        (entityManager.createNativeQuery("select count(*) from $tableName where id = :id")
            .setParameter("id", id)
            .singleResult as Number).toLong()

    private fun nativeUserReference(tableName: String, id: Long, columnName: String): Long =
        (entityManager.createNativeQuery("select $columnName from $tableName where id = :id")
            .setParameter("id", id)
            .singleResult as Number).toLong()

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

    private fun createPasskey(user: User, displayName: String, relyingPartyId: String? = null): PasskeyCredential =
        inTransaction {
            PasskeyCredential().also {
                it.user = userRepo.findById(user.id)!!
                it.credentialId = "credential-$runId-${displayName.lowercase()}"
                it.publicKey = "public-key-$runId".toByteArray()
                it.publicKeyAlgorithm = -7
                it.counter = 0
                it.aaguid = "00000000-0000-0000-0000-000000000000"
                it.displayName = displayName
                it.relyingPartyId = relyingPartyId
                it.createdAt = Instant.now()
                passkeyCredentialRepo.persist(it)
            }
        }

    private fun createTotpCredential(user: User): TotpCredential =
        inTransaction {
            TotpCredential().also {
                it.user = userRepo.findById(user.id)!!
                it.secret = "JBSWY3DPEHPK3PXP"
                it.createdAt = Instant.now()
                it.enabledAt = Instant.now()
                it.lastUsedAt = null
                it.lastUsedStep = null
                totpCredentialRepo.persist(it)
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

    private fun deleteMyPasskeyJson(): String =
        """
        {
          "passkey": {
            "id": "credential-id",
            "rawId": "credential-id",
            "type": "public-key",
            "response": {
              "clientDataJSON": "client-data",
              "authenticatorData": "authenticator-data",
              "signature": "signature",
              "userHandle": "user-handle"
            }
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
                it.activatedAt = java.time.Instant.now()
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

    private fun createPasskeyLink(targetDomain: String, redirectPath: String = "/${targetDomain.substringBefore(".")}"): String =
        given()
            .header("Host", tenant.domain)
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .contentType("application/json")
            .body("""{"targetOrigin":"https://$targetDomain","redirectPath":"$redirectPath"}""")
            .`when`().post("/api/auth/passkeys/link")
            .then()
            .statusCode(200)
            .body("targetOrigin", equalTo("https://$targetDomain"))
            .extract()
            .path<String>("code")

    private fun exchangePasskeyLink(code: String, targetDomain: String): String =
        given()
            .header("Host", targetDomain)
            .contentType("application/json")
            .body("""{"code":"$code"}""")
            .`when`().post("/api/auth/passkeys/link/exchange")
            .then()
            .statusCode(200)
            .extract()
            .path<String>("grant")

    private fun createAvatarImage(): Path =
        Files.createTempFile("veyq-avatar-", ".png").also { path ->
            ImageIO.write(BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB), "png", path.toFile())
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
