package com.veyq.app.features.memory.createtree

import com.veyq.app.generated.model.Blob
import com.veyq.app.generated.model.CreateNodeRequest
import com.veyq.app.generated.model.MemoryNode
import com.veyq.app.generated.model.MemoryNodeList
import com.veyq.app.generated.model.NodeType
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.content.Node
import com.veyq.app.shared.content.NodeRepository
import com.veyq.app.shared.content.ScopeContentKeyRepository
import com.veyq.app.shared.crypto.CryptoSuiteRegistry
import com.veyq.app.shared.keymgmt.ScopeGrantRepository
import com.veyq.app.shared.keymgmt.ScopeRepository
import com.veyq.app.shared.security.SessionService
import com.veyq.app.shared.tenancy.MembershipRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

/**
 * uc.memory.create-tree — the encrypted Memory tree (AD-26). Create Folders, Memories and Chapters under a
 * scope and list its node graph. The server enforces the structure (containment invariants + slug
 * uniqueness per parent) and stores only the ID graph, types, blinded slug tokens, order and ciphertext.
 *
 * A root-level node stores the SCOPE id in parent_id (the root sentinel) so UNIQUE(parent_id, slug_token)
 * holds at the root too; the API exposes parentId = null for those.
 */
@ApplicationScoped
class MemoryTreeHandler(
    private val scopes: ScopeRepository,
    private val grants: ScopeGrantRepository,
    private val nodes: NodeRepository,
    private val contentKeys: ScopeContentKeyRepository,
    private val memberships: MembershipRepository,
    private val sessions: SessionService,
    private val suites: CryptoSuiteRegistry,
) {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()

    @Transactional
    fun listNodes(auth: String?, scopeId: String): MemoryNodeList {
        requireKeyHolder(scopeId, auth)
        return MemoryNodeList(nodes = nodes.listByScopeId(scopeId).map { toApi(it, scopeId) })
    }

    @Transactional
    fun createNode(auth: String?, scopeId: String, req: CreateNodeRequest): MemoryNode {
        requireKeyHolder(scopeId, auth)
        if (!suites.isKnown(req.sealedContent.suiteId)) throw ApiException(400, "UNKNOWN_SUITE", "Unknown crypto suite")
        // a node is sealed under the scope content key, so one must exist — this keeps the invariant
        // "content-sealed object ⇒ content key" true, which is what scope rotation relies on to re-seal nodes.
        contentKeys.findByScopeId(scopeId) ?: throw ApiException(409, "CONTENT_KEY_REQUIRED", "Provision the scope content key before creating nodes")

        // resolve the parent: absent → root (the scope id is the root sentinel); else it must be a node in
        // the SAME scope. The parent's type (or null for root) drives the containment check below.
        val parentId = req.parentId ?: scopeId
        val parentType: NodeType? = if (parentId == scopeId) {
            null
        } else {
            val parent = nodes.findById(parentId) ?: throw ApiException(404, "PARENT_NOT_FOUND", "Parent node not found")
            if (parent.scopeId != scopeId) throw ApiException(400, "CROSS_SCOPE_PARENT", "Parent belongs to another scope")
            NodeType.valueOf(parent.type)
        }
        requireContainment(parentType, req.type)

        // slug uniqueness among the parent's children (the DB UNIQUE(parent_id, slug_token) is the backstop)
        if (nodes.findByParentAndSlug(parentId, req.slugToken) != null) {
            throw ApiException(409, "SLUG_EXISTS", "A sibling already uses that slug")
        }

        val node = Node().apply {
            id = UUID.randomUUID().toString(); this.scopeId = scopeId; this.parentId = parentId
            type = req.type.value; slugToken = req.slugToken; orderIndex = req.orderIndex
            suiteId = req.sealedContent.suiteId; sealedContent = dec.decode(req.sealedContent.bytes); createdAt = Instant.now()
        }
        nodes.persist(node)
        return toApi(node, scopeId)
    }

    /** Containment invariants: Folder/Memory at root or in a Folder; only a Chapter inside a Memory/Chapter. */
    private fun requireContainment(parentType: NodeType?, childType: NodeType) {
        val allowed = when (parentType) {
            null, NodeType.FOLDER -> childType == NodeType.FOLDER || childType == NodeType.MEMORY
            NodeType.MEMORY, NodeType.CHAPTER -> childType == NodeType.CHAPTER
        }
        if (!allowed) {
            val where = parentType?.value ?: "the scope root"
            throw ApiException(400, "INVALID_CONTAINMENT", "A ${childType.value} cannot be created under $where")
        }
    }

    private fun toApi(node: Node, scopeId: String): MemoryNode =
        MemoryNode(
            id = node.id,
            parentId = if (node.parentId == scopeId) null else node.parentId,
            scopeId = node.scopeId,
            type = NodeType.valueOf(node.type),
            slugToken = node.slugToken,
            orderIndex = node.orderIndex,
            sealedContent = Blob(suiteId = node.suiteId, bytes = enc.encodeToString(node.sealedContent)),
            createdAt = OffsetDateTime.ofInstant(node.createdAt, ZoneOffset.UTC),
        )

    private fun requireKeyHolder(scopeId: String, auth: String?) {
        val userId = sessions.resolve(auth) ?: throw ApiException(401, "UNAUTHORIZED", "Missing or invalid session")
        val scope = scopes.findById(scopeId) ?: throw ApiException(404, "NOT_FOUND", "Scope not found")
        grants.find(scope.id, userId) ?: throw ApiException(403, "NOT_A_KEY_HOLDER", "You do not hold this scope")
        val tenantId = scope.tenantId
        if (tenantId != null && memberships.find(tenantId, userId)?.status != "ACTIVE") {
            throw ApiException(403, "NOT_A_MEMBER", "You are not an active member of this tenant")
        }
    }
}
