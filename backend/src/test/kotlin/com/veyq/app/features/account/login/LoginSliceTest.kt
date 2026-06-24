package com.veyq.app.features.account.login

import com.veyq.app.shared.crypto.Argon2Verifier
import com.veyq.app.shared.identity.ServerRole
import com.veyq.app.shared.identity.User
import com.veyq.app.shared.identity.UserRepository
import com.veyq.app.shared.keymgmt.IdentityKey
import com.veyq.app.shared.keymgmt.IdentityKeyRepository
import com.veyq.app.shared.keymgmt.KeyBlob
import com.veyq.app.shared.keymgmt.KeyBlobRepository
import com.veyq.app.shared.keymgmt.Scope
import com.veyq.app.shared.keymgmt.ScopeGrant
import com.veyq.app.shared.keymgmt.ScopeGrantRepository
import com.veyq.app.shared.keymgmt.ScopeRepository
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.hamcrest.CoreMatchers.`is`
import org.hamcrest.CoreMatchers.not
import org.hamcrest.Matchers.emptyString
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.BeforeEach
import java.time.Instant
import kotlin.test.Test
import com.veyq.app.shared.keymgmt.ScopeKind

@QuarkusTest
class LoginSliceTest {

    @Inject lateinit var users: UserRepository
    @Inject lateinit var identityKeys: IdentityKeyRepository
    @Inject lateinit var keyBlobs: KeyBlobRepository
    @Inject lateinit var scopes: ScopeRepository
    @Inject lateinit var grants: ScopeGrantRepository
    @Inject lateinit var argon2: Argon2Verifier

    private val email = "login@example.com"
    private val password = "correct horse battery staple"

    /** Seed an enrolled user directly (bypassing the one-shot setup gate) so the slice is independent. */
    @BeforeEach
    fun seed() {
        QuarkusTransaction.requiringNew().run {
            users.findByEmail(email)?.let {
                it.displayName = "Login User"
                return@run
            }
            val now = Instant.now()
            val user = User().apply {
                id = "login-user"; this.email = this@LoginSliceTest.email
                displayName = "Login User"; serverRole = ServerRole.ServerMember; createdAt = now
                authVerifierKind = "ARGON2_PROOF"; authVerifierSuiteId = "v1"
                authVerifier = argon2.createPasswordVerifier(password); authVerifierCreatedAt = now
            }
            users.persist(user)
            identityKeys.persist(
                IdentityKey().apply {
                    id = "login-idk"; userId = user.id; suiteId = "v1"
                    kemPublicKey = "kem".toByteArray(); signPublicKey = "sig".toByteArray()
                    wrappedPrivateKey = "priv".toByteArray(); wrappedPrivateSuiteId = "v1"; createdAt = now
                },
            )
            keyBlobs.persist(
                KeyBlob().apply {
                    id = "login-blob"; userId = user.id; unlockMethod = "PASSWORD"; suiteId = "v1"
                    wrappedKey = byteArrayOf(1, 2, 3, 4); kdfParams = byteArrayOf(5, 6, 7, 8); createdAt = now
                },
            )
            scopes.persist(
                Scope().apply {
                    id = "login-personal-scope"; ownerUserId = user.id; kind = ScopeKind.PERSONAL_ROOT; suiteId = "v1"
                    kemPublicKey = "personal".toByteArray(); createdAt = now
                },
            )
            grants.persist(
                ScopeGrant().apply {
                    id = "login-personal-grant"; scopeId = "login-personal-scope"; userId = user.id; suiteId = "v1"
                    wrappedPrivateKey = "personalgrant".toByteArray(); createdAt = now
                },
            )
        }
    }

    @Test
    fun loginUnlockAndSession() {
        // 1. login returns a session token + the wrapped key bag (ciphertext only)
        val sessionToken = given().contentType(ContentType.JSON)
            .body("""{"email":"$email","password":"$password"}""")
            .post("/api/login")
            .then().statusCode(200)
            .body("user.email", `is`(email))
            .body("user.serverRole", `is`("ServerMember"))
            .body("sessionToken", `is`(not(emptyString())))
            .body("keyBag.identityPublicKey.kemPublicKey", `is`("a2Vt")) // base64url("kem")
            .body("keyBag.wrappedIdentityPrivateKey.bytes", `is`("cHJpdg")) // base64url("priv")
            .body("keyBag.masterKeyWraps[0].unlockMethod", `is`("PASSWORD"))
            .body("keyBag.masterKeyWraps[0].wrappedKey", `is`("AQIDBA")) // base64url(01020304)
            .body("keyBag.masterKeyWraps[0].kdfParams", `is`("BQYHCA")) // base64url(05060708)
            .extract().path<String>("sessionToken")

        // 2. the session resolves
        given().header("Authorization", "Bearer $sessionToken")
            .get("/api/me")
            .then().statusCode(200)
            .body("email", `is`(email))

        // 3. the session resolves the user's personal scope grant (ciphertext only)
        given().header("Authorization", "Bearer $sessionToken")
            .get("/api/me/personal-scope")
            .then().statusCode(200)
            .body("scopePublicKey.kemPublicKey", `is`("cGVyc29uYWw")) // base64url("personal")
            .body("wrappedScopePrivateKey.bytes", `is`("cGVyc29uYWxncmFudA")) // base64url("personalgrant")

        // 4. logout invalidates the session: the same token no longer resolves
        given().header("Authorization", "Bearer $sessionToken")
            .delete("/api/me/session")
            .then().statusCode(204)
        given().header("Authorization", "Bearer $sessionToken")
            .get("/api/me")
            .then().statusCode(401)

        // 4b. logout itself needs a valid session (a bogus token cannot log out)
        given().header("Authorization", "Bearer nope")
            .delete("/api/me/session")
            .then().statusCode(401)

        // 5. a wrong password is rejected, no session
        given().contentType(ContentType.JSON)
            .body("""{"email":"$email","password":"wrong"}""")
            .post("/api/login")
            .then().statusCode(401)
            .body("code", `is`("INVALID_CREDENTIALS"))

        // 6. a bogus token does not resolve
        given().header("Authorization", "Bearer nope")
            .get("/api/me")
            .then().statusCode(401)
        given().header("Authorization", "Bearer nope")
            .get("/api/me/personal-scope")
            .then().statusCode(401)
    }

    @Test
    fun refreshRotatesTheRefreshCookieAndAccessSession() {
        val loginResponse = given().contentType(ContentType.JSON)
            .body("""{"email":"$email","password":"$password"}""")
            .post("/api/login")
            .then().statusCode(200)
            .cookie("VEYQ_REFRESH_TOKEN", notNullValue())
            .extract()

        val firstAccessToken = loginResponse.path<String>("sessionToken")
        val firstRefreshToken = loginResponse.cookie("VEYQ_REFRESH_TOKEN")

        val refreshResponse = given()
            .cookie("VEYQ_REFRESH_TOKEN", firstRefreshToken)
            .post("/api/auth/refresh")
            .then().statusCode(200)
            .cookie("VEYQ_REFRESH_TOKEN", notNullValue())
            .body("sessionToken", `is`(not(firstAccessToken)))
            .extract()

        val secondAccessToken = refreshResponse.path<String>("sessionToken")
        val secondRefreshToken = refreshResponse.cookie("VEYQ_REFRESH_TOKEN")

        given().header("Authorization", "Bearer $firstAccessToken")
            .get("/api/me")
            .then().statusCode(401)

        given().header("Authorization", "Bearer $secondAccessToken")
            .get("/api/me")
            .then().statusCode(200)
            .body("email", `is`(email))

        given()
            .cookie("VEYQ_REFRESH_TOKEN", firstRefreshToken)
            .post("/api/auth/refresh")
            .then().statusCode(401)

        given()
            .cookie("VEYQ_REFRESH_TOKEN", secondRefreshToken)
            .post("/api/auth/refresh")
            .then().statusCode(200)
            .body("sessionToken", `is`(not(secondAccessToken)))
    }

    @Test
    fun logoutInvalidatesTheAssociatedAccessAndRefreshTokenFamily() {
        val loginResponse = given().contentType(ContentType.JSON)
            .body("""{"email":"$email","password":"$password"}""")
            .post("/api/login")
            .then().statusCode(200)
            .cookie("VEYQ_REFRESH_TOKEN", notNullValue())
            .extract()

        val firstAccessToken = loginResponse.path<String>("sessionToken")
        val firstRefreshToken = loginResponse.cookie("VEYQ_REFRESH_TOKEN")

        val refreshResponse = given()
            .cookie("VEYQ_REFRESH_TOKEN", firstRefreshToken)
            .post("/api/auth/refresh")
            .then().statusCode(200)
            .cookie("VEYQ_REFRESH_TOKEN", notNullValue())
            .extract()

        val currentAccessToken = refreshResponse.path<String>("sessionToken")
        val currentRefreshToken = refreshResponse.cookie("VEYQ_REFRESH_TOKEN")

        given()
            .header("Authorization", "Bearer $firstAccessToken")
            .cookie("VEYQ_REFRESH_TOKEN", currentRefreshToken)
            .delete("/api/me/session")
            .then().statusCode(204)
            .cookie("VEYQ_REFRESH_TOKEN", emptyString())

        given().header("Authorization", "Bearer $currentAccessToken")
            .get("/api/me")
            .then().statusCode(401)

        given()
            .cookie("VEYQ_REFRESH_TOKEN", currentRefreshToken)
            .post("/api/auth/refresh")
            .then().statusCode(401)
    }

    @Test
    fun updatesCurrentProfileDisplayName() {
        val sessionToken = given().contentType(ContentType.JSON)
            .body("""{"email":"$email","password":"$password"}""")
            .post("/api/login")
            .then().statusCode(200)
            .extract().path<String>("sessionToken")

        given().contentType(ContentType.JSON)
            .header("Authorization", "Bearer $sessionToken")
            .body("""{"displayName":"  Curator Name  ","username":"curator-name"}""")
            .patch("/api/me")
            .then().statusCode(200)
            .body("email", `is`(email))
            .body("displayName", `is`("Curator Name"))
            .body("username", `is`("curator-name"))

        given().header("Authorization", "Bearer $sessionToken")
            .get("/api/me")
            .then().statusCode(200)
            .body("displayName", `is`("Curator Name"))
            .body("username", `is`("curator-name"))

        given().contentType(ContentType.JSON)
            .header("Authorization", "Bearer $sessionToken")
            .body("""{"displayName":"","username":"curator-name"}""")
            .patch("/api/me")
            .then().statusCode(400)

        given().contentType(ContentType.JSON)
            .header("Authorization", "Bearer nope")
            .body("""{"displayName":"Nobody","username":"nobody"}""")
            .patch("/api/me")
            .then().statusCode(401)
    }
}
