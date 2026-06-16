package com.veyq.app.features.memory.curate

import com.veyq.app.generated.model.Blob
import com.veyq.app.generated.model.MemoryNode
import com.veyq.app.generated.model.NodeType
import com.veyq.app.generated.model.UpdateNodeRequest
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.content.ContentPurge
import com.veyq.app.shared.content.MediaRepository
import com.veyq.app.shared.content.Node
import com.veyq.app.shared.content.NodeRepository
import com.veyq.app.shared.crypto.CryptoSuiteRegistry
import com.veyq.app.shared.security.ScopeAccessGuard
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64

/**
 * uc.memory.curate — mutate an existing node: rename (re-seal name + slug token), move (new parent + slug
 * token recomputed under it, with containment + cycle + slug-uniqueness re-checks), reorder (order index),
 * or pin a cover (a media of this node). The server stays blind to names; only the structure changes.
 */
@ApplicationScoped
class CurateHandler(
    private val nodes: NodeRepository,
    private val media: MediaRepository,
    private val purge: ContentPurge,
    private val guard: ScopeAccessGuard,
    private val suites: CryptoSuiteRegistry,
) {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()

    @Transactional
    fun updateNode(auth: String?, scopeId: String, nodeId: String, req: UpdateNodeRequest): MemoryNode {
        requireKeyHolder(scopeId, auth)
        val node = nodes.lockById(nodeId) ?: throw ApiException(404, "NODE_NOT_FOUND", "Node not found")
        if (node.scopeId != scopeId) throw ApiException(404, "NODE_NOT_FOUND", "Node not found in this scope")

        when {
            // MOVE: relocate under a new parent (or the root). Slug token must be recomputed under it.
            req.newParentId != null -> {
                val slugToken = req.slugToken ?: throw ApiException(400, "SLUG_TOKEN_REQUIRED", "A move needs a recomputed slug token")
                val newParentId = if (req.newParentId == "root") scopeId else req.newParentId
                val parentType: NodeType? = if (newParentId == scopeId) null else {
                    val parent = nodes.findById(newParentId) ?: throw ApiException(404, "PARENT_NOT_FOUND", "Parent node not found")
                    if (parent.scopeId != scopeId) throw ApiException(400, "CROSS_SCOPE_PARENT", "Parent belongs to another scope")
                    NodeType.valueOf(parent.type)
                }
                requireContainment(parentType, NodeType.valueOf(node.type))
                if (isInSubtree(root = node.id, candidate = newParentId, scopeId = scopeId)) {
                    throw ApiException(409, "NODE_CYCLE", "Cannot move a node into its own subtree")
                }
                val clash = nodes.findByParentAndSlug(newParentId, slugToken)
                if (clash != null && clash.id != node.id) throw ApiException(409, "SLUG_EXISTS", "A sibling already uses that slug")
                node.parentId = newParentId
                node.slugToken = slugToken
                req.orderIndex?.let { node.orderIndex = it }
            }
            // RENAME: new sealed name + slug token, same parent.
            req.sealedContent != null -> {
                if (!suites.isKnown(req.sealedContent.suiteId)) throw ApiException(400, "UNKNOWN_SUITE", "Unknown crypto suite")
                val slugToken = req.slugToken ?: throw ApiException(400, "SLUG_TOKEN_REQUIRED", "A rename needs a recomputed slug token")
                val clash = nodes.findByParentAndSlug(node.parentId, slugToken)
                if (clash != null && clash.id != node.id) throw ApiException(409, "SLUG_EXISTS", "A sibling already uses that slug")
                node.sealedContent = dec.decode(req.sealedContent.bytes)
                node.suiteId = req.sealedContent.suiteId
                node.slugToken = slugToken
                req.orderIndex?.let { node.orderIndex = it }
            }
            // REORDER only.
            req.orderIndex != null -> node.orderIndex = req.orderIndex
        }

        // COVER — can accompany any of the above (or stand alone). Empty string clears it.
        req.coverMediaId?.let { coverId ->
            if (coverId.isEmpty()) {
                node.coverMediaId = null
            } else {
                if (NodeType.valueOf(node.type) == NodeType.FOLDER) throw ApiException(400, "NOT_A_MEMORY", "Only a Memory/Chapter has a cover")
                val m = media.findById(coverId) ?: throw ApiException(404, "MEDIA_NOT_FOUND", "Cover media not found")
                if (m.nodeId != node.id) throw ApiException(400, "COVER_NOT_OWN_MEDIA", "The cover must be a media of this node")
                node.coverMediaId = coverId
            }
        }

        return toApi(node, scopeId)
    }

    /**
     * Delete a node and its whole subtree (descendant nodes + all attached media/journal/tracks/shares).
     * Any key holder of the scope may delete; the confirm-by-name gate is enforced client-side. Irreversible.
     */
    @Transactional
    fun deleteNode(auth: String?, scopeId: String, nodeId: String) {
        requireKeyHolder(scopeId, auth)
        val node = nodes.findById(nodeId) ?: throw ApiException(404, "NODE_NOT_FOUND", "Node not found")
        if (node.scopeId != scopeId) throw ApiException(404, "NODE_NOT_FOUND", "Node not found in this scope")
        purge.purgeNodes(collectSubtree(scopeId, nodeId))
    }

    /** All node ids in the subtree rooted at `rootId` (inclusive), via a single scope read + BFS. */
    private fun collectSubtree(scopeId: String, rootId: String): Set<String> {
        val childrenByParent = nodes.listByScopeId(scopeId).groupBy { it.parentId }
        val result = linkedSetOf(rootId)
        val stack = ArrayDeque(listOf(rootId))
        while (stack.isNotEmpty()) {
            for (child in childrenByParent[stack.removeLast()].orEmpty()) {
                if (result.add(child.id)) stack.addLast(child.id)
            }
        }
        return result
    }

    /** True if `candidate` is `root` itself or one of its descendants (walk up from candidate to the scope root). */
    private fun isInSubtree(root: String, candidate: String, scopeId: String): Boolean {
        var current: String? = candidate
        var hops = 0
        while (current != null && current != scopeId && hops++ < 4096) {
            if (current == root) return true
            current = nodes.findById(current)?.parentId
        }
        return false
    }

    private fun requireContainment(parentType: NodeType?, childType: NodeType) {
        val allowed = when (parentType) {
            null, NodeType.FOLDER -> childType == NodeType.FOLDER || childType == NodeType.MEMORY
            NodeType.MEMORY, NodeType.CHAPTER -> childType == NodeType.CHAPTER
        }
        if (!allowed) {
            val where = parentType?.value ?: "the scope root"
            throw ApiException(400, "INVALID_CONTAINMENT", "A ${childType.value} cannot live under $where")
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
            coverMediaId = node.coverMediaId,
            createdAt = OffsetDateTime.ofInstant(node.createdAt, ZoneOffset.UTC),
        )

    private fun requireKeyHolder(scopeId: String, auth: String?) {
        guard.requireKeyHolder(auth, scopeId)
    }
}
