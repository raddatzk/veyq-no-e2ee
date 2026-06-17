package com.veyq.app.features.delete

import com.veyq.app.generated.model.NodeType
import com.veyq.app.shared.content.Blob
import com.veyq.app.shared.content.BlobRepository
import com.veyq.app.shared.content.Media
import com.veyq.app.shared.content.MediaDerivative
import com.veyq.app.shared.content.MediaDerivativeRepository
import com.veyq.app.shared.content.MediaRepository
import com.veyq.app.shared.content.MemoryJournal
import com.veyq.app.shared.content.MemoryJournalRepository
import com.veyq.app.shared.content.MemoryTrack
import com.veyq.app.shared.content.MemoryTrackRepository
import com.veyq.app.shared.content.Node
import com.veyq.app.shared.content.NodeRepository
import com.veyq.app.shared.content.ScopeContentKey
import com.veyq.app.shared.content.ScopeContentKeyRepository
import com.veyq.app.shared.content.Share
import com.veyq.app.shared.content.ShareRepository
import com.veyq.app.shared.identity.ServerRole
import com.veyq.app.shared.identity.User
import com.veyq.app.shared.identity.UserRepository
import com.veyq.app.shared.keymgmt.Scope
import com.veyq.app.shared.keymgmt.ScopeGrant
import com.veyq.app.shared.keymgmt.ScopeGrantRepository
import com.veyq.app.shared.keymgmt.ScopeRepository
import com.veyq.app.shared.security.SessionService
import com.veyq.app.shared.tenancy.Membership
import com.veyq.app.shared.tenancy.MembershipRepository
import com.veyq.app.shared.tenancy.Tenant
import com.veyq.app.shared.tenancy.TenantRepository
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import com.veyq.app.shared.tenancy.TenantStatus
import com.veyq.app.shared.tenancy.MembershipStatus
import com.veyq.app.shared.tenancy.MemberRole
import com.veyq.app.shared.keymgmt.ScopeKind

/** uc.delete — recursive cascade delete of a node subtree, a single medium, and a whole group (tenant). */
@QuarkusTest
class DeleteSliceTest {
    @Inject lateinit var users: UserRepository
    @Inject lateinit var scopes: ScopeRepository
    @Inject lateinit var grants: ScopeGrantRepository
    @Inject lateinit var contentKeys: ScopeContentKeyRepository
    @Inject lateinit var nodes: NodeRepository
    @Inject lateinit var blobs: BlobRepository
    @Inject lateinit var medias: MediaRepository
    @Inject lateinit var derivatives: MediaDerivativeRepository
    @Inject lateinit var journals: MemoryJournalRepository
    @Inject lateinit var tracks: MemoryTrackRepository
    @Inject lateinit var shares: ShareRepository
    @Inject lateinit var tenants: TenantRepository
    @Inject lateinit var memberships: MembershipRepository
    @Inject lateinit var tenantHandler: com.veyq.app.features.admin.createtenantinviteowner.TenantProvisioningHandler
    @Inject lateinit var sessions: SessionService

    private lateinit var ownerToken: String
    private lateinit var outsiderToken: String
    private val scopeId = "del-scope"
    private val tenantId = "del-tenant"
    private val tenantScopeId = "del-tscope"

    @BeforeTest
    fun seed() {
        QuarkusTransaction.requiringNew().run {
            if (users.findByEmail("del-owner@example.com") != null) return@run
            val now = Instant.now()
            users.persist(User().apply { id = "del-owner"; email = "del-owner@example.com"; displayName = "O"; serverRole = ServerRole.ServerMember; createdAt = now })
            users.persist(User().apply { id = "del-out"; email = "del-out@example.com"; displayName = "X"; serverRole = ServerRole.ServerMember; createdAt = now })

            // --- personal scope with a subtree: fa(folder) > mm(memory) > cc(chapter); media/journal/track/share hang off it
            scopes.persist(Scope().apply { id = scopeId; ownerUserId = "del-owner"; kind = ScopeKind.PERSONAL_ROOT; suiteId = "v1"; kemPublicKey = "p".toByteArray(); createdAt = now })
            grants.persist(ScopeGrant().apply { id = "del-grant"; this.scopeId = this@DeleteSliceTest.scopeId; userId = "del-owner"; suiteId = "v1"; wrappedPrivateKey = "g".toByteArray(); createdAt = now })
            contentKeys.persist(ScopeContentKey().apply { id = "del-ck"; this.scopeId = this@DeleteSliceTest.scopeId; suiteId = "v1"; wrappedKey = "ck".toByteArray(); createdAt = now })
            node("fa", scopeId, "FOLDER", "s-fa", scopeId)
            node("mm", "fa", "MEMORY", "s-mm", scopeId)
            node("cc", "mm", "CHAPTER", "s-cc", scopeId)
            node("keep", scopeId, "FOLDER", "s-keep", scopeId) // a sibling that must survive
            blobs.persist(Blob().apply { id = "b"; this.scopeId = this@DeleteSliceTest.scopeId; dedupToken = "d"; totalChunks = 1; byteSize = 9; suiteId = "v1"; createdAt = now })
            media("med1", "mm", scopeId)
            media("med2", "cc", scopeId)
            derivatives.persist(MediaDerivative().apply { id = "der1"; mediaId = "med1"; kind = "THUMBNAIL"; blobId = "b" })
            journals.persist(MemoryJournal().apply { nodeId = "mm"; this.scopeId = this@DeleteSliceTest.scopeId; suiteId = "v1"; sealedContent = "j".toByteArray(); updatedAt = now })
            tracks.persist(MemoryTrack().apply { id = "trk1"; nodeId = "mm"; this.scopeId = this@DeleteSliceTest.scopeId; suiteId = "v1"; sealedContent = "t".toByteArray(); createdAt = now })
            shares.persist(Share().apply { id = "shr1"; this.scopeId = this@DeleteSliceTest.scopeId; nodeId = "mm"; suiteId = "v1"; wrappedContentKey = "w".toByteArray(); role = "VIEWER"; label = ""; createdBy = "del-owner"; createdAt = now })
            // an isolated memory + media for the single-media-delete test (so the subtree test can't destroy it first)
            node("mmx", "keep", "MEMORY", "s-mmx", scopeId)
            media("medx", "mmx", scopeId)
            derivatives.persist(MediaDerivative().apply { id = "derx"; mediaId = "medx"; kind = "THUMBNAIL"; blobId = "b" })

            // --- a group (tenant) with its own scope + a node, an Owner and a plain Member
            tenants.persist(Tenant().apply { id = tenantId; name = "del-grp"; domain = "del-grp.test"; displayName = "G"; status = TenantStatus.ACTIVE; termsVersion = 0; createdBy = "del-owner"; createdAt = now })
            memberships.persist(Membership().apply { id = "mb-o"; this.tenantId = this@DeleteSliceTest.tenantId; userId = "del-owner"; role = MemberRole.Owner; status = MembershipStatus.ACTIVE; createdAt = now })
            memberships.persist(Membership().apply { id = "mb-m"; this.tenantId = this@DeleteSliceTest.tenantId; userId = "del-out"; role = MemberRole.Member; status = MembershipStatus.ACTIVE; createdAt = now })
            scopes.persist(Scope().apply { id = tenantScopeId; this.tenantId = this@DeleteSliceTest.tenantId; kind = ScopeKind.TENANT_ROOT; suiteId = "v1"; kemPublicKey = "p".toByteArray(); createdAt = now })
            grants.persist(ScopeGrant().apply { id = "del-tgrant"; this.scopeId = tenantScopeId; userId = "del-owner"; suiteId = "v1"; wrappedPrivateKey = "g".toByteArray(); createdAt = now })
            node("tn", tenantScopeId, "FOLDER", "s-tn", tenantScopeId)
        }
        ownerToken = "Bearer ${sessions.issue("del-owner")}"
        outsiderToken = "Bearer ${sessions.issue("del-out")}"
    }

    private fun node(id: String, parentId: String, type: String, slug: String, scope: String) {
        nodes.persist(Node().apply {
            this.id = id; scopeId = scope; this.parentId = parentId; this.type = NodeType.valueOf(type)
            slugToken = slug; orderIndex = 0; suiteId = "v1"; sealedContent = "n".toByteArray(); createdAt = Instant.now()
        })
    }

    private fun media(id: String, nodeId: String, scope: String) {
        medias.persist(Media().apply {
            this.id = id; scopeId = scope; this.nodeId = nodeId; originalBlobId = "b"
            sealedMeta = "m".toByteArray(); suiteId = "v1"; mimeType = "image/png"; createdAt = Instant.now()
        })
    }

    @Test
    fun deletesNodeSubtreeRecursivelyButSparesSiblings() {
        // outsiders (no grant) cannot delete
        given().header("Authorization", outsiderToken).delete("/api/scopes/$scopeId/nodes/fa").then().statusCode(403)

        given().header("Authorization", ownerToken).delete("/api/scopes/$scopeId/nodes/fa").then().statusCode(204)

        QuarkusTransaction.requiringNew().run {
            // fa + mm + cc gone; the sibling survives
            assertNull(nodes.findById("fa"))
            assertNull(nodes.findById("mm"))
            assertNull(nodes.findById("cc"))
            assertEquals("keep", nodes.findById("keep")?.id)
            // all content under the subtree gone
            assertNull(medias.findById("med1"))
            assertNull(medias.findById("med2"))
            assertNull(derivatives.findById("der1"))
            assertNull(journals.findById("mm"))
            assertNull(tracks.findById("trk1"))
            assertNull(shares.findById("shr1"))
        }
    }

    @Test
    fun deletesSingleMediumAndItsDerivatives() {
        given().header("Authorization", ownerToken).delete("/api/scopes/$scopeId/nodes/mmx/media/medx").then().statusCode(204)
        QuarkusTransaction.requiringNew().run {
            assertNull(medias.findById("medx"))
            assertNull(derivatives.findById("derx"))
            assertEquals("mmx", nodes.findById("mmx")?.id) // the node itself survives
        }
    }

    @Test
    fun schedulesTenantDeletionOwnerOnlyThenCancelsAndGcPurges() {
        // a plain member cannot schedule deletion
        given().header("Authorization", outsiderToken).delete("/api/tenants/$tenantId").then().statusCode(403)

        // owner schedules → tenant + scope stay (usable during the grace period), deadline is set
        given().header("Authorization", ownerToken).delete("/api/tenants/$tenantId").then().statusCode(204)
        QuarkusTransaction.requiringNew().run {
            assertNotNull(tenants.findById(tenantId)?.scheduledDeletionAt)
            assertNotNull(scopes.findById(tenantScopeId))
        }

        // owner cancels → no longer scheduled
        given().header("Authorization", ownerToken).post("/api/tenants/$tenantId/cancel-deletion").then().statusCode(204)
        QuarkusTransaction.requiringNew().run { assertNull(tenants.findById(tenantId)?.scheduledDeletionAt) }

        // re-schedule, then the GC (deadline passed) hard-purges the tenant + its scope
        given().header("Authorization", ownerToken).delete("/api/tenants/$tenantId").then().statusCode(204)
        val due = Instant.now().plus(40, ChronoUnit.DAYS)
        tenantHandler.dueTenantIds(due).forEach { tenantHandler.purgeTenant(it) }
        QuarkusTransaction.requiringNew().run {
            assertNull(tenants.findById(tenantId))
            assertNull(scopes.findById(tenantScopeId))
            assertNull(nodes.findById("tn"))
            assertNull(grants.find(tenantScopeId, "del-owner"))
            assertEquals(0, memberships.listByTenant(tenantId).size)
        }
    }
}
