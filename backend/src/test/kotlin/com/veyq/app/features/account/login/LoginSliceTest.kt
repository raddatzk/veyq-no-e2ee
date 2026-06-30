package com.veyq.app.features.account.login

import com.veyq.app.shared.identity.VeyqUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64

/**
 * Slice tests for challenge-response login + sessions (uc.account.login). Seeds a user with a REAL
 * Ed25519 auth keypair directly (the setup endpoint stores a dummy key and is single-owner-locked), then
 * drives challenge → sign → verify and the token lifecycle through the running instance.
 */
@QuarkusTest
class LoginSliceTest {

    @BeforeEach
    fun seedUser() {
        QuarkusTransaction.requiringNew().run(Runnable {
            if (VeyqUser.find("email", EMAIL).firstResult() == null) {
                VeyqUser().apply {
                    id = "login-test-user"
                    email = EMAIL
                    displayName = "Login Test"
                    serverRole = "SERVER_OWNER"
                    suiteId = "v1"
                    identityPublicKey = "aWRlbnRpdHktcHVibGljLWtleQ=="
                    wrappedIdentityPrivateKey = "d3JhcHBlZC1pZGVudGl0eS1wcml2YXRlLWtleQ=="
                    authPublicKey = b64(rawPublicKey(KEYPAIR))
                    createdAt = Instant.now()
                }.persist()
            }
        })
    }

    // --- login (challenge → verify) ---

    @Test
    fun validLoginIssuesSession() {
        val nonce = requestChallenge(EMAIL)
        verify(EMAIL, sign(KEYPAIR, nonce))
            .then().statusCode(200)
            .body("accessToken", notNullValue())
            .body("refreshToken", notNullValue())
            .body("family.id", notNullValue())
    }

    @Test
    fun wrongSignatureIsGenericFailure() {
        val nonce = requestChallenge(EMAIL)
        verify(EMAIL, sign(OTHER_KEYPAIR, nonce)) // signed by the wrong key
            .then().statusCode(401).body("code", equalTo("authentication_failed"))
    }

    @Test
    fun unknownAccountIsIdenticalGenericFailure() {
        // Challenge for a non-existent account still returns 200 with a nonce (non-enumerable)...
        val nonce = requestChallenge("ghost@example.com")
        assertTrue(nonce.isNotEmpty())
        // ...and verify fails with the SAME generic code as a wrong signature.
        verify("ghost@example.com", sign(KEYPAIR, nonce))
            .then().statusCode(401).body("code", equalTo("authentication_failed"))
    }

    @Test
    fun replayedNonceIsRejected() {
        val nonce = requestChallenge(EMAIL)
        val sig = sign(KEYPAIR, nonce)
        verify(EMAIL, sig).then().statusCode(200) // single use consumes the challenge
        verify(EMAIL, sig).then().statusCode(401) // replay → no outstanding nonce
    }

    @Test
    fun expiredNonceIsRejected() {
        val nonce = requestChallenge(EMAIL)
        // Force the stored challenge past its TTL (5 min) without waiting it out — a correctly-signed but
        // stale nonce must be rejected, not just a replayed one.
        QuarkusTransaction.requiringNew().run(Runnable {
            val challenge = LoginChallengeEntity.find("email", EMAIL).firstResult()!!
            challenge.expiresAt = Instant.now().minus(1, ChronoUnit.MINUTES)
            challenge.persist()
        })
        verify(EMAIL, sign(KEYPAIR, nonce)) // valid signature, expired challenge
            .then().statusCode(401).body("code", equalTo("authentication_failed"))
    }

    // --- token rotation + reuse detection ---

    @Test
    fun refreshRotatesAndChains() {
        val r1 = login().refreshToken
        val r2 = refresh(r1).then().statusCode(200).extract().path<String>("refreshToken")
        refresh(r2).then().statusCode(200) // the chain keeps rotating
    }

    @Test
    fun replayedSupersededRefreshRevokesFamily() {
        val r1 = login().refreshToken
        val r2 = refresh(r1).then().statusCode(200).extract().path<String>("refreshToken")
        // r1 is now superseded; replaying it is a clone signal → the whole family is revoked.
        refresh(r1).then().statusCode(401)
        // ...so even the once-valid r2 no longer works.
        refresh(r2).then().statusCode(401)
    }

    @Test
    fun unknownRefreshTokenIsPlain401AndRevokesNobody() {
        // A live, innocent refresh family exists alongside the attack.
        val victim = login()
        // A token that matches no stored hash is just rejected...
        refresh("not-a-real-token").then().statusCode(401).body("code", equalTo("refresh_rejected"))
        // ...and crucially revokes nobody: the bystander family still refreshes. A bogus token cannot be a
        // DoS lever against other sessions.
        refresh(victim.refreshToken).then().statusCode(200)
    }

    @Test
    fun logoutRevokesFamily() {
        val session = login()
        given().header("Authorization", "Bearer ${session.accessToken}")
            .`when`().post("/api/logout")
            .then().statusCode(204)
        refresh(session.refreshToken).then().statusCode(401) // family gone
    }

    // --- "remember me" cookie transport (cr-0052) ---

    @Test
    fun rememberLoginSetsHttpOnlyRefreshCookieAndOmitsBodyToken() {
        val nonce = requestChallenge(EMAIL)
        val res = verifyRemember(EMAIL, sign(KEYPAIR, nonce)).then().statusCode(200)
            .body("accessToken", notNullValue())
            .body("refreshToken", nullValue()) // omitted from the body — it rides the cookie instead
            .cookie("veyq_refresh", notNullValue())
            .extract()
        assertTrue(res.detailedCookie("veyq_refresh").isHttpOnly)
    }

    @Test
    fun refreshViaCookieRotatesAndOmitsBodyToken() {
        val cookie = rememberLoginCookie()
        // No body token — the refresh token rides the cookie; the rotated one comes back as a fresh cookie.
        given().contentType(ContentType.JSON).cookie("veyq_refresh", cookie).body("{}")
            .`when`().post("/api/token/refresh")
            .then().statusCode(200)
            .body("accessToken", notNullValue())
            .body("refreshToken", nullValue())
            .cookie("veyq_refresh", notNullValue())
    }

    @Test
    fun refreshWithNeitherCookieNorBodyTokenIs400() {
        given().contentType(ContentType.JSON).body("{}")
            .`when`().post("/api/token/refresh")
            .then().statusCode(400).body("code", equalTo("invalid_request"))
    }

    @Test
    fun logoutClearsRefreshCookie() {
        val session = login()
        given().header("Authorization", "Bearer ${session.accessToken}")
            .`when`().post("/api/logout")
            .then().statusCode(204)
            .cookie("veyq_refresh", equalTo("")) // Max-Age 0, empty value — the browser drops it
    }

    // --- instance salt ---

    @Test
    fun setupStatusCarriesStableInstanceSalt() {
        val first = given().`when`().get("/api/setup").then().statusCode(200)
            .extract().path<String>("instanceSalt")
        assertTrue(first.isNotEmpty())
        Base64.getDecoder().decode(first) // valid base64
        val second = given().`when`().get("/api/setup").then().extract().path<String>("instanceSalt")
        assertEquals(first, second) // stable across calls
    }

    // --- helpers ---

    private data class Session(val accessToken: String, val refreshToken: String)

    private fun login(): Session {
        val nonce = requestChallenge(EMAIL)
        val res = verify(EMAIL, sign(KEYPAIR, nonce)).then().statusCode(200).extract()
        return Session(res.path("accessToken"), res.path("refreshToken"))
    }

    private fun requestChallenge(email: String): String =
        given().contentType(ContentType.JSON).body("""{"email":"$email"}""")
            .`when`().post("/api/login/challenge")
            .then().statusCode(200).extract().path("nonce")

    private fun verify(email: String, signatureB64: String) =
        given().contentType(ContentType.JSON).body("""{"email":"$email","signature":"$signatureB64"}""")
            .`when`().post("/api/login/verify")

    private fun verifyRemember(email: String, signatureB64: String) =
        given().contentType(ContentType.JSON)
            .body("""{"email":"$email","signature":"$signatureB64","remember":true}""")
            .`when`().post("/api/login/verify")

    private fun rememberLoginCookie(): String {
        val nonce = requestChallenge(EMAIL)
        return verifyRemember(EMAIL, sign(KEYPAIR, nonce)).then().statusCode(200)
            .extract().cookie("veyq_refresh")
    }

    private fun refresh(refreshToken: String) =
        given().contentType(ContentType.JSON).body("""{"refreshToken":"$refreshToken"}""")
            .`when`().post("/api/token/refresh")

    companion object {
        private const val EMAIL = "login-test@example.com"
        private val KEYPAIR: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        private val OTHER_KEYPAIR: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

        private fun rawPublicKey(kp: KeyPair): ByteArray =
            kp.public.encoded.let { it.copyOfRange(it.size - 32, it.size) }

        /** Sign the decoded nonce bytes with the keypair's private key; return standard-base64 signature. */
        private fun sign(kp: KeyPair, nonceB64: String): String {
            val sig = Signature.getInstance("Ed25519")
            sig.initSign(kp.private)
            sig.update(Base64.getDecoder().decode(nonceB64))
            return b64(sig.sign())
        }

        private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    }
}
