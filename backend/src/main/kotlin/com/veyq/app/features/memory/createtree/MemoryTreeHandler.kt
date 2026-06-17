package com.veyq.app.features.memory.createtree

import com.veyq.app.generated.model.Blob
import com.veyq.app.generated.model.CreateNodeRequest
import com.veyq.app.generated.model.MemoryNode
import com.veyq.app.generated.model.MemoryNodeList
import com.veyq.app.generated.model.NodeType
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.content.MemoryJournal
import com.veyq.app.shared.content.MemoryJournalRepository
import com.veyq.app.shared.content.Node
import com.veyq.app.shared.content.NodeApiMapper
import com.veyq.app.shared.content.NodeStructure
import com.veyq.app.shared.content.NodeRepository
import com.veyq.app.shared.content.ScopeContentKeyRepository
import com.veyq.app.shared.crypto.CryptoSuiteRegistry
import com.veyq.app.shared.security.ScopeAccessGuard
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
    private val nodes: NodeRepository,
    private val contentKeys: ScopeContentKeyRepository,
    private val journals: MemoryJournalRepository,
    private val guard: ScopeAccessGuard,
    private val suites: CryptoSuiteRegistry,
) {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()

    /**
     * No parentId → the full node graph (rotation re-seal, whole-tree reconstruction). parentId = "root" →
     * the scope's root-level children; parentId = <nodeId> → that node's direct children — the level-by-level
     * listing that backs browse. A root node uses the scope id as its parent slot (the DB sentinel).
     */
    @Transactional
    fun listNodes(auth: String?, scopeId: String, parentId: String?): MemoryNodeList {
        requireKeyHolder(scopeId, auth)
        val list = when (parentId) {
            null -> nodes.listByScopeId(scopeId)
            "root" -> nodes.listByParent(scopeId, scopeId)
            else -> nodes.listByParent(scopeId, parentId)
        }
        return MemoryNodeList(nodes = list.map { toApi(it, scopeId) })
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
            parent.type
        }
        NodeStructure.requireContainment(parentType, req.type, "be created under")

        // slug uniqueness among the parent's children (the DB UNIQUE(parent_id, slug_token) is the backstop)
        if (nodes.findByParentAndSlug(parentId, req.slugToken) != null) {
            throw ApiException(409, "SLUG_EXISTS", "A sibling already uses that slug")
        }

        val node = Node().apply {
            id = UUID.randomUUID().toString(); this.scopeId = scopeId; this.parentId = parentId
            type = req.type; slugToken = req.slugToken; orderIndex = req.orderIndex
            suiteId = req.sealedContent.suiteId; sealedContent = dec.decode(req.sealedContent.bytes); createdAt = Instant.now()
        }
        nodes.persist(node)
        // Pre-create the (empty) journal row for a Memory/Chapter at birth, so the first live append never
        // races two concurrent INSERTs of the same PK (node_id) into a unique-violation. An empty row
        // (sealedContent=null, nextSeq=0) reads identically to "no journal yet"; its suiteId is just a
        // placeholder the node was already sealed under (overwritten when real content is compacted in).
        if (req.type != NodeType.FOLDER) {
            journals.persist(
                MemoryJournal().apply {
                    nodeId = node.id; this.scopeId = scopeId; suiteId = req.sealedContent.suiteId; updatedAt = Instant.now()
                },
            )
        }
        return toApi(node, scopeId)
    }

    private fun toApi(node: Node, scopeId: String): MemoryNode = NodeApiMapper.toApi(node, scopeId, enc)

    private fun requireKeyHolder(scopeId: String, auth: String?) {
        guard.requireKeyHolder(auth, scopeId)
    }
}
