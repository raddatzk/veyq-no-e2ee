package com.veyq.app.features.media

import com.veyq.app.features.account.login.SessionService
import com.veyq.app.features.admin.tenant.MembershipEntity
import com.veyq.app.features.admin.tenant.TenantEntity
import com.veyq.app.features.content.NodeEntity
import com.veyq.app.features.spaces.scope.ScopeEntity
import com.veyq.app.shared.identity.VeyqUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Slice tests for media blobs (en.memory.media, cr-0028) against the **in-memory store** (dev/test metadata
 * only — no byte transfer; the byte path is the opt-in [BlobBatchE2ETest]). Covers: start→complete→attach→list
 * metadata round-trip; partial-batch per-item result; illegal_target (422-equivalent per-item) on a FOLDER;
 * budget_exceeded on an over-cap declaration; non-member 404; unauth 401; operator-blind (the K_blob wrap
 * round-trips verbatim). UUID-unique seeds so the JVM-lived H2 mem DB can't bleed across tests.
 */
@QuarkusTest
class MediaSliceTest {

    @Inject
    lateinit var sessions: SessionService

    private fun token(userId: String) = "Bearer ${sessions.issue(userId).accessToken}"

    @Test
    fun startCompleteListRoundTrip() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY)
        val uploadId = startOne(userId, scopeId, chunkCount = 2, byteSize = 1024, wrap = "d3JhcHBlZA==")

        // complete + attach to the Memory; the merged listed-metadata tier is listed, EXIF is lazy (cr-0068)
        val mediaId = given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body("""{"items":[{"originalUploadId":"$uploadId","nodeId":"$memory","wrappedMeta":"bWV0YQ==","wrappedExif":"ZXhpZg=="}]}""")
            .`when`().post("/api/scopes/$scopeId/media-batch")
            .then().statusCode(200)
            .body("results[0].blobId", notNullValue())
            .body("results[0].mediaId", notNullValue())
            .body("results[0].error", nullValue())
            .extract().path<String>("results[0].mediaId")

        // list the node's media — operator-blind: the wrap + sealed listed-metadata blob come back verbatim.
        // The heavier EXIF tier is deliberately NOT listed (kept constant-small); it is lazy via getMediaExif.
        given().header("Authorization", token(userId))
            .`when`().get("/api/scopes/$scopeId/nodes/$memory/media")
            .then().statusCode(200)
            .body("[0].wrappedBlobKey", equalTo("d3JhcHBlZA=="))
            .body("[0].chunkCount", equalTo(2))
            .body("[0].byteSize", equalTo(1024))
            .body("[0].wrappedMeta", equalTo("bWV0YQ==")) // the listed-metadata tier is listed (geometry + date + GPS)
            .body("[0].wrappedExif", nullValue()) // the list never carries EXIF

        // the lazy exif endpoint returns the sealed EXIF verbatim (opaque, never inspected)
        given().header("Authorization", token(userId))
            .`when`().get("/api/scopes/$scopeId/media/$mediaId/exif")
            .then().statusCode(200)
            .body("wrappedExif", equalTo("ZXhpZg=="))
    }

    @Test
    fun mediaExifWithoutExifIsNull() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY)
        val uploadId = startOne(userId, scopeId, chunkCount = 1, byteSize = 16, wrap = "b25seQ==")

        // complete with no EXIF sealed (source carried no readable EXIF)
        val mediaId = given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body("""{"items":[{"originalUploadId":"$uploadId","nodeId":"$memory"}]}""")
            .`when`().post("/api/scopes/$scopeId/media-batch")
            .then().statusCode(200)
            .body("results[0].error", nullValue())
            .extract().path<String>("results[0].mediaId")

        given().header("Authorization", token(userId))
            .`when`().get("/api/scopes/$scopeId/media/$mediaId/exif")
            .then().statusCode(200)
            .body("wrappedExif", nullValue())
    }

    @Test
    fun mediaExifForUnknownMediaIs404() {
        val (userId, scopeId) = seedMemberScope()
        given().header("Authorization", token(userId))
            .`when`().get("/api/scopes/$scopeId/media/${UUID.randomUUID()}/exif")
            .then().statusCode(404).body("code", equalTo("media_not_found"))
    }

    @Test
    fun updateMetaOverwritesSealedTierAndLists() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY)
        val uploadId = startOne(userId, scopeId, chunkCount = 1, byteSize = 16, wrap = "b25seQ==")

        // complete with an initial listed-metadata tier (cr-0068)
        val mediaId = given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body("""{"items":[{"originalUploadId":"$uploadId","nodeId":"$memory","wrappedMeta":"bWV0YQ=="}]}""")
            .`when`().post("/api/scopes/$scopeId/media-batch")
            .then().statusCode(200).body("results[0].error", nullValue())
            .extract().path<String>("results[0].mediaId")

        // PUT a re-sealed listed-metadata blob (e.g. a corrected date) — opaque, echoed back verbatim (cr-0068)
        given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body("""{"wrappedMeta":"Y29ycmVjdGVk"}""")
            .`when`().put("/api/scopes/$scopeId/media/$mediaId/meta")
            .then().statusCode(200).body("wrappedMeta", equalTo("Y29ycmVjdGVk"))

        // the overwritten value comes back on list (the tier is always listed)
        given().header("Authorization", token(userId))
            .`when`().get("/api/scopes/$scopeId/nodes/$memory/media")
            .then().statusCode(200).body("[0].wrappedMeta", equalTo("Y29ycmVjdGVk"))

        // null clears it
        given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body("""{"wrappedMeta":null}""")
            .`when`().put("/api/scopes/$scopeId/media/$mediaId/meta")
            .then().statusCode(200).body("wrappedMeta", nullValue())
    }

    @Test
    fun updateMetaForUnknownMediaIs404() {
        val (userId, scopeId) = seedMemberScope()
        given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body("""{"wrappedMeta":"Y29ycmVjdGVk"}""")
            .`when`().put("/api/scopes/$scopeId/media/${UUID.randomUUID()}/meta")
            .then().statusCode(404).body("code", equalTo("media_not_found"))
    }

    @Test
    fun derivativesRoundTripWithOwnWraps() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY)
        // three independent uploads: original + thumbnail + preview, each its own K_blob wrap
        val original = startOne(userId, scopeId, chunkCount = 3, byteSize = 3000, wrap = "b3JpZw==")
        val thumb = startOne(userId, scopeId, chunkCount = 1, byteSize = 100, wrap = "dGh1bWI=")
        val preview = startOne(userId, scopeId, chunkCount = 2, byteSize = 900, wrap = "cHJldg==")

        // complete: one logical media = original + two role-tagged derivatives
        given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body(
                """{"items":[{"nodeId":"$memory","originalUploadId":"$original","derivatives":[""" +
                    """{"kind":"THUMBNAIL","uploadId":"$thumb"},{"kind":"PREVIEW","uploadId":"$preview"}]}]}""",
            )
            .`when`().post("/api/scopes/$scopeId/media-batch")
            .then().statusCode(200)
            .body("results[0].blobId", notNullValue())
            .body("results[0].mediaId", notNullValue())
            .body("results[0].error", nullValue())

        // list: the original tier + both derivative tiers, each with its OWN wrap (no shared K_blob)
        given().header("Authorization", token(userId))
            .`when`().get("/api/scopes/$scopeId/nodes/$memory/media")
            .then().statusCode(200)
            .body("[0].wrappedBlobKey", equalTo("b3JpZw=="))
            .body("[0].chunkCount", equalTo(3))
            .body("[0].derivatives.size()", equalTo(2))
            .body("[0].derivatives.find { it.kind == 'THUMBNAIL' }.wrappedBlobKey", equalTo("dGh1bWI="))
            .body("[0].derivatives.find { it.kind == 'THUMBNAIL' }.chunkCount", equalTo(1))
            .body("[0].derivatives.find { it.kind == 'PREVIEW' }.wrappedBlobKey", equalTo("cHJldg=="))
            .body("[0].derivatives.find { it.kind == 'PREVIEW' }.chunkCount", equalTo(2))
    }

    @Test
    fun mediaWithoutDerivativesListsEmptyTiers() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY)
        val original = startOne(userId, scopeId, chunkCount = 1, byteSize = 16, wrap = "b25seQ==")

        // a media with no derivable frame attaches the original only
        given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body("""{"items":[{"nodeId":"$memory","originalUploadId":"$original"}]}""")
            .`when`().post("/api/scopes/$scopeId/media-batch")
            .then().statusCode(200)
            .body("results[0].error", nullValue())

        given().header("Authorization", token(userId))
            .`when`().get("/api/scopes/$scopeId/nodes/$memory/media")
            .then().statusCode(200)
            .body("[0].wrappedBlobKey", equalTo("b25seQ=="))
            .body("[0].derivatives.size()", equalTo(0))
            .body("[0].wrappedMeta", nullValue()) // none sealed → null (the grid uses a default aspect)
    }

    @Test
    fun derivativeUploadNotFoundFailsWholeItem() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY)
        val original = startOne(userId, scopeId, chunkCount = 1, byteSize = 16, wrap = "b3JpZw==")
        val bogus = UUID.randomUUID()

        // a missing derivative session fails the whole item atomically — nothing is attached
        given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body(
                """{"items":[{"nodeId":"$memory","originalUploadId":"$original",""" +
                    """"derivatives":[{"kind":"THUMBNAIL","uploadId":"$bogus"}]}]}""",
            )
            .`when`().post("/api/scopes/$scopeId/media-batch")
            .then().statusCode(200)
            .body("results[0].error", equalTo("upload_not_found"))
            .body("results[0].mediaId", nullValue())

        given().header("Authorization", token(userId))
            .`when`().get("/api/scopes/$scopeId/nodes/$memory/media")
            .then().statusCode(200)
            .body("size()", equalTo(0))
    }

    @Test
    fun partialBatchPerItemResult() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY)
        val uploadId = startOne(userId, scopeId, chunkCount = 1, byteSize = 16, wrap = "aw==")
        val bogus = UUID.randomUUID()

        given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body("""{"items":[{"originalUploadId":"$uploadId","nodeId":"$memory"},{"originalUploadId":"$bogus","nodeId":"$memory"}]}""")
            .`when`().post("/api/scopes/$scopeId/media-batch")
            .then().statusCode(200)
            .body("results[0].mediaId", notNullValue())
            .body("results[1].error", equalTo("upload_not_found"))
            .body("results[1].mediaId", nullValue())
    }

    @Test
    fun mediaOnFolderIsIllegalTarget() {
        val (userId, scopeId) = seedMemberScope()
        val folder = seedNode(scopeId, NodeEntity.FOLDER)
        val uploadId = startOne(userId, scopeId, chunkCount = 1, byteSize = 16, wrap = "aw==")

        given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body("""{"items":[{"originalUploadId":"$uploadId","nodeId":"$folder"}]}""")
            .`when`().post("/api/scopes/$scopeId/media-batch")
            .then().statusCode(200)
            .body("results[0].error", equalTo("illegal_target"))
            .body("results[0].blobId", nullValue())
    }

    @Test
    fun overCapDeclarationIsBudgetExceeded() {
        val (userId, scopeId) = seedMemberScope()
        // byteSize beyond the default per-blob cap (2 GiB) — declare-then-verify rejects at start
        given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body("""{"items":[{"chunkCount":1,"byteSize":9999999999,"wrappedBlobKey":"aw=="}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-uploads-batch")
            .then().statusCode(200)
            .body("results[0].error", equalTo("budget_exceeded"))
            .body("results[0].uploadId", nullValue())
    }

    @Test
    fun nonMemberIs404() {
        val (_, scopeId) = seedMemberScope()
        val outsider = seedUser()
        given().header("Authorization", token(outsider)).contentType(ContentType.JSON)
            .body("""{"items":[{"chunkCount":1,"byteSize":16,"wrappedBlobKey":"aw=="}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-uploads-batch")
            .then().statusCode(404).body("code", equalTo("scope_not_found"))
    }

    @Test
    fun unauthenticatedIs401() {
        given().contentType(ContentType.JSON)
            .body("""{"items":[{"chunkCount":1,"byteSize":16,"wrappedBlobKey":"aw=="}]}""")
            .`when`().post("/api/scopes/${UUID.randomUUID()}/blob-uploads-batch")
            .then().statusCode(401).body("code", equalTo("authentication_failed"))
    }

    // --- helpers ---

    /** Start one blob upload (in-memory store: presignedPuts come back null) and return its uploadId. */
    private fun startOne(userId: String, scopeId: String, chunkCount: Int, byteSize: Long, wrap: String): String =
        given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body("""{"items":[{"chunkCount":$chunkCount,"byteSize":$byteSize,"wrappedBlobKey":"$wrap"}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-uploads-batch")
            .then().statusCode(200)
            .body("results[0].uploadId", notNullValue())
            .body("results[0].presignedPuts", nullValue())
            .extract().path("results[0].uploadId")

    private fun seedNode(scopeId: String, kind: String): String {
        val id = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            NodeEntity().apply {
                this.id = id; this.scopeId = scopeId; parentId = scopeId; this.kind = kind
                encryptedName = "bg=="; slugToken = "c2x1Zy0$id".take(120); orderIndex = 0
                wrappedContentKey = if (kind == NodeEntity.MEMORY) "a21lbQ==" else null; createdAt = Instant.now()
            }.persist()
        }
        return id
    }

    /** Seed a user + an ACTIVE tenant + a PERSONAL scope the user is a member of. Returns (userId, scopeId). */
    private fun seedMemberScope(): Pair<String, String> {
        val userId = seedUser()
        val tenantId = UUID.randomUUID().toString()
        val scopeId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            TenantEntity().apply {
                id = tenantId; name = "Fam"; domain = "t-$tenantId.example.com"
                ownerEmail = "o-$tenantId@example.com"; status = TenantEntity.ACTIVE; createdAt = Instant.now()
            }.persist()
            ScopeEntity().apply {
                id = scopeId; this.tenantId = tenantId; kind = ScopeEntity.PERSONAL; createdAt = Instant.now()
            }.persist()
            MembershipEntity().apply {
                id = UUID.randomUUID().toString(); this.userId = userId; this.tenantId = tenantId
                this.scopeId = scopeId; role = MembershipEntity.OWNER; wrappedGrant = "Z3JhbnQ="; createdAt = Instant.now()
            }.persist()
        }
        return userId to scopeId
    }

    private fun seedUser(): String {
        val userId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            VeyqUser().apply {
                id = userId; email = "u-$userId@example.com"; serverRole = "MEMBER"
                suiteId = "v1"; identityPublicKey = "aWQ="; wrappedIdentityPrivateKey = "aXA="
                authPublicKey = "YXA="; createdAt = Instant.now()
            }.persist()
        }
        return userId
    }
}
