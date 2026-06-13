package com.veyq.app.features.keys.rotateidentity

import com.veyq.app.shared.identity.ServerRole
import com.veyq.app.shared.identity.User
import com.veyq.app.shared.identity.UserRepository
import com.veyq.app.shared.keymgmt.IdentityKey
import com.veyq.app.shared.keymgmt.IdentityKeyRepository
import com.veyq.app.shared.keymgmt.Scope
import com.veyq.app.shared.keymgmt.ScopeGrant
import com.veyq.app.shared.keymgmt.ScopeGrantRepository
import com.veyq.app.shared.keymgmt.ScopeRepository
import com.veyq.app.shared.security.SessionService
import com.veyq.app.shared.tenancy.Tenant
import com.veyq.app.shared.tenancy.TenantRepository
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.`is`
import java.time.Instant
import java.util.Base64
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@QuarkusTest
class RotateIdentitySliceTest {

    @Inject lateinit var users: UserRepository
    @Inject lateinit var identityKeys: IdentityKeyRepository
    @Inject lateinit var scopes: ScopeRepository
    @Inject lateinit var grants: ScopeGrantRepository
    @Inject lateinit var tenants: TenantRepository
    @Inject lateinit var sessions: SessionService

    private lateinit var token: String

    @BeforeTest
    fun seed() {
        QuarkusTransaction.requiringNew().run {
            val now = Instant.now()
            if (users.findByEmail("ri-user@example.com") == null) {
                users.persist(User().apply { id = "ri-user"; email = "ri-user@example.com"; displayName = "RI"; serverRole = ServerRole.ServerMember; createdAt = now })
                identityKeys.persist(IdentityKey().apply {
                    id = "ri-ik"; userId = "ri-user"; suiteId = "v1"
                    kemPublicKey = "oldkem".toByteArray(); signPublicKey = "oldsign".toByteArray()
                    wrappedPrivateKey = "oldpriv".toByteArray(); wrappedPrivateSuiteId = "v1"; createdAt = now
                })
                tenants.persist(Tenant().apply { id = "ri-tenant"; name = "ri-acme"; displayName = "RiAcme"; status = "ACTIVE"; createdBy = "ri-user"; createdAt = now })
                scopes.persist(Scope().apply { id = "ri-personal"; ownerUserId = "ri-user"; kind = "PERSONAL_ROOT"; suiteId = "v1"; kemPublicKey = "ppub".toByteArray(); createdAt = now })
                scopes.persist(Scope().apply { id = "ri-tscope"; tenantId = "ri-tenant"; kind = "TENANT_ROOT"; suiteId = "v1"; kemPublicKey = "tpub".toByteArray(); createdAt = now })
                grants.persist(ScopeGrant().apply { id = "ri-pg"; scopeId = "ri-personal"; userId = "ri-user"; suiteId = "v1"; wrappedPrivateKey = "pg".toByteArray(); createdAt = now })
                grants.persist(ScopeGrant().apply { id = "ri-tg"; scopeId = "ri-tscope"; userId = "ri-user"; suiteId = "v1"; wrappedPrivateKey = "tg".toByteArray(); createdAt = now })
            }
        }
        token = "Bearer ${sessions.issue("ri-user")}"
    }

    @Test
    fun rotatesIdentityAndReWrapsExactlyTheOwnedGrants() {
        // the grant roster lists both scopes with their kind + public key
        given().header("Authorization", token).get("/api/me/grants").then().statusCode(200)
            .body("grants.size()", `is`(2))
            .body("grants.find { it.scopeId == 'ri-personal' }.kind", `is`("PERSONAL_ROOT"))
            .body("grants.find { it.scopeId == 'ri-tscope' }.kind", `is`("TENANT_ROOT"))

        // an incomplete or outsider re-wrap is rejected (rotation can't drop or invent access)
        given().header("Authorization", token).contentType(ContentType.JSON).body(rotateBody(listOf("ri-personal" to "bmV3MQ")))
            .post("/api/me/identity/rotate").then().statusCode(400).body("code", `is`("INCOMPLETE_REWRAP"))
        given().header("Authorization", token).contentType(ContentType.JSON)
            .body(rotateBody(listOf("ri-personal" to "bmV3MQ", "ri-tscope" to "bmV3Mg", "ghost" to "eA")))
            .post("/api/me/identity/rotate").then().statusCode(400).body("code", `is`("INCOMPLETE_REWRAP"))

        // rotate: new identity public key + re-wrapped grants for exactly the held scopes
        given().header("Authorization", token).contentType(ContentType.JSON)
            .body(rotateBody(listOf("ri-personal" to "bmV3MQ", "ri-tscope" to "bmV3Mg")))
            .post("/api/me/identity/rotate").then().statusCode(200).body("id", `is`("ri-user"))

        // the identity public key changed and the grants now hold the new ciphertext
        val kem = QuarkusTransaction.requiringNew().call { String(identityKeys.findByUserId("ri-user")!!.kemPublicKey) }
        assertEquals("newkem", kem)
        given().header("Authorization", token).get("/api/me/grants").then().statusCode(200)
            .body("grants.find { it.scopeId == 'ri-personal' }.wrappedScopePrivateKey.bytes", `is`("bmV3MQ"))
            .body("grants.find { it.scopeId == 'ri-tscope' }.wrappedScopePrivateKey.bytes", `is`("bmV3Mg"))

        // a bogus session cannot list or rotate
        given().header("Authorization", "Bearer nope").get("/api/me/grants").then().statusCode(401)
    }

    private fun rotateBody(grants: List<Pair<String, String>>): String {
        val g = grants.joinToString(",") { (sid, bytes) -> """{"scopeId":"$sid","wrappedScopePrivateKey":{"suiteId":"v1","bytes":"$bytes"}}""" }
        val newKem = Base64.getUrlEncoder().withoutPadding().encodeToString("newkem".toByteArray())
        return """{"identityPublicKey":{"suiteId":"v1","kemPublicKey":"$newKem","signPublicKey":"bmV3c2ln"},""" +
            """"wrappedIdentityPrivateKey":{"suiteId":"v1","bytes":"bmV3cHJpdg"},"grants":[$g]}"""
    }
}
