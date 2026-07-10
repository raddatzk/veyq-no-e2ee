package com.veyq.app.features.sharing

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
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Slice tests for the editable curator note on a share (uc.sharing.share-note, cr-0115, ADR 0036): create with a
 * note → the anonymous resolve returns it; the owner edits it **in place** (`PATCH`) → the resolve reflects the
 * new note at the **same shareId** (wrap/role unchanged); a null note clears it; a **non-owner** edit is a
 * non-enumerable 404; a share created without a note resolves with a null note and the owner's manage list carries
 * the recoverable `sealed_share_key`. Operator-blind: the server stores/serves only the opaque ciphertexts.
 */
@QuarkusTest
class ShareNoteSliceTest {

    @Inject
    lateinit var sessions: SessionService

    private fun token(userId: String) = "Bearer ${sessions.issue(userId).accessToken}"

    private val wrap = "d3JhcC1rLW1lbS11bmRlci1zaGFyZQ=="
    private val sealedName = "c2VhbGVkLW5hbWU="
    private val note1 = "bm90ZS1vbmU=" // "note-one"
    private val note2 = "bm90ZS10d28=" // "note-two"
    private val shareKey = "cmVjb3ZlcmFibGUta2V5" // symWrap(K_mem, K_share), opaque here

    @Test
    fun createWithNoteThenAnonymousResolveReturnsIt() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedMemory(scopeId)
        val shareId = createWithNote(userId, memory, note1)

        given().`when`().get("/api/shares/$shareId")
            .then().statusCode(200).body("sealedNote", equalTo(note1))
    }

    @Test
    fun ownerEditsTheNoteInPlaceAtTheSameShareId() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedMemory(scopeId)
        val shareId = createWithNote(userId, memory, note1)

        given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body("""{"sealedNote":"$note2"}""")
            .`when`().patch("/api/shares/$shareId")
            .then().statusCode(204)

        // same link (shareId), new note; the grant (wrap) + role are untouched
        given().`when`().get("/api/shares/$shareId")
            .then().statusCode(200)
            .body("sealedNote", equalTo(note2))
            .body("wrap", equalTo(wrap))
            .body("role", equalTo("VIEWER"))
    }

    @Test
    fun editingWithNullClearsTheNote() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedMemory(scopeId)
        val shareId = createWithNote(userId, memory, note1)

        given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body("""{"sealedNote":null}""")
            .`when`().patch("/api/shares/$shareId")
            .then().statusCode(204)

        given().`when`().get("/api/shares/$shareId").then().statusCode(200).body("sealedNote", equalTo(null))
    }

    @Test
    fun aNonOwnerEditIs404AndLeavesTheNoteUnchanged() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedMemory(scopeId)
        val shareId = createWithNote(userId, memory, note1)
        val outsider = seedUser()

        given().header("Authorization", token(outsider)).contentType(ContentType.JSON)
            .body("""{"sealedNote":"$note2"}""")
            .`when`().patch("/api/shares/$shareId")
            .then().statusCode(404).body("code", equalTo("share_not_found"))

        given().`when`().get("/api/shares/$shareId").then().statusCode(200).body("sealedNote", equalTo(note1))
    }

    @Test
    fun createWithoutNoteResolvesNullAndTheManageListCarriesTheRecoverableKey() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedMemory(scopeId)
        // a share with the recoverable key but no note yet
        val shareId = given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body("""{"nodeId":"$memory","role":"VIEWER","wrap":"$wrap","sealedName":"$sealedName","sealedShareKey":"$shareKey"}""")
            .`when`().post("/api/shares")
            .then().statusCode(201).extract().path<String>("shareId")

        given().`when`().get("/api/shares/$shareId").then().statusCode(200).body("sealedNote", equalTo(null))

        // the owner's manage list carries the recoverable key (so it can later recover K_share to add/edit a note)
        given().header("Authorization", token(userId))
            .`when`().get("/api/shares")
            .then().statusCode(200)
            .body("find { it.shareId == '$shareId' }.sealedShareKey", equalTo(shareKey))
    }

    // --- helpers ---

    private fun createWithNote(userId: String, nodeId: String, note: String): String =
        given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body("""{"nodeId":"$nodeId","role":"VIEWER","wrap":"$wrap","sealedName":"$sealedName","sealedNote":"$note","sealedShareKey":"$shareKey"}""")
            .`when`().post("/api/shares")
            .then().statusCode(201).extract().path("shareId")

    private fun seedMemory(scopeId: String): String {
        val id = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            NodeEntity().apply {
                this.id = id; this.scopeId = scopeId; parentId = scopeId; kind = NodeEntity.MEMORY
                encryptedName = "bg=="; slugToken = "c2x1Zy0$id".take(120); orderIndex = 0
                wrappedContentKey = "a21lbQ=="; createdAt = Instant.now()
            }.persist()
        }
        return id
    }

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
