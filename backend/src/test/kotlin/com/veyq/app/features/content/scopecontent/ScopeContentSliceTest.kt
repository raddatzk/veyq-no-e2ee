package com.veyq.app.features.content.scopecontent

import com.veyq.app.shared.identity.ServerRole
import com.veyq.app.shared.identity.User
import com.veyq.app.shared.identity.UserRepository
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
import kotlin.test.BeforeTest
import kotlin.test.Test

@QuarkusTest
class ScopeContentSliceTest {

    @Inject lateinit var users: UserRepository
    @Inject lateinit var tenants: TenantRepository
    @Inject lateinit var scopes: ScopeRepository
    @Inject lateinit var grants: ScopeGrantRepository
    @Inject lateinit var sessions: SessionService

    private lateinit var holderToken: String
    private lateinit var outsiderToken: String
    private val scopeId = "ci-scope"

    @BeforeTest
    fun seed() {
        QuarkusTransaction.requiringNew().run {
            val now = Instant.now()
            if (users.findByEmail("ci-owner@example.com") == null) {
                users.persist(User().apply { id = "ci-owner"; email = "ci-owner@example.com"; displayName = "Owner"; serverRole = ServerRole.ServerMember; createdAt = now })
                users.persist(User().apply { id = "ci-outsider"; email = "ci-outsider@example.com"; displayName = "Outsider"; serverRole = ServerRole.ServerMember; createdAt = now })
                tenants.persist(Tenant().apply { id = "ci-tenant"; name = "ci-acme"; displayName = "CiAcme"; status = "ACTIVE"; createdBy = "ci-owner"; createdAt = now })
                scopes.persist(Scope().apply { id = scopeId; tenantId = "ci-tenant"; kind = "TENANT_ROOT"; suiteId = "v1"; kemPublicKey = "pub".toByteArray(); createdAt = now })
                grants.persist(ScopeGrant().apply { id = "ci-grant"; this.scopeId = this@ScopeContentSliceTest.scopeId; userId = "ci-owner"; suiteId = "v1"; wrappedPrivateKey = "g".toByteArray(); createdAt = now })
            }
        }
        holderToken = "Bearer ${sessions.issue("ci-owner")}"
        outsiderToken = "Bearer ${sessions.issue("ci-outsider")}"
    }

    @Test
    fun storesAndServesEncryptedContentForKeyHoldersOnly() {
        // only a key-holder may touch content; outsiders and unknown scopes are rejected
        given().header("Authorization", outsiderToken).get("/api/scopes/$scopeId/content-key").then().statusCode(403).body("code", `is`("NOT_A_KEY_HOLDER"))
        given().header("Authorization", holderToken).get("/api/scopes/ghost/content-key").then().statusCode(404)

        // no content key until provisioned
        given().header("Authorization", holderToken).get("/api/scopes/$scopeId/content-key").then().statusCode(404)

        // provision once; a second provision is rejected so everyone converges on one key
        given().header("Authorization", holderToken).contentType(ContentType.JSON).body(contentKeyBody("Y2tleQ"))
            .put("/api/scopes/$scopeId/content-key").then().statusCode(200).body("wrappedContentKey.bytes", `is`("Y2tleQ"))
        given().header("Authorization", holderToken).contentType(ContentType.JSON).body(contentKeyBody("b3RoZXI"))
            .put("/api/scopes/$scopeId/content-key").then().statusCode(409).body("code", `is`("CONTENT_KEY_EXISTS"))
        given().header("Authorization", holderToken).get("/api/scopes/$scopeId/content-key").then().statusCode(200).body("wrappedContentKey.bytes", `is`("Y2tleQ"))

        // store an opaque item, then read it back byte-identical (blindness); outsiders cannot list
        val id = given().header("Authorization", holderToken).contentType(ContentType.JSON).body(itemBody("c2VhbGVk"))
            .post("/api/scopes/$scopeId/items").then().statusCode(200)
            .body("sealedContent.bytes", `is`("c2VhbGVk")).extract().path<String>("id")
        given().header("Authorization", holderToken).get("/api/scopes/$scopeId/items").then().statusCode(200)
            .body("items.size()", `is`(1))
            .body("items[0].id", `is`(id))
            .body("items[0].sealedContent.bytes", `is`("c2VhbGVk"))
        given().header("Authorization", outsiderToken).get("/api/scopes/$scopeId/items").then().statusCode(403)
    }

    private fun contentKeyBody(bytes: String) = """{"wrappedContentKey":{"suiteId":"v1","bytes":"$bytes"}}"""
    private fun itemBody(bytes: String) = """{"sealedContent":{"suiteId":"v1","bytes":"$bytes"}}"""
}
