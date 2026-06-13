package com.veyq.app.features.admin.setupserver

import com.veyq.app.shared.keymgmt.KeyBlobRepository
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.`is`
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals

@QuarkusTest
class SetupServerSliceTest {

    @Inject
    lateinit var keyBlobs: KeyBlobRepository

    private val passwordWrap = "AQIDBA" // base64url of 0x01020304

    private val request = """
        {
          "email": "owner@example.com",
          "displayName": "Owner",
          "identityPublicKey": { "suiteId": "v1", "kemPublicKey": "a2Vt", "signPublicKey": "c2ln" },
          "wrappedIdentityPrivateKey": { "suiteId": "v1", "bytes": "cHJpdg" },
          "masterKeyWraps": [
            { "unlockMethod": "PASSWORD", "suiteId": "v1", "wrappedKey": "$passwordWrap" }
          ],
          "authVerifier": { "kind": "ARGON2_PROOF", "suiteId": "v1", "verifier": "dmVy" }
        }
    """.trimIndent()

    @Test
    fun coldRegistrationFlow() {
        // 1. setup required on a fresh instance, advertising the v1 suite
        given().get("/api/setup")
            .then().statusCode(200)
            .body("setupRequired", `is`(true))
            .body("suites[0]", `is`("v1"))

        // 2. create the first Server Owner
        given().contentType(ContentType.JSON).body(request)
            .post("/api/setup/owner")
            .then().statusCode(200)
            .body("email", `is`("owner@example.com"))
            .body("serverRole", `is`("ServerOwner"))

        // 3. setup no longer required
        given().get("/api/setup")
            .then().statusCode(200)
            .body("setupRequired", `is`(false))

        // 4. blindness: the stored blob is byte-identical to what was posted
        val stored = QuarkusTransaction.requiringNew().call { keyBlobs.listAll().first().wrappedKey }
        assertContentEquals(Base64.getUrlDecoder().decode(passwordWrap), stored)

        // 5. a second setup is rejected
        given().contentType(ContentType.JSON).body(request)
            .post("/api/setup/owner")
            .then().statusCode(409)
            .body("code", `is`("ALREADY_SETUP"))
    }
}
