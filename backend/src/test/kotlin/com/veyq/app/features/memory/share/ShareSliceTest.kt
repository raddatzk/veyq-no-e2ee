package com.veyq.app.features.memory.share

import com.veyq.app.shared.content.Blob
import com.veyq.app.shared.content.BlobRepository
import com.veyq.app.shared.content.Media
import com.veyq.app.shared.content.MediaRepository
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
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.`is`
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import com.veyq.app.shared.keymgmt.ScopeKind

/** uc.memory.share — create a read-only share, resolve it + its subtree as an unauthenticated guest. */
@QuarkusTest
class ShareSliceTest {

    @Inject lateinit var users: UserRepository
    @Inject lateinit var scopes: ScopeRepository
    @Inject lateinit var grants: ScopeGrantRepository
    @Inject lateinit var contentKeys: ScopeContentKeyRepository
    @Inject lateinit var nodes: NodeRepository
    @Inject lateinit var blobs: BlobRepository
    @Inject lateinit var medias: MediaRepository
    @Inject lateinit var sessions: SessionService

    private lateinit var ownerToken: String
    private lateinit var outsiderToken: String
    private val scopeId = "shr-scope"

    @BeforeTest
    fun seed() {
        QuarkusTransaction.requiringNew().run {
            val now = Instant.now()
            if (users.findByEmail("shr-owner@example.com") != null) return@run
            users.persist(User().apply { id = "shr-owner"; email = "shr-owner@example.com"; displayName = "O"; serverRole = ServerRole.ServerMember; createdAt = now })
            users.persist(User().apply { id = "shr-out"; email = "shr-out@example.com"; displayName = "X"; serverRole = ServerRole.ServerMember; createdAt = now })
            scopes.persist(Scope().apply { id = scopeId; ownerUserId = "shr-owner"; kind = ScopeKind.PERSONAL_ROOT; suiteId = "v1"; kemPublicKey = "p".toByteArray(); createdAt = now })
            grants.persist(ScopeGrant().apply { id = "shr-grant"; this.scopeId = this@ShareSliceTest.scopeId; userId = "shr-owner"; suiteId = "v1"; wrappedPrivateKey = "g".toByteArray(); createdAt = now })
            contentKeys.persist(ScopeContentKey().apply { id = "shr-ck"; this.scopeId = this@ShareSliceTest.scopeId; suiteId = "v1"; wrappedKey = "ck".toByteArray(); createdAt = now })
            // memory mem with a chapter chap (a subtree) + a sibling other memory; mem has a medium
            node("shr-mem", scopeId, "MEMORY", "s-m", 0)
            node("shr-chap", "shr-mem", "CHAPTER", "s-c", 0)
            node("shr-other", scopeId, "MEMORY", "s-o", 1)
            node("shr-folder", scopeId, "FOLDER", "s-f", 2)
            node("shr-mem2", scopeId, "MEMORY", "s-m2", 3) // contributor test (isolated from shr-mem's counts)
            node("shr-mem3", scopeId, "MEMORY", "s-m3", 4) // uploader test
            blobs.persist(Blob().apply { id = "shr-b1"; this.scopeId = this@ShareSliceTest.scopeId; dedupToken = "d1"; totalChunks = 1; byteSize = 9; suiteId = "v1"; createdAt = now })
            medias.persist(Media().apply { id = "shr-med1"; this.scopeId = this@ShareSliceTest.scopeId; nodeId = "shr-mem"; originalBlobId = "shr-b1"; sealedMeta = "m".toByteArray(); suiteId = "v1"; mimeType = "image/png"; createdAt = now })
            // a medium on the sibling memory (must NOT be reachable through the share)
            blobs.persist(Blob().apply { id = "shr-b2"; this.scopeId = this@ShareSliceTest.scopeId; dedupToken = "d2"; totalChunks = 1; byteSize = 9; suiteId = "v1"; createdAt = now })
            medias.persist(Media().apply { id = "shr-med2"; this.scopeId = this@ShareSliceTest.scopeId; nodeId = "shr-other"; originalBlobId = "shr-b2"; sealedMeta = "m".toByteArray(); suiteId = "v1"; mimeType = "image/png"; createdAt = now })
        }
        ownerToken = "Bearer ${sessions.issue("shr-owner")}"
        outsiderToken = "Bearer ${sessions.issue("shr-out")}"
    }

    private fun node(id: String, parentId: String, type: String, slug: String, order: Int) {
        nodes.persist(Node().apply {
            this.id = id; scopeId = "shr-scope"; this.parentId = parentId; this.type = type
            slugToken = slug; orderIndex = order; suiteId = "v1"; sealedContent = "n".toByteArray(); createdAt = Instant.now()
        })
    }

    private val body = """{"wrappedContentKey":{"suiteId":"v1","bytes":"d2Nr"}}"""

    @Test
    fun createsAndResolvesAReadOnlyShare() {
        // outsiders cannot create; folders cannot be shared
        given().header("Authorization", outsiderToken).contentType(ContentType.JSON).body(body)
            .post("/api/scopes/$scopeId/nodes/shr-mem/shares").then().statusCode(403)
        given().header("Authorization", ownerToken).contentType(ContentType.JSON).body(body)
            .post("/api/scopes/$scopeId/nodes/shr-folder/shares").then().statusCode(400).body("code", `is`("NOT_A_MEMORY"))

        // create a share for mem; the shareId is the secret link token
        val shareId = given().header("Authorization", ownerToken).contentType(ContentType.JSON).body(body)
            .post("/api/scopes/$scopeId/nodes/shr-mem/shares").then().statusCode(200)
            .body("nodeId", `is`("shr-mem")).extract().path<String>("shareId")

        // a GUEST (no auth) resolves the share: wrapped key + the sealed root node
        given().get("/api/public/shares/$shareId").then().statusCode(200)
            .body("rootNodeId", `is`("shr-mem")).body("wrappedContentKey.bytes", `is`("d2Nr"))
            .body("root.id", `is`("shr-mem"))

        // the subtree is mem + chap only (the sibling memory + folder are NOT exposed)
        given().get("/api/public/shares/$shareId/nodes").then().statusCode(200)
            .body("nodes.id", org.hamcrest.Matchers.containsInAnyOrder("shr-mem", "shr-chap"))

        // a guest reads mem's media, but cannot reach the sibling memory's node or its blob
        given().get("/api/public/shares/$shareId/nodes/shr-mem/media").then().statusCode(200)
            .body("media.size()", `is`(1)).body("media[0].id", `is`("shr-med1"))
        given().get("/api/public/shares/$shareId/nodes/shr-other/media").then().statusCode(404)
        given().get("/api/public/shares/$shareId/blobs/shr-b2/chunks/0").then().statusCode(404)

        // a VIEWER share rejects guest writes (read-only) but may READ the journal
        given().contentType(ContentType.JSON).body("""{"dedupToken":"x","totalChunks":1,"byteSize":6}""")
            .post("/api/public/shares/$shareId/blobs").then().statusCode(403).body("code", `is`("SHARE_READ_ONLY"))
        given().get("/api/public/shares/$shareId/nodes/shr-mem/journal").then().statusCode(200)
        // ...and the journal CRDT live state (for live-reading owner edits over the relay)
        given().get("/api/public/shares/$shareId/nodes/shr-mem/journal/live").then().statusCode(200)
            .body("baseSeq", `is`(0)).body("updates.size()", `is`(0))
        // a node outside the shared subtree is 404 even for the live state
        given().get("/api/public/shares/$shareId/nodes/shr-other/journal/live").then().statusCode(404)

        // revoke; the share (and guest access) is gone
        given().header("Authorization", ownerToken).delete("/api/scopes/$scopeId/nodes/shr-mem/shares/$shareId").then().statusCode(204)
        given().get("/api/public/shares/$shareId").then().statusCode(404)
    }

    @Test
    fun contributorGuestUploadsMediaAndWritesJournal() {
        // a CONTRIBUTOR share lets a guest contribute (on its own memory, isolated from other tests)
        val shareId = given().header("Authorization", ownerToken).contentType(ContentType.JSON)
            .body("""{"wrappedContentKey":{"suiteId":"v1","bytes":"d2Nr"},"role":"CONTRIBUTOR"}""")
            .post("/api/scopes/$scopeId/nodes/shr-mem2/shares").then().statusCode(200)
            .body("role", `is`("CONTRIBUTOR")).extract().path<String>("shareId")

        // the guest uploads a one-frame blob through the shared blob path (no auth)
        val uploadId = given().contentType(ContentType.JSON).body("""{"dedupToken":"guest-1","totalChunks":1,"byteSize":6}""")
            .post("/api/public/shares/$shareId/blobs").then().statusCode(200).body("status", `is`("UPLOADING"))
            .extract().path<String>("uploadId")
        given().contentType(ContentType.JSON).body("""{"frame":{"suiteId":"v1","bytes":"ZnJhbWUh"}}""")
            .put("/api/public/shares/$shareId/blob-uploads/$uploadId/chunks/0").then().statusCode(204)
        val blobId = given().post("/api/public/shares/$shareId/blob-uploads/$uploadId/complete").then().statusCode(200)
            .extract().path<String>("blobId")

        // and attaches it as media on the shared memory
        given().contentType(ContentType.JSON)
            .body("""{"originalBlobId":"$blobId","sealedMeta":{"suiteId":"v1","bytes":"bWV0YQ"},"mimeType":"image/jpeg"}""")
            .post("/api/public/shares/$shareId/nodes/shr-mem2/contributed-media").then().statusCode(200)
        // the shared gallery now lists the guest's contribution
        given().get("/api/public/shares/$shareId/nodes/shr-mem2/media").then().statusCode(200).body("media.size()", `is`(1))

        // a CONTRIBUTOR may READ the journal but not write it (no PUT route — journal is owner-only)
        given().get("/api/public/shares/$shareId/nodes/shr-mem2/journal").then().statusCode(200)

        // a guest still cannot contribute outside the subtree
        given().contentType(ContentType.JSON)
            .body("""{"originalBlobId":"$blobId","sealedMeta":{"suiteId":"v1","bytes":"bWV0YQ"},"mimeType":"image/jpeg"}""")
            .post("/api/public/shares/$shareId/nodes/shr-other/contributed-media").then().statusCode(404)
    }

    @Test
    fun guestUploadsAreCappedPerShare() {
        val share = given().header("Authorization", ownerToken).contentType(ContentType.JSON)
            .body("""{"wrappedContentKey":{"suiteId":"v1","bytes":"d2Nr"},"role":"CONTRIBUTOR"}""")
            .post("/api/scopes/$scopeId/nodes/shr-mem2/shares").then().statusCode(200).extract().path<String>("shareId")
        // open the per-share guest session cap (default 10) with distinct dedup tokens; the 11th is refused 429
        repeat(10) { i ->
            given().contentType(ContentType.JSON).body("""{"dedupToken":"quota-$i","totalChunks":1,"byteSize":6}""")
                .post("/api/public/shares/$share/blobs").then().statusCode(200)
        }
        given().contentType(ContentType.JSON).body("""{"dedupToken":"quota-over","totalChunks":1,"byteSize":6}""")
            .post("/api/public/shares/$share/blobs").then().statusCode(429).body("code", `is`("TOO_MANY_OPEN_UPLOADS"))
    }

    @Test
    fun guestCannotTouchAnotherSharesInFlightUpload() {
        // two CONTRIBUTOR shares on the SAME scope (different memories) — distinct guests
        fun contributorShareOn(node: String) = given().header("Authorization", ownerToken).contentType(ContentType.JSON)
            .body("""{"wrappedContentKey":{"suiteId":"v1","bytes":"d2Nr"},"role":"CONTRIBUTOR"}""")
            .post("/api/scopes/$scopeId/nodes/$node/shares").then().statusCode(200).extract().path<String>("shareId")
        val shareA = contributorShareOn("shr-mem2")
        val shareB = contributorShareOn("shr-mem3")

        // guest A starts an upload under share A
        val uploadId = given().contentType(ContentType.JSON).body("""{"dedupToken":"a2-guest","totalChunks":1,"byteSize":6}""")
            .post("/api/public/shares/$shareA/blobs").then().statusCode(200).extract().path<String>("uploadId")

        // guest B (share B, same scope) knows A's uploadId but must NOT read/overwrite/finalize it — 404, not a hit
        given().get("/api/public/shares/$shareB/blob-uploads/$uploadId").then().statusCode(404).body("code", `is`("UPLOAD_NOT_FOUND"))
        given().contentType(ContentType.JSON).body("""{"frame":{"suiteId":"v1","bytes":"ZnJhbWUh"}}""")
            .put("/api/public/shares/$shareB/blob-uploads/$uploadId/chunks/0").then().statusCode(404).body("code", `is`("UPLOAD_NOT_FOUND"))
        given().post("/api/public/shares/$shareB/blob-uploads/$uploadId/complete").then().statusCode(404).body("code", `is`("UPLOAD_NOT_FOUND"))

        // the rightful owner (share A) still operates on it normally
        given().contentType(ContentType.JSON).body("""{"frame":{"suiteId":"v1","bytes":"ZnJhbWUh"}}""")
            .put("/api/public/shares/$shareA/blob-uploads/$uploadId/chunks/0").then().statusCode(204)
        given().get("/api/public/shares/$shareA/blob-uploads/$uploadId").then().statusCode(200)
    }

    @Test
    fun passwordExpiryAndLabel() {
        // a password-protected, labelled share with a per-share notice (guests must accept it)
        val shareId = given().header("Authorization", ownerToken).contentType(ContentType.JSON)
            .body("""{"wrappedContentKey":{"suiteId":"v1","bytes":"d2Nr"},"passwordSalt":{"suiteId":"v1","bytes":"c2FsdA"},"label":"For grandma","noticeMarkdown":"## Bitte nur privat teilen"}""")
            .post("/api/scopes/$scopeId/nodes/shr-mem/shares").then().statusCode(200)
            .body("passwordProtected", `is`(true)).body("label", `is`("For grandma")).extract().path<String>("shareId")
        given().get("/api/public/shares/$shareId").then().statusCode(200)
            .body("passwordProtected", `is`(true)).body("passwordSalt.bytes", `is`("c2FsdA"))
            // the per-share notice rides the public resolve as a SHARE-scoped notice for the accept gate
            .body("notices.size()", `is`(1)).body("notices[0].scope", `is`("SHARE"))
            .body("notices[0].markdown", `is`("## Bitte nur privat teilen"))

        // an already-expired share denies all public access (owner can still list it)
        val expired = given().header("Authorization", ownerToken).contentType(ContentType.JSON)
            .body("""{"wrappedContentKey":{"suiteId":"v1","bytes":"d2Nr"},"expiresAt":"2000-01-01T00:00:00Z","label":"old"}""")
            .post("/api/scopes/$scopeId/nodes/shr-mem/shares").then().statusCode(200).extract().path<String>("shareId")
        given().get("/api/public/shares/$expired").then().statusCode(404).body("code", `is`("SHARE_EXPIRED"))

        // edit: deactivate the expiry + rename the label → the link works again, no expiry
        given().header("Authorization", ownerToken).contentType(ContentType.JSON).body("""{"label":"renewed"}""")
            .patch("/api/scopes/$scopeId/nodes/shr-mem/shares/$expired").then().statusCode(200)
            .body("label", `is`("renewed")).body("expiresAt", org.hamcrest.Matchers.nullValue())
        given().get("/api/public/shares/$expired").then().statusCode(200)
    }

    @Test
    fun uploaderGuestUploadsButCannotRead() {
        // an UPLOADER share is a blind drop-off: it may upload but not read the gallery/journal
        val shareId = given().header("Authorization", ownerToken).contentType(ContentType.JSON)
            .body("""{"wrappedContentKey":{"suiteId":"v1","bytes":"d2Nr"},"role":"UPLOADER"}""")
            .post("/api/scopes/$scopeId/nodes/shr-mem3/shares").then().statusCode(200)
            .body("role", `is`("UPLOADER")).extract().path<String>("shareId")

        // resolve works (the uploader sees the memory name + wrapped key to seal under) — but reads are denied
        given().get("/api/public/shares/$shareId").then().statusCode(200).body("role", `is`("UPLOADER"))
        given().get("/api/public/shares/$shareId/nodes").then().statusCode(403).body("code", `is`("SHARE_NO_READ"))
        given().get("/api/public/shares/$shareId/nodes/shr-mem3/media").then().statusCode(403)
        given().get("/api/public/shares/$shareId/nodes/shr-mem3/journal").then().statusCode(403)

        // but uploading a blob + attaching media works
        val uploadId = given().contentType(ContentType.JSON).body("""{"dedupToken":"up-1","totalChunks":1,"byteSize":6}""")
            .post("/api/public/shares/$shareId/blobs").then().statusCode(200).extract().path<String>("uploadId")
        given().contentType(ContentType.JSON).body("""{"frame":{"suiteId":"v1","bytes":"ZnJhbWUh"}}""")
            .put("/api/public/shares/$shareId/blob-uploads/$uploadId/chunks/0").then().statusCode(204)
        val blobId = given().post("/api/public/shares/$shareId/blob-uploads/$uploadId/complete").then().statusCode(200).extract().path<String>("blobId")
        given().contentType(ContentType.JSON)
            .body("""{"originalBlobId":"$blobId","sealedMeta":{"suiteId":"v1","bytes":"bWV0YQ"},"mimeType":"image/jpeg"}""")
            .post("/api/public/shares/$shareId/nodes/shr-mem3/contributed-media").then().statusCode(200)
    }
}
