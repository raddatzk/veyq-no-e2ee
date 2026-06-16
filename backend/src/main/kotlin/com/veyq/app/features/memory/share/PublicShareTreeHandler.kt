package com.veyq.app.features.memory.share

import com.veyq.app.generated.model.Blob
import com.veyq.app.generated.model.MemoryJournal
import com.veyq.app.generated.model.MemoryNodeList
import com.veyq.app.generated.model.PublicShare
import com.veyq.app.generated.model.ShareNotice
import com.veyq.app.generated.model.ShareRole
import com.veyq.app.features.memory.journal.JournalHandler
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.content.NodeRepository
import com.veyq.app.shared.content.Share
import com.veyq.app.shared.content.ShareRepository
import com.veyq.app.shared.keymgmt.ScopeRepository
import com.veyq.app.shared.tenancy.TenantRepository
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
    private val journals: JournalHandler,
    private val scopes: ScopeRepository,
    private val tenants: TenantRepository,
) {
    private val enc = Base64.getUrlEncoder().withoutPadding()

    /** Consent notices a guest must accept: the tenant ToS (group memories) + this share's extra notice. */
    private fun notices(share: Share): List<ShareNotice> {
        val out = mutableListOf<ShareNotice>()
        val tenantId = scopes.findById(share.scopeId)?.tenantId
        if (tenantId != null) {
            val tenant = tenants.findById(tenantId)
            if (tenant?.termsMarkdown != null) out.add(ShareNotice(scope = ShareNotice.Scope.TENANT, version = tenant.termsVersion, markdown = tenant.termsMarkdown!!))
        }
        share.noticeMarkdown?.let { out.add(ShareNotice(scope = ShareNotice.Scope.SHARE, version = 1, markdown = it)) }
        return out
    }

    @Transactional
    fun getPublicShare(shareId: String): PublicShare {
        val share = shareOr404(shareId)
        val root = nodes.findById(share.nodeId) ?: throw ApiException(404, "SHARE_NOT_FOUND", "Shared node is gone")
        return PublicShare(
            shareId = share.id,
            scopeId = share.scopeId,
            rootNodeId = share.nodeId,
            role = ShareRole.valueOf(share.role),
            passwordProtected = share.passwordSalt != null,
            passwordSalt = share.passwordSalt?.let { Blob(suiteId = "v1", bytes = enc.encodeToString(it)) },
            notices = notices(share),
            // The content key is required even for a blind UPLOADER (it seals its own contribution), but the
            // root node carries the sealed name — metadata an uploader must not see. Withhold it for non-readers.
            wrappedContentKey = Blob(suiteId = share.suiteId, bytes = enc.encodeToString(share.wrappedContentKey)),
            root = if (ShareHandler.canRead(share.role)) ShareHandler.nodeToApi(root, share.scopeId, enc) else null,
        )
    }

    @Transactional
    fun listShareNodes(shareId: String): MemoryNodeList {
        val share = shareOr404(shareId)
        if (!ShareHandler.canRead(share.role)) throw ApiException(403, "SHARE_NO_READ", "This share cannot read content")
        val all = nodes.listByScopeId(share.scopeId)
        val ids = ShareHandler.subtreeIds(all, share.nodeId)
        return MemoryNodeList(nodes = all.filter { it.id in ids }.map { ShareHandler.nodeToApi(it, share.scopeId, enc) })
    }

    // Journal is READ-ONLY over a share — only the owner edits it (via the authed endpoint).
    @Transactional
    fun getShareJournal(shareId: String, nodeId: String): MemoryJournal {
        val share = shareOr404(shareId)
        if (!ShareHandler.canRead(share.role)) throw ApiException(403, "SHARE_NO_READ", "This share cannot read content")
        requireSubtree(share, nodeId)
        return journals.getJournalAs(share.scopeId, nodeId)
    }

    private fun shareOr404(shareId: String): Share {
        val share = shares.findById(shareId) ?: throw ApiException(404, "SHARE_NOT_FOUND", "Share not found")
        if (ShareHandler.isExpired(share)) throw ApiException(404, "SHARE_EXPIRED", "This share has expired")
        return share
    }

    private fun requireSubtree(share: Share, nodeId: String) {
        val ids = ShareHandler.subtreeIds(nodes.listByScopeId(share.scopeId), share.nodeId)
        if (nodeId !in ids) throw ApiException(404, "NODE_NOT_FOUND", "Node not in this share")
    }
}
