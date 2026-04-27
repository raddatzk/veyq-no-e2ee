package com.lunva.api.bootstrap

import com.lunva.api.auth.PasswordService
import com.lunva.api.domain.ServerRole
import com.lunva.api.domain.TenantRole
import com.lunva.api.domain.User
import com.lunva.api.domain.UserRepo
import com.lunva.api.setup.SetupFilter
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.UserTransaction
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertFalse

@QuarkusTest
class BootstrapResourceTest {
    @Inject lateinit var entityManager: EntityManager
    @Inject lateinit var passwordService: PasswordService
    @Inject lateinit var userRepo: UserRepo
    @Inject lateinit var setupFilter: SetupFilter
    @Inject lateinit var transaction: UserTransaction

    private lateinit var runId: String

    @BeforeEach
    fun cleanDatabase() {
        runId = UUID.randomUUID().toString().take(8)
        inTransaction {
            entityManager.createNativeQuery("set referential_integrity false").executeUpdate()
            listOf(
                "lunva_media_group_share",
                "lunva_media_user_share",
                "lunva_media",
                "lunva_tenant_group_member",
                "lunva_tenant_group",
                "lunva_tenant_member",
                "lunva_tenant",
                "lunva_access_token",
                "lunva_refresh_token",
                "lunva_user",
                "lunva_config",
            ).forEach { table ->
                entityManager.createNativeQuery("delete from $table").executeUpdate()
            }
            entityManager.createNativeQuery("set referential_integrity true").executeUpdate()
        }
        setupFilter.refreshSetupStatus()
    }

    @Test
    fun `api requests report setup required on empty instance`() {
        given()
            .`when`().get("/api/tenants")
            .then()
            .statusCode(503)
            .body("error", equalTo("Setup required"))
    }

    @Test
    fun `bootstrap requires authentication after setup without cookie`() {
        createOwner()

        given()
            .`when`().get("/api/bootstrap")
            .then()
            .statusCode(401)
    }

    @Test
    fun `bootstrap returns current user and tenants with valid cookie`() {
        val email = createOwner()
        val cookie = login(email, "admin123")

        given()
            .contentType("application/json")
            .cookie("LUNVA_ACCESS_TOKEN", cookie)
            .body("""{"name":"friends-$runId","displayName":"Friends"}""")
            .`when`().post("/api/tenants")
            .then()
            .statusCode(200)

        given()
            .cookie("LUNVA_ACCESS_TOKEN", cookie)
            .`when`().get("/api/bootstrap")
            .then()
            .statusCode(200)
            .body("adminRequired", equalTo(false))
            .body("user.email", equalTo(email))
            .body("user.serverRole", equalTo(ServerRole.SERVER_OWNER.name))
            .body("tenants.name", hasItem("friends-$runId"))
            .body("tenants.displayName", hasItem("Friends"))
            .body("tenants.role", hasItem(TenantRole.TENANT_OWNER.name))
    }

    @Test
    fun `bootstrap rejects invalid cookie after setup`() {
        createOwner()

        given()
            .cookie("LUNVA_ACCESS_TOKEN", "not-a-valid-token")
            .`when`().get("/api/bootstrap")
            .then()
            .statusCode(401)
    }

    @Test
    fun `spa fallback serves frontend routes but not api routes`() {
        val email = createOwner()
        val cookie = login(email, "admin123")

        given()
            .`when`().get("/login")
            .then()
            .statusCode(200)
            .contentType(containsString("text/html"))
            .body(containsString("""<div id="app"></div>"""))

        given()
            .`when`().get("/some-tenant")
            .then()
            .statusCode(200)
            .contentType(containsString("text/html"))
            .body(containsString("""<div id="app"></div>"""))

        given()
            .cookie("LUNVA_ACCESS_TOKEN", cookie)
            .`when`().get("/api/unknown")
            .then()
            .statusCode(404)
            .contentType(not(containsString("text/html")))
    }

    private fun createOwner(): String {
        val email = "owner-$runId@example.com"
        inTransaction {
            User().also {
                it.passwordHash = passwordService.hash("admin123")
                it.displayName = "Owner $runId"
                it.email = email
                it.role = ServerRole.SERVER_OWNER
                userRepo.persist(it)
            }
        }
        setupFilter.markSetupCompleted()
        return email
    }

    private fun login(email: String, password: String): String {
        val accessCookie = given()
            .contentType("application/json")
            .body("""{"email":"$email","password":"$password"}""")
            .`when`().post("/api/auth/login")
            .then()
            .statusCode(200)
            .extract()
            .cookie("LUNVA_ACCESS_TOKEN")

        assertFalse(accessCookie.isNullOrBlank(), "Login did not return an access cookie")
        return accessCookie
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
