package com.veyq.app.features.account.login

import com.veyq.app.shared.identity.ServerRole
import com.veyq.app.shared.identity.User
import com.veyq.app.shared.identity.UserRepository
import com.veyq.app.shared.keymgmt.AuthVerifier
import com.veyq.app.shared.keymgmt.AuthVerifierRepository
import com.veyq.app.shared.keymgmt.IdentityKey
import com.veyq.app.shared.keymgmt.IdentityKeyRepository
import com.veyq.app.shared.keymgmt.KeyBlob
import com.veyq.app.shared.keymgmt.KeyBlobRepository
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
import org.junit.jupiter.api.BeforeEach
import java.text.Normalizer
import java.time.Instant
import kotlin.test.Test

@QuarkusTest
class LoginSliceTest {

    @Inject lateinit var users: UserRepository
    @Inject lateinit var identityKeys: IdentityKeyRepository
    @Inject lateinit var keyBlobs: KeyBlobRepository
    @Inject lateinit var authVerifiers: AuthVerifierRepository

    private val email = "login@example.com"
    private val password = "correct horse battery staple"

    /** Mirrors the clients' v1 ARGON2_PROOF verifier: salt(16) || Argon2id(nfc(password), salt). */
    private fun verifierBytes(): ByteArray {
        val salt = ByteArray(16) { it.toByte() }
        val nfc = Normalizer.normalize(password, Normalizer.Form.NFC).toByteArray(Charsets.UTF_8)
        val gen = Argon2BytesGenerator()
        gen.init(
            Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(3).withMemoryAsKB(8192).withParallelism(1).withSalt(salt).build(),
        )
        val proof = ByteArray(32).also { gen.generateBytes(nfc, it) }
        return salt + proof
    }

    /** Seed an enrolled user directly (bypassing the one-shot setup gate) so the slice is independent. */
    @BeforeEach
    fun seed() {
        QuarkusTransaction.requiringNew().run {
            if (users.findByEmail(email) != null) return@run
            val now = Instant.now()
            val user = User().apply {
                id = "login-user"; this.email = this@LoginSliceTest.email
                displayName = "Login User"; serverRole = ServerRole.ServerMember; createdAt = now
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
            authVerifiers.persist(
                AuthVerifier().apply {
                    id = "login-ver"; userId = user.id; kind = "ARGON2_PROOF"; suiteId = "v1"
                    verifier = verifierBytes(); createdAt = now
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

        // 3. a wrong password is rejected, no session
        given().contentType(ContentType.JSON)
            .body("""{"email":"$email","password":"wrong"}""")
            .post("/api/login")
            .then().statusCode(401)
            .body("code", `is`("INVALID_CREDENTIALS"))

        // 4. a bogus token does not resolve
        given().header("Authorization", "Bearer nope")
            .get("/api/me")
            .then().statusCode(401)
    }
}
