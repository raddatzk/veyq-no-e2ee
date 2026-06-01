package com.veyq.api.auth

import com.veyq.api.domain.AccessTokenRepo
import com.veyq.api.domain.MobileSessionCodeRepo
import com.veyq.api.domain.RefreshTokenRepo
import com.veyq.api.domain.TotpCredentialRepo
import com.veyq.api.security.RateLimitService
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.filter.cookie.CookieFilter
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import jakarta.inject.Inject
import jakarta.transaction.UserTransaction
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.time.Instant
import kotlin.math.pow

@QuarkusTest
class AuthResourceTest {
    @Inject
    lateinit var accessTokenRepo: AccessTokenRepo
    @Inject
    lateinit var refreshTokenRepo: RefreshTokenRepo
    @Inject
    lateinit var mobileSessionCodeRepo: MobileSessionCodeRepo
    @Inject
    lateinit var totpCredentialRepo: TotpCredentialRepo
    @Inject
    lateinit var rateLimitService: RateLimitService
    @Inject
    lateinit var transaction: UserTransaction

    @BeforeEach
    fun ensureAdmin() {
        rateLimitService.clearAll()
        transaction.begin()
        totpCredentialRepo.deleteAll()
        transaction.commit()
        val loginResponse = given()
            .contentType("application/json")
            .body("""{"email":"kevin@raddatz.me","password":"admin1234567"}""")
            .`when`().post("/api/auth/login")

        if (loginResponse.statusCode == 200) return

        val setupResponse = given()
            .contentType("application/json")
            .body("""{"password":"admin1234567","displayName":"Administrator","email":"kevin@raddatz.me"}""")
            .`when`().post("/api/setup/admin")

        if (setupResponse.statusCode != 200 && setupResponse.statusCode != 409) {
            throw AssertionError("Admin setup failed with ${setupResponse.statusCode}: ${setupResponse.body.asString()}")
        }
    }

    @Test
    fun `login succeeds and sets access and refresh cookies`() {
        val response = given()
            .contentType("application/json")
            .body("""{"email":"kevin@raddatz.me","password":"admin1234567"}""")
            .`when`().post("/api/auth/login")

        assertThat(response.statusCode)
            .describedAs("Login failed with body: ${response.body.asString()}")
            .isEqualTo(200)

        response
            .then()
            .cookie("VEYQ_ACCESS_TOKEN", notNullValue())
            .cookie("VEYQ_REFRESH_TOKEN", notNullValue())
            .body("user.email", equalTo("kevin@raddatz.me"))
            .body("user.displayName", equalTo("Administrator"))
            .body("accessTokenExpiresAt", notNullValue())
    }

    @Test
    fun `login fails for wrong password`() {
        given()
            .contentType("application/json")
            .body("""{"email":"kevin@raddatz.me","password":"wrong1234567"}""")
            .`when`().post("/api/auth/login")
            .then()
            .statusCode(401)
    }

    @Test
    fun `login failures are rate limited with retry after header`() {
        repeat(5) {
            given()
                .contentType("application/json")
                .body("""{"email":"kevin@raddatz.me","password":"wrong1234567"}""")
                .`when`().post("/api/auth/login")
                .then()
                .statusCode(401)
        }

        given()
            .contentType("application/json")
            .body("""{"email":"kevin@raddatz.me","password":"wrong1234567"}""")
            .`when`().post("/api/auth/login")
            .then()
            .statusCode(429)
            .header("Retry-After", notNullValue())
            .body("code", equalTo("RATE_LIMITED"))
            .body("message", equalTo("Too many requests"))
    }

    @Test
    fun `password login requires totp code when enabled`() {
        val accessCookie = given()
            .contentType("application/json")
            .body("""{"email":"kevin@raddatz.me","password":"admin1234567"}""")
            .`when`().post("/api/auth/login")
            .then()
            .statusCode(200)
            .body("user.totpEnabled", equalTo(false))
            .extract()
            .cookie("VEYQ_ACCESS_TOKEN")

        val enrollment = given()
            .cookie("VEYQ_ACCESS_TOKEN", accessCookie)
            .`when`().post("/api/auth/totp/enrollment")
            .then()
            .statusCode(200)
            .body("provisioningUri", notNullValue())
            .body("provisioningUri", org.hamcrest.Matchers.startsWith("otpauth://totp/veyq:kevin%40raddatz.me?"))
            .body("digits", equalTo(6))
            .body("periodSeconds", equalTo(30))
            .extract()
        val secret = enrollment.path<String>("secret")
        assertThat(enrollment.path<String>("provisioningUri"))
            .contains("secret=$secret")
            .doesNotContain("veyq%3A")

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", accessCookie)
            .body("""{"password":"admin1234567","secret":"$secret","code":"${totpCode(secret)}"}""")
            .`when`().post("/api/auth/totp/enable")
            .then()
            .statusCode(200)
            .body("email", equalTo("kevin@raddatz.me"))
            .body("totpEnabled", equalTo(true))

        given()
            .contentType("application/json")
            .body("""{"email":"kevin@raddatz.me","password":"admin1234567"}""")
            .`when`().post("/api/auth/login")
            .then()
            .statusCode(401)
            .body("code", equalTo("TOTP_REQUIRED"))

        given()
            .contentType("application/json")
            .body("""{"email":"kevin@raddatz.me","password":"admin1234567","totpCode":"000000"}""")
            .`when`().post("/api/auth/login")
            .then()
            .statusCode(401)
            .body("code", equalTo("TOTP_INVALID"))

        given()
            .contentType("application/json")
            .body("""{"email":"kevin@raddatz.me","password":"admin1234567","totpCode":"${totpCode(secret, stepOffset = 1)}"}""")
            .`when`().post("/api/auth/login")
            .then()
            .statusCode(200)
            .cookie("VEYQ_ACCESS_TOKEN", notNullValue())
            .cookie("VEYQ_REFRESH_TOKEN", notNullValue())
            .body("user.totpEnabled", equalTo(true))

        given()
            .contentType("application/json")
            .body("""{"email":"kevin@raddatz.me","password":"admin1234567","totpCode":"${totpCode(secret, stepOffset = 1)}"}""")
            .`when`().post("/api/auth/login")
            .then()
            .statusCode(401)
            .body("code", equalTo("TOTP_INVALID"))
    }

    @Test
    fun `totp enable requires current password`() {
        val accessCookie = given()
            .contentType("application/json")
            .body("""{"email":"kevin@raddatz.me","password":"admin1234567"}""")
            .`when`().post("/api/auth/login")
            .then()
            .statusCode(200)
            .extract()
            .cookie("VEYQ_ACCESS_TOKEN")

        val secret = given()
            .cookie("VEYQ_ACCESS_TOKEN", accessCookie)
            .`when`().post("/api/auth/totp/enrollment")
            .then()
            .statusCode(200)
            .extract()
            .path<String>("secret")

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", accessCookie)
            .body("""{"password":"wrong1234567","secret":"$secret","code":"${totpCode(secret)}"}""")
            .`when`().post("/api/auth/totp/enable")
            .then()
            .statusCode(401)
            .body("code", equalTo("UNAUTHORIZED"))
    }

    @Test
    fun `totp can be disabled with password and current code`() {
        val accessCookie = given()
            .contentType("application/json")
            .body("""{"email":"kevin@raddatz.me","password":"admin1234567"}""")
            .`when`().post("/api/auth/login")
            .then()
            .statusCode(200)
            .extract()
            .cookie("VEYQ_ACCESS_TOKEN")

        val secret = given()
            .cookie("VEYQ_ACCESS_TOKEN", accessCookie)
            .`when`().post("/api/auth/totp/enrollment")
            .then()
            .statusCode(200)
            .extract()
            .path<String>("secret")

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", accessCookie)
            .body("""{"password":"admin1234567","secret":"$secret","code":"${totpCode(secret)}"}""")
            .`when`().post("/api/auth/totp/enable")
            .then()
            .statusCode(200)

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", accessCookie)
            .body("""{"password":"admin1234567","code":"${totpCode(secret, stepOffset = 1)}"}""")
            .`when`().post("/api/auth/totp/disable")
            .then()
            .statusCode(200)
            .body("totpEnabled", equalTo(false))

        given()
            .contentType("application/json")
            .body("""{"email":"kevin@raddatz.me","password":"admin1234567"}""")
            .`when`().post("/api/auth/login")
            .then()
            .statusCode(200)
    }

    @Test
    fun `passkey login options rotate challenge in the same cookie context`() {
        val cookieFilter = CookieFilter()

        val firstChallenge = given()
            .filter(cookieFilter)
            .`when`().post("/api/auth/passkeys/login/options")
            .then()
            .statusCode(200)
            .extract()
            .path<String>("challenge")

        val secondChallenge = given()
            .filter(cookieFilter)
            .`when`().post("/api/auth/passkeys/login/options")
            .then()
            .statusCode(200)
            .extract()
            .path<String>("challenge")

        assertThat(firstChallenge).isNotBlank()
        assertThat(secondChallenge).isNotBlank()
        assertThat(secondChallenge).isNotEqualTo(firstChallenge)
    }

    private fun totpCode(secret: String, timestamp: Instant = Instant.now(), stepOffset: Long = 0): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(base32Decode(secret), "HmacSHA1"))
        val counter = timestamp.epochSecond / 30 + stepOffset
        val hash = mac.doFinal(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(counter).array())
        val offset = hash.last().toInt() and 0x0f
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        return (binary % 10.0.pow(6).toInt()).toString().padStart(6, '0')
    }

    private fun base32Decode(secret: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var buffer = 0
        var bitsLeft = 0
        val output = mutableListOf<Byte>()
        secret.trimEnd('=').forEach { char ->
            buffer = (buffer shl 5) or alphabet.indexOf(char)
            bitsLeft += 5
            if (bitsLeft >= 8) {
                output.add(((buffer shr (bitsLeft - 8)) and 0xff).toByte())
                bitsLeft -= 8
            }
        }
        return output.toByteArray()
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
            .body("""{"email":"kevin@raddatz.me","password":"admin1234567"}""")
            .`when`().post("/api/auth/login")
            .then().statusCode(200)
            .extract()
            .cookie("VEYQ_ACCESS_TOKEN")
        assertThat(accessCookie)
            .describedAs("Login did not return an access cookie")
            .isNotBlank()

        val meResponse = given()
            .cookie("VEYQ_ACCESS_TOKEN", accessCookie)
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
            .body("""{"email":"kevin@raddatz.me","password":"admin1234567"}""")
            .`when`().post("/api/auth/login")
            .then()
            .statusCode(200)
            .extract()

        val oldRefreshCookie = login.cookie("VEYQ_REFRESH_TOKEN")
        assertThat(oldRefreshCookie)
            .describedAs("Login did not return a refresh cookie")
            .isNotBlank()

        val refreshResponse = given()
            .contentType("application/json")
            .cookie("VEYQ_REFRESH_TOKEN", oldRefreshCookie)
            .`when`().post("/api/auth/refresh")

        assertThat(refreshResponse.statusCode)
            .describedAs("POST /api/auth/refresh failed with body: ${refreshResponse.body.asString()}")
            .isEqualTo(200)

        val newRefreshCookie = refreshResponse.getCookie("VEYQ_REFRESH_TOKEN")
        val newAccessCookie = refreshResponse.getCookie("VEYQ_ACCESS_TOKEN")
        assertThat(newRefreshCookie)
            .describedAs("Refresh did not return new refresh cookie")
            .isNotBlank()
        assertThat(newAccessCookie)
            .describedAs("Refresh did not return new access cookie")
            .isNotBlank()
        refreshResponse.then()
            .body("user.email", equalTo("kevin@raddatz.me"))
            .body("accessTokenExpiresAt", notNullValue())
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
            .body("""{"email":"kevin@raddatz.me","password":"admin1234567"}""")
            .`when`().post("/api/auth/login")
            .then()
            .statusCode(200)
            .extract()

        val accessCookie = login.cookie("VEYQ_ACCESS_TOKEN")
        val refreshCookie = login.cookie("VEYQ_REFRESH_TOKEN")
        assertThat(accessCookie)
            .describedAs("Login did not return an access cookie")
            .isNotBlank()
        assertThat(refreshCookie)
            .describedAs("Login did not return a refresh cookie")
            .isNotBlank()

        given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", accessCookie)
            .cookie("VEYQ_REFRESH_TOKEN", refreshCookie)
            .`when`().post("/api/auth/logout")
            .then()
            .statusCode(204)

        given()
            .cookie("VEYQ_ACCESS_TOKEN", accessCookie)
            .`when`().get("/api/users/me")
            .then()
            .statusCode(401)
    }

    @Test
    fun `mobile handoff code exchanges once for bearer session`() {
        val login = given().contentType("application/json")
            .body("""{"email":"kevin@raddatz.me","password":"admin1234567"}""")
            .`when`().post("/api/auth/login")
            .then().statusCode(200)
            .extract()
        val accessCookie = login.cookie("VEYQ_ACCESS_TOKEN")

        val codeResponse = given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", accessCookie)
            .body("""{"redirectUri":"veyq://auth/callback","state":"ios-state"}""")
            .`when`().post("/api/auth/mobile/session/code")

        assertThat(codeResponse.statusCode)
            .describedAs("Mobile code creation failed with body: ${codeResponse.body.asString()}")
            .isEqualTo(200)
        val code = codeResponse.path<String>("code")
        assertThat(codeResponse.path<String>("redirectUri"))
            .startsWith("veyq://auth/callback?code=")
            .contains("state=ios-state")

        val session = given()
            .contentType("application/json")
            .body("""{"code":"$code"}""")
            .`when`().post("/api/auth/mobile/session/exchange")

        assertThat(session.statusCode)
            .describedAs("Mobile session exchange failed with body: ${session.body.asString()}")
            .isEqualTo(200)
        session.then()
            .body("tokenType", equalTo("Bearer"))
            .body("user.email", equalTo("kevin@raddatz.me"))

        val nativeAccessToken = session.path<String>("accessToken")
        assertThat(nativeAccessToken).isNotBlank()
        given()
            .header("Authorization", "Bearer $nativeAccessToken")
            .`when`().get("/api/frontend/bootstrap")
            .then()
            .statusCode(200)
            .body("user.email", equalTo("kevin@raddatz.me"))

        given()
            .contentType("application/json")
            .body("""{"code":"$code"}""")
            .`when`().post("/api/auth/mobile/session/exchange")
            .then()
            .statusCode(400)
    }

    @Test
    fun `mobile refresh rotates native bearer session`() {
        val accessCookie = given().contentType("application/json")
            .body("""{"email":"kevin@raddatz.me","password":"admin1234567"}""")
            .`when`().post("/api/auth/login")
            .then().statusCode(200)
            .extract()
            .cookie("VEYQ_ACCESS_TOKEN")
        val code = given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", accessCookie)
            .body("""{"redirectUri":"veyq://auth/callback"}""")
            .`when`().post("/api/auth/mobile/session/code")
            .then().statusCode(200)
            .extract().path<String>("code")
        val session = given()
            .contentType("application/json")
            .body("""{"code":"$code"}""")
            .`when`().post("/api/auth/mobile/session/exchange")
            .then().statusCode(200)
            .extract()
        val oldAccessToken = session.path<String>("accessToken")
        val oldRefreshToken = session.path<String>("refreshToken")

        val refreshed = given()
            .contentType("application/json")
            .body("""{"refreshToken":"$oldRefreshToken"}""")
            .`when`().post("/api/auth/mobile/session/refresh")

        assertThat(refreshed.statusCode)
            .describedAs("Mobile session refresh failed with body: ${refreshed.body.asString()}")
            .isEqualTo(200)
        refreshed.then()
            .body("tokenType", equalTo("Bearer"))
            .body("user.email", equalTo("kevin@raddatz.me"))
        val newAccessToken = refreshed.path<String>("accessToken")
        val newRefreshToken = refreshed.path<String>("refreshToken")
        assertThat(newAccessToken).isNotEqualTo(oldAccessToken)
        assertThat(newRefreshToken).isNotEqualTo(oldRefreshToken)

        given()
            .header("Authorization", "Bearer $oldAccessToken")
            .`when`().get("/api/frontend/bootstrap")
            .then()
            .statusCode(401)
        given()
            .header("Authorization", "Bearer $newAccessToken")
            .`when`().get("/api/frontend/bootstrap")
            .then()
            .statusCode(200)
            .body("user.email", equalTo("kevin@raddatz.me"))
        given()
            .contentType("application/json")
            .body("""{"refreshToken":"$oldRefreshToken"}""")
            .`when`().post("/api/auth/mobile/session/refresh")
            .then()
            .statusCode(401)
    }

    @Test
    fun `mobile handoff code rejects expired code`() {
        val accessCookie = given().contentType("application/json")
            .body("""{"email":"kevin@raddatz.me","password":"admin1234567"}""")
            .`when`().post("/api/auth/login")
            .then().statusCode(200)
            .extract()
            .cookie("VEYQ_ACCESS_TOKEN")

        val code = given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", accessCookie)
            .body("""{"redirectUri":"veyq://auth/callback"}""")
            .`when`().post("/api/auth/mobile/session/code")
            .then()
            .statusCode(200)
            .extract()
            .path<String>("code")

        inTransaction {
            mobileSessionCodeRepo.findByCodeHashForUpdate(hash(code))!!.expiresAt = Instant.now().minusSeconds(1)
        }

        given()
            .contentType("application/json")
            .body("""{"code":"$code"}""")
            .`when`().post("/api/auth/mobile/session/exchange")
            .then()
            .statusCode(400)
    }

    @Test
    fun `mobile logout revokes native bearer session`() {
        val accessCookie = given().contentType("application/json")
            .body("""{"email":"kevin@raddatz.me","password":"admin1234567"}""")
            .`when`().post("/api/auth/login")
            .then().statusCode(200)
            .extract()
            .cookie("VEYQ_ACCESS_TOKEN")
        val code = given()
            .contentType("application/json")
            .cookie("VEYQ_ACCESS_TOKEN", accessCookie)
            .body("""{"redirectUri":"veyq://auth/callback"}""")
            .`when`().post("/api/auth/mobile/session/code")
            .then().statusCode(200)
            .extract().path<String>("code")
        val nativeAccessToken = given()
            .contentType("application/json")
            .body("""{"code":"$code"}""")
            .`when`().post("/api/auth/mobile/session/exchange")
            .then().statusCode(200)
            .extract().path<String>("accessToken")

        given()
            .header("Authorization", "Bearer $nativeAccessToken")
            .`when`().post("/api/auth/mobile/logout")
            .then()
            .statusCode(204)

        given()
            .header("Authorization", "Bearer $nativeAccessToken")
            .`when`().get("/api/frontend/bootstrap")
            .then()
            .statusCode(401)
    }

    private fun hash(value: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
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
