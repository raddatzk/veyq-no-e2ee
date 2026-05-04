package com.lunva.api.auth

import com.lunva.api.domain.AccessTokenRepo
import com.lunva.api.domain.RefreshTokenRepo
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@QuarkusTest
class AuthResourceTest {
    @Inject
    lateinit var accessTokenRepo: AccessTokenRepo
    @Inject
    lateinit var refreshTokenRepo: RefreshTokenRepo

    @BeforeEach
    fun ensureAdmin() {
        val loginResponse = given()
            .contentType("application/json")
            .body("""{"email":"kevin@raddatz.me","password":"admin123"}""")
            .`when`().post("/api/auth/login")

        if (loginResponse.statusCode == 200) return

        val setupResponse = given()
            .contentType("application/json")
            .body("""{"password":"admin123","displayName":"Administrator","email":"kevin@raddatz.me"}""")
            .`when`().post("/api/setup/admin")

        if (setupResponse.statusCode != 200 && setupResponse.statusCode != 409) {
            throw AssertionError("Admin setup failed with ${setupResponse.statusCode}: ${setupResponse.body.asString()}")
        }
    }

    @Test
    fun `login succeeds and sets access and refresh cookies`() {
        val response = given()
            .contentType("application/json")
            .body("""{"email":"kevin@raddatz.me","password":"admin123"}""")
            .`when`().post("/api/auth/login")

        assertThat(response.statusCode)
            .describedAs("Login failed with body: ${response.body.asString()}")
            .isEqualTo(200)

        response
            .then()
            .cookie("LUNVA_ACCESS_TOKEN", notNullValue())
            .cookie("LUNVA_REFRESH_TOKEN", notNullValue())
            .body("email", equalTo("kevin@raddatz.me"))
            .body("displayName", equalTo("Administrator"))
    }

    @Test
    fun `login fails for wrong password`() {
        given()
            .contentType("application/json")
            .body("""{"email":"kevin@raddatz.me","password":"nope"}""")
            .`when`().post("/api/auth/login")
            .then()
            .statusCode(401)
    }

    @Test
    fun `me requires authentication`() {
        given()
            .`when`().get("/api/users/me")
            .then()
            .statusCode(401)
    }

    @Test
    fun `me returns current user with access cookie`() {
        val accessCookie = given().contentType("application/json")
            .body("""{"email":"kevin@raddatz.me","password":"admin123"}""")
            .`when`().post("/api/auth/login")
            .then().statusCode(200)
            .extract()
            .cookie("LUNVA_ACCESS_TOKEN")
        assertThat(accessCookie)
            .describedAs("Login did not return an access cookie")
            .isNotBlank()

        val meResponse = given()
            .cookie("LUNVA_ACCESS_TOKEN", accessCookie)
            .`when`().get("/api/users/me")

        assertThat(meResponse.statusCode)
            .describedAs("GET /api/users/me failed with body: ${meResponse.body.asString()}")
            .isEqualTo(200)

        meResponse
            .then()
            .body("email", equalTo("kevin@raddatz.me"))
    }

    @Test
    fun `refresh rotates refresh token and returns new cookies`() {
        val login = given()
            .contentType("application/json")
            .body("""{"email":"kevin@raddatz.me","password":"admin123"}""")
            .`when`().post("/api/auth/login")
            .then()
            .statusCode(200)
            .extract()

        val oldRefreshCookie = login.cookie("LUNVA_REFRESH_TOKEN")
        assertThat(oldRefreshCookie)
            .describedAs("Login did not return a refresh cookie")
            .isNotBlank()

        val refreshResponse = given()
            .contentType("application/json")
            .cookie("LUNVA_REFRESH_TOKEN", oldRefreshCookie)
            .`when`().post("/api/auth/refresh")

        assertThat(refreshResponse.statusCode)
            .describedAs("POST /api/auth/refresh failed with body: ${refreshResponse.body.asString()}")
            .isEqualTo(200)

        val newRefreshCookie = refreshResponse.getCookie("LUNVA_REFRESH_TOKEN")
        val newAccessCookie = refreshResponse.getCookie("LUNVA_ACCESS_TOKEN")
        assertThat(newRefreshCookie)
            .describedAs("Refresh did not return new refresh cookie")
            .isNotBlank()
        assertThat(newAccessCookie)
            .describedAs("Refresh did not return new access cookie")
            .isNotBlank()
        assertThat(newRefreshCookie)
            .describedAs("Refresh token should be rotated")
            .isNotEqualTo(oldRefreshCookie)

        val oldRefreshTokenEntity = refreshTokenRepo.findByTokenHash(hash(oldRefreshCookie))
        val newRefreshTokenEntity = refreshTokenRepo.findByTokenHash(hash(newRefreshCookie))
        assertThat(oldRefreshTokenEntity).isNotNull
        assertThat(newRefreshTokenEntity).isNotNull
        assertThat(oldRefreshTokenEntity!!.rotatedTo?.id)
            .describedAs("Old refresh token must point to successor")
            .isEqualTo(newRefreshTokenEntity!!.id)
    }

    @Test
    fun `logout invalidates current access token`() {
        val login = given()
            .contentType("application/json")
            .body("""{"email":"kevin@raddatz.me","password":"admin123"}""")
            .`when`().post("/api/auth/login")
            .then()
            .statusCode(200)
            .extract()

        val accessCookie = login.cookie("LUNVA_ACCESS_TOKEN")
        val refreshCookie = login.cookie("LUNVA_REFRESH_TOKEN")
        assertThat(accessCookie)
            .describedAs("Login did not return an access cookie")
            .isNotBlank()
        assertThat(refreshCookie)
            .describedAs("Login did not return a refresh cookie")
            .isNotBlank()

        given()
            .contentType("application/json")
            .cookie("LUNVA_ACCESS_TOKEN", accessCookie)
            .cookie("LUNVA_REFRESH_TOKEN", refreshCookie)
            .`when`().post("/api/auth/logout")
            .then()
            .statusCode(204)

        given()
            .cookie("LUNVA_ACCESS_TOKEN", accessCookie)
            .`when`().get("/api/users/me")
            .then()
            .statusCode(401)
    }

    private fun hash(value: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

}
