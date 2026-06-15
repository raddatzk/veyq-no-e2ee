package com.veyq.app.features.memory.share

import com.veyq.app.generated.model.Blob
import com.veyq.app.generated.model.MemoryNodeList
import com.veyq.app.generated.model.PublicShare
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.content.NodeRepository
import com.veyq.app.shared.content.ShareRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.util.Base64

/**
 * uc.memory.share — guest side (unauthenticated). Resolves a share to its wrapped content key + root node,
 * and lists the shared subtree's nodes (ciphertext). The server never sees the share key or plaintext; it
 * only gates which ciphertext a guest receives to the shared node's subtree.
 */
@ApplicationScoped
class PublicShareTreeHandler(
    private val shares: ShareRepository,
    private val nodes: NodeRepository,
) {
    private val enc = Base64.getUrlEncoder().withoutPadding()

    @Transactional
    fun getPublicShare(shareId: String): PublicShare {
        val share = shares.findById(shareId) ?: throw ApiException(404, "SHARE_NOT_FOUND", "Share not found")
        val root = nodes.findById(share.nodeId) ?: throw ApiException(404, "SHARE_NOT_FOUND", "Shared node is gone")
        return PublicShare(
            shareId = share.id,
            scopeId = share.scopeId,
            rootNodeId = share.nodeId,
            wrappedContentKey = Blob(suiteId = share.suiteId, bytes = enc.encodeToString(share.wrappedContentKey)),
            root = ShareHandler.nodeToApi(root, share.scopeId, enc),
        )
    }

    @Transactional
    fun listShareNodes(shareId: String): MemoryNodeList {
        val share = shares.findById(shareId) ?: throw ApiException(404, "SHARE_NOT_FOUND", "Share not found")
        val all = nodes.listByScopeId(share.scopeId)
        val ids = ShareHandler.subtreeIds(all, share.nodeId)
        return MemoryNodeList(nodes = all.filter { it.id in ids }.map { ShareHandler.nodeToApi(it, share.scopeId, enc) })
    }
}
