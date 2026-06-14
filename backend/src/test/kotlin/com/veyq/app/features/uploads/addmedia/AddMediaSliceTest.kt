package com.veyq.app.features.uploads.addmedia

import com.veyq.app.shared.content.Node
import com.veyq.app.shared.content.NodeRepository
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
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.`is`
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test

/** Keep the size limits tiny so the controls can be proven without large local payloads. */
class AddMediaLimitsProfile : QuarkusTestProfile {
    override fun getConfigOverrides() = mapOf(
        "veyq.uploads.max-inline-bytes" to "64",
        "veyq.uploads.max-thumb-bytes" to "16",
    )
}

@QuarkusTest
@TestProfile(AddMediaLimitsProfile::class)
class AddMediaSliceTest {

    @Inject lateinit var users: UserRepository
    @Inject lateinit var scopes: ScopeRepository
    @Inject lateinit var grants: ScopeGrantRepository
    @Inject lateinit var nodes: NodeRepository
    @Inject lateinit var contentKeys: ScopeContentKeyRepository
    @Inject lateinit var sessions: SessionService

    private lateinit var ownerToken: String
    private lateinit var outsiderToken: String
    private val scopeId = "md-scope"

    @BeforeTest
    fun seed() {
        QuarkusTransaction.requiringNew().run {
            val now = Instant.now()
            if (users.findByEmail("md-owner@example.com") != null) return@run
            users.persist(User().apply { id = "md-owner"; email = "md-owner@example.com"; displayName = "Owner"; serverRole = ServerRole.ServerMember; createdAt = now })
            users.persist(User().apply { id = "md-outsider"; email = "md-outsider@example.com"; displayName = "Outsider"; serverRole = ServerRole.ServerMember; createdAt = now })
            scopes.persist(Scope().apply { id = scopeId; ownerUserId = "md-owner"; kind = "PERSONAL_ROOT"; suiteId = "v1"; kemPublicKey = "p".toByteArray(); createdAt = now })
            grants.persist(ScopeGrant().apply { id = "md-grant"; this.scopeId = this@AddMediaSliceTest.scopeId; userId = "md-owner"; suiteId = "v1"; wrappedPrivateKey = "g".toByteArray(); createdAt = now })
            contentKeys.persist(ScopeContentKey().apply { id = "md-ck"; this.scopeId = this@AddMediaSliceTest.scopeId; suiteId = "v1"; wrappedKey = "ck".toByteArray(); createdAt = now })
            // a Memory (media allowed) and a Folder (media rejected) at the scope root
            nodes.persist(Node().apply { id = "md-mem"; this.scopeId = this@AddMediaSliceTest.scopeId; parentId = this@AddMediaSliceTest.scopeId; type = "MEMORY"; slugToken = "tok-mem"; orderIndex = 0; suiteId = "v1"; sealedContent = "n".toByteArray(); createdAt = now })
            nodes.persist(Node().apply { id = "md-folder"; this.scopeId = this@AddMediaSliceTest.scopeId; parentId = this@AddMediaSliceTest.scopeId; type = "FOLDER"; slugToken = "tok-folder"; orderIndex = 1; suiteId = "v1"; sealedContent = "n".toByteArray(); createdAt = now })
        }
        ownerToken = "Bearer ${sessions.issue("md-owner")}"
        outsiderToken = "Bearer ${sessions.issue("md-outsider")}"
    }

    private fun mediaBody(takenAt: String, blob: String = "c2VhbGVk") =
        """{"takenAt":"$takenAt","mimeType":"image/jpeg","byteSize":1234,"sealedBlob":{"suiteId":"v1","bytes":"$blob"},"sealedThumb":{"suiteId":"v1","bytes":"dGh1bWI"}}"""

    @Test
    fun storesAndServesEncryptedMediaForMemoryNodesOnly() {
        // only a key-holder may add media
        given().header("Authorization", outsiderToken).contentType(ContentType.JSON).body(mediaBody("2026-01-01T10:00:00Z"))
            .post("/api/scopes/$scopeId/nodes/md-mem/media").then().statusCode(403).body("code", `is`("NOT_A_KEY_HOLDER"))

        // media only live inside a Memory/Chapter — a Folder is rejected, an unknown node is a 404
        given().header("Authorization", ownerToken).contentType(ContentType.JSON).body(mediaBody("2026-01-01T10:00:00Z"))
            .post("/api/scopes/$scopeId/nodes/md-folder/media").then().statusCode(400).body("code", `is`("NOT_A_MEMORY"))
        given().header("Authorization", ownerToken).contentType(ContentType.JSON).body(mediaBody("2026-01-01T10:00:00Z"))
            .post("/api/scopes/$scopeId/nodes/ghost/media").then().statusCode(404)

        // store two media out of takenAt order; the bytes round-trip byte-identical (blindness)
        given().header("Authorization", ownerToken).contentType(ContentType.JSON).body(mediaBody("2026-03-15T12:00:00Z", "bGF0ZXI"))
            .post("/api/scopes/$scopeId/nodes/md-mem/media").then().statusCode(200)
            .body("mimeType", `is`("image/jpeg")).body("sealedBlob.bytes", `is`("bGF0ZXI"))
        given().header("Authorization", ownerToken).contentType(ContentType.JSON).body(mediaBody("2026-01-01T08:00:00Z", "ZWFybGllcg"))
            .post("/api/scopes/$scopeId/nodes/md-mem/media").then().statusCode(200)

        // the gallery is ordered by takenAt (earliest first), ciphertext only
        given().header("Authorization", ownerToken).get("/api/scopes/$scopeId/nodes/md-mem/media").then().statusCode(200)
            .body("media.size()", `is`(2))
            .body("media[0].sealedBlob.bytes", `is`("ZWFybGllcg"))
            .body("media[1].sealedBlob.bytes", `is`("bGF0ZXI"))

        // outsiders cannot list either
        given().header("Authorization", outsiderToken).get("/api/scopes/$scopeId/nodes/md-mem/media").then().statusCode(403)
    }

    @Test
    fun rejectsOversizedMediaBeforeDecode() {
        // an oversized sealed blob is rejected at the base64 length (no allocation) — DoS guard
        val hugeBlob = "A".repeat(89) // > maxEncodedChars(64)
        given().header("Authorization", ownerToken).contentType(ContentType.JSON)
            .body("""{"takenAt":"2026-01-01T10:00:00Z","mimeType":"image/jpeg","byteSize":1,"sealedBlob":{"suiteId":"v1","bytes":"$hugeBlob"}}""")
            .post("/api/scopes/$scopeId/nodes/md-mem/media").then().statusCode(400).body("code", `is`("MEDIA_TOO_LARGE"))

        // a valid blob with an oversized thumbnail is rejected on the thumbnail
        val hugeThumb = "A".repeat(25) // > maxEncodedChars(16)
        given().header("Authorization", ownerToken).contentType(ContentType.JSON)
            .body("""{"takenAt":"2026-01-01T10:00:00Z","mimeType":"image/jpeg","byteSize":1,"sealedBlob":{"suiteId":"v1","bytes":"c2VhbGVk"},"sealedThumb":{"suiteId":"v1","bytes":"$hugeThumb"}}""")
            .post("/api/scopes/$scopeId/nodes/md-mem/media").then().statusCode(400).body("code", `is`("THUMB_TOO_LARGE"))
    }
}
