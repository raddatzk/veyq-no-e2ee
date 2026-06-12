package com.veyq.api.settings

import com.veyq.api.auth.PasswordService
import com.veyq.api.domain.ServerRole
import com.veyq.api.domain.User
import com.veyq.api.domain.UserRepo
import com.veyq.api.setup.ServerState
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import jakarta.transaction.UserTransaction
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.empty
import org.hamcrest.Matchers.endsWith
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

@QuarkusTest
class SettingsResourceTest {
    @Inject lateinit var userRepo: UserRepo
    @Inject lateinit var passwordService: PasswordService
    @Inject lateinit var transaction: UserTransaction
    @Inject lateinit var serverState: ServerState

    private lateinit var runId: String
    private lateinit var ownerCookie: String
    private lateinit var memberCookie: String

    @BeforeEach
    fun setUp() {
        runId = UUID.randomUUID().toString().take(8)
        val owner = createUser("settings-owner-$runId", ServerRole.ServerOwner)
        val member = createUser("settings-member-$runId", ServerRole.ServerMember)
        ownerCookie = login(owner)
        memberCookie = login(member)
    }

    @Test
    fun `server owner can load server settings endpoints`() {
        given()
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .`when`().get("/api/settings/server")
            .then()
            .statusCode(200)
            .body("displayName", equalTo("Server"))
            .body("userManagementAvailable", equalTo(true))
            .body("license.edition", equalTo("COMMUNITY"))

        given()
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .`when`().get("/api/settings/server/notifications")
            .then()
            .statusCode(200)
            .body("notifications", empty<Any>())

        given()
            .cookie("VEYQ_ACCESS_TOKEN", ownerCookie)
            .`when`().get("/api/settings/server/update-events")
            .then()
            .statusCode(200)
            .body("events", empty<Any>())
    }

    @Test
    fun `public discovery exposes mobile login and client capabilities`() {
        given()
            .`when`().get("/.well-known/veyq")
            .then()
            .statusCode(200)
            .body("product", equalTo("veyq"))
            .body("apiBaseUrl", endsWith("/api"))
            .body("clientCapabilitiesUrl", endsWith("/api/client-capabilities"))
            .body("mobileLoginUrl", endsWith("/login?handoff=mobile"))
            .body("mobileCallbackScheme", equalTo("veyq"))

        given()
            .`when`().get("/api/client-capabilities")
            .then()
            .statusCode(200)
            .body("capabilities.id", org.hamcrest.Matchers.hasItems("auth.mobile-handoff", "memory.core"))
    }

    @Test
    fun `public discovery uses the request host for mobile login`() {
        given()
            .header("Host", "tenant-a.example.test")
            .`when`().get("/.well-known/veyq")
            .then()
            .statusCode(200)
            .body("apiBaseUrl", equalTo("http://tenant-a.example.test/api"))
            .body("mobileLoginUrl", equalTo("http://tenant-a.example.test/login?handoff=mobile"))
    }

    @Test
    fun `server settings endpoints reject unauthenticated requests`() {
        serverSettingsPaths().forEach { path ->
            given()
                .`when`().get(path)
                .then()
                .statusCode(401)
        }
    }

    @Test
    fun `server settings endpoints reject server members`() {
        serverSettingsPaths().forEach { path ->
            given()
                .cookie("VEYQ_ACCESS_TOKEN", memberCookie)
                .`when`().get(path)
                .then()
                .statusCode(403)
        }
    }

    private fun serverSettingsPaths(): List<String> =
        listOf(
            "/api/settings/server",
            "/api/settings/server/notifications",
            "/api/settings/server/update-events",
        )

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
        }.also {
            serverState.markSetupCompleted()
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
