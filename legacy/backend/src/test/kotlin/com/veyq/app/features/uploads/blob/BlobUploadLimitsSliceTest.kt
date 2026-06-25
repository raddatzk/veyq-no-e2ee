package com.veyq.app.features.uploads.blob

import com.veyq.app.shared.content.ScopeContentKey
import com.veyq.app.shared.content.ScopeContentKeyRepository
import com.veyq.app.shared.identity.ServerRole
import com.veyq.app.shared.identity.User
import com.veyq.app.shared.identity.UserRepository
import com.veyq.app.shared.keymgmt.Scope
import com.veyq.app.shared.keymgmt.ScopeGrant
import com.veyq.app.shared.keymgmt.ScopeGrantRepository
import com.veyq.app.shared.keymgmt.ScopeRepository
import com.veyq.app.shared.security.SessionService
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.junit.QuarkusTestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.`is`
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import com.veyq.app.shared.keymgmt.ScopeKind

/**
 * uc.uploads (limits) — the server bounds chunk counts, declared sizes, single-frame sizes, cumulative frame
 * data and concurrent OPEN sessions BEFORE doing expensive memory/BlobStore work (anti-DoS). Tiny limits are
 * injected via a TestProfile so the bounds are cheap to hit.
 */
@QuarkusTest
@TestProfile(BlobUploadLimitsSliceTest.TinyLimits::class)
class BlobUploadLimitsSliceTest {

    class TinyLimits : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf(
            "veyq.uploads.max-chunks" to "4",
            "veyq.uploads.max-upload-bytes" to "1000",
            "veyq.uploads.max-frame-bytes" to "64",
            "veyq.uploads.max-open-sessions" to "2",
        )
    }

    @Inject lateinit var users: UserRepository
    @Inject lateinit var scopes: ScopeRepository
    @Inject lateinit var grants: ScopeGrantRepository
    @Inject lateinit var contentKeys: ScopeContentKeyRepository
    @Inject lateinit var sessions: SessionService

    private lateinit var ownerToken: String
    private lateinit var capOwnerToken: String
    // two owner/scope pairs so the tests don't cross-count OPEN sessions (one leaves OPEN uploads behind on
    // purpose); a personal scope is unique per (owner, kind), so the second needs its own owner.
    private val scopeId = "lim-scope"
    private val capScopeId = "lim-cap-scope"

    @BeforeTest
    fun seed() {
        QuarkusTransaction.requiringNew().run {
            val now = Instant.now()
            if (users.findByEmail("lim-owner@example.com") != null) return@run
            for ((uid, sid) in listOf("lim-owner" to scopeId, "lim-cap-owner" to capScopeId)) {
                users.persist(User().apply { id = uid; email = "$uid@example.com"; displayName = "Owner"; serverRole = ServerRole.ServerMember; createdAt = now })
                scopes.persist(Scope().apply { id = sid; ownerUserId = uid; kind = ScopeKind.PERSONAL_ROOT; suiteId = "v1"; kemPublicKey = "p".toByteArray(); createdAt = now })
                grants.persist(ScopeGrant().apply { id = "$sid-grant"; this.scopeId = sid; userId = uid; suiteId = "v1"; wrappedPrivateKey = "g".toByteArray(); createdAt = now })
                contentKeys.persist(ScopeContentKey().apply { id = "$sid-ck"; this.scopeId = sid; suiteId = "v1"; wrappedKey = "ck".toByteArray(); createdAt = now })
            }
        }
        ownerToken = "Bearer ${sessions.issue("lim-owner")}"
        capOwnerToken = "Bearer ${sessions.issue("lim-cap-owner")}"
    }

    private fun start(token: String, total: Int = 2, size: Int = 16) = """{"dedupToken":"$token","totalChunks":$total,"byteSize":$size}"""
    private fun startUpload(authz: String, scope: String, token: String, total: Int, size: Int): String =
        given().header("Authorization", authz).contentType(ContentType.JSON).body(start(token, total, size))
            .post("/api/scopes/$scope/blobs").then().statusCode(200).extract().path("uploadId")

    @Test
    fun rejectsOversizedDeclarationsBeforeHeavyWork() {
        // The cheap declare-time bounds (anti-DoS) reject before any store work. Per-frame + cumulative budget
        // are now enforced on the directly-uploaded objects at complete (stat-based, reconcileDirectUploads) and
        // exercised by the opt-in MinIO e2e (BlobBatchE2ETest); this slice covers the declare-time gates.
        // too many chunks declared
        given().header("Authorization", ownerToken).contentType(ContentType.JSON).body(start("t1", total = 5, size = 16))
            .post("/api/scopes/$scopeId/blobs").then().statusCode(400).body("code", `is`("INVALID_CHUNK_COUNT"))
        // declared byteSize over the configured ceiling
        given().header("Authorization", ownerToken).contentType(ContentType.JSON).body(start("t2", total = 2, size = 2000))
            .post("/api/scopes/$scopeId/blobs").then().statusCode(400).body("code", `is`("INVALID_SIZE"))
    }

    @Test
    fun capsConcurrentOpenSessionsPerScope() {
        startUpload(capOwnerToken, capScopeId, "cap-a", total = 1, size = 8)
        startUpload(capOwnerToken, capScopeId, "cap-b", total = 1, size = 8)
        // the third concurrent OPEN session is refused (anti DB-row exhaustion)
        given().header("Authorization", capOwnerToken).contentType(ContentType.JSON).body(start("cap-c", total = 1, size = 8))
            .post("/api/scopes/$capScopeId/blobs").then().statusCode(429).body("code", `is`("TOO_MANY_OPEN_UPLOADS"))
    }
}
