package com.veyq.app.features.admin.setupserver

import com.veyq.app.shared.identity.UserRepository
import com.veyq.app.shared.keymgmt.KeyBlobRepository
import com.veyq.app.shared.keymgmt.ScopeGrantRepository
import com.veyq.app.shared.keymgmt.ScopeRepository
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.`is`
import org.hamcrest.CoreMatchers.notNullValue
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals

@QuarkusTest
@TestProfile(SetupServerSliceTest.IsolatedDb::class)
class SetupServerSliceTest {

    // The cold-setup flow asserts a virgin instance (setupRequired = countServerOwners() == 0). The suite
    // shares one in-memory H2 (DB_CLOSE_DELAY=-1) and other slices seed a ServerOwner — which would flip
    // setupRequired to false here depending on class order. An isolated datasource gives this class its own
    // pristine DB so the cold path is deterministic.
    class IsolatedDb : QuarkusTestProfile {
        override fun getConfigOverrides() =
            mapOf("quarkus.datasource.jdbc.url" to "jdbc:h2:mem:setup-isolated;DB_CLOSE_DELAY=-1")
    }

    @Inject
    lateinit var users: UserRepository
    @Inject
    lateinit var keyBlobs: KeyBlobRepository
    @Inject
    lateinit var scopes: ScopeRepository
    @Inject
    lateinit var grants: ScopeGrantRepository

    private val passwordWrap = "AQIDBA" // base64url of 0x01020304
    private val personalPublicKey = "cGVyc29uYWw" // base64url("personal")
    private val personalGrant = "cGVyc29uYWxncmFudA" // base64url("personalgrant")

    private fun decode(s: String): ByteArray =
        Base64.getUrlDecoder().decode(s.padEnd(s.length + (4 - s.length % 4) % 4, '='))

    private val request = """
        {
          "email": "owner@example.com",
          "displayName": "Owner",
          "identityPublicKey": { "suiteId": "v1", "kemPublicKey": "a2Vt", "signPublicKey": "c2ln" },
          "wrappedIdentityPrivateKey": { "suiteId": "v1", "bytes": "cHJpdg" },
          "masterKeyWraps": [
            { "unlockMethod": "PASSWORD", "suiteId": "v1", "wrappedKey": "$passwordWrap" }
          ],
          "authVerifier": { "kind": "ARGON2_PROOF", "suiteId": "v1", "verifier": "${'$'}argon2id${'$'}v=19${'$'}m=32768,t=3,p=1${'$'}AAAAAAAAAAAAAAAAAAAAAA${'$'}AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" },
          "personalScopePublicKey": { "suiteId": "v1", "kemPublicKey": "$personalPublicKey" },
          "wrappedPersonalScopePrivateKey": { "suiteId": "v1", "bytes": "$personalGrant" }
        }
    """.trimIndent()

    @Test
    fun coldRegistrationFlow() {
        // 1. setup required on a fresh instance, advertising the v1 suite
        given().get("/api/setup")
            .then().statusCode(200)
            .body("setupRequired", `is`(true))
            .body("suites[0]", `is`("v1"))
            .body("serverVersion", notNullValue()) // advisory server version (default "dev" in tests)

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

        // 4. blindness: the stored blobs are byte-identical to what was posted
        val stored = QuarkusTransaction.requiringNew().call { keyBlobs.listAll().first().wrappedKey }
        assertContentEquals(decode(passwordWrap), stored)
        val personal = QuarkusTransaction.requiringNew().call {
            val user = users.findByEmail("owner@example.com") ?: error("missing setup owner")
            val scope = scopes.findPersonalByOwnerUserId(user.id) ?: error("missing personal scope")
            scope.kemPublicKey to (grants.find(scope.id, scope.ownerUserId!!)?.wrappedPrivateKey ?: error("missing personal grant"))
        }
        assertContentEquals(decode(personalPublicKey), personal.first)
        assertContentEquals(decode(personalGrant), personal.second)

        // 5. a second setup is rejected
        given().contentType(ContentType.JSON).body(request)
            .post("/api/setup/owner")
            .then().statusCode(409)
            .body("code", `is`("ALREADY_SETUP"))
    }

    // Annotation-driven validation (custom @Constraint via the OpenAPI x-field-extra-annotation extension) is
    // enforced at the @Valid boundary → 400 BEFORE the handler runs, independent of setup state.
    @Test
    fun rejectsBlankDisplayName() {
        val bad = request.replace(""""displayName": "Owner"""", """"displayName": """"")
        given().contentType(ContentType.JSON).body(bad)
            .post("/api/setup/owner")
            .then().statusCode(400)
    }

    @Test
    fun rejectsMalformedEmail() {
        val bad = request.replace(""""email": "owner@example.com"""", """"email": "not-an-email"""")
        given().contentType(ContentType.JSON).body(bad)
            .post("/api/setup/owner")
            .then().statusCode(400)
    }
}
