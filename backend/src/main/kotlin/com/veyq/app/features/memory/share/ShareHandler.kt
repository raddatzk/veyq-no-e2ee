package com.veyq.app.features.memory.share

import com.veyq.app.generated.model.Blob
import com.veyq.app.generated.model.CreateShareRequest
import com.veyq.app.generated.model.MemoryNode
import com.veyq.app.generated.model.NodeType
import com.veyq.app.generated.model.ShareList
import com.veyq.app.generated.model.ShareRole
import com.veyq.app.generated.model.ShareSummary
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.content.Node
import com.veyq.app.shared.content.NodeApiMapper
import com.veyq.app.shared.content.NodeRepository
import com.veyq.app.shared.content.Share
import com.veyq.app.shared.content.ShareRepository
import com.veyq.app.shared.crypto.CryptoSuiteRegistry
import com.veyq.app.shared.security.ScopeAccessGuard
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.security.SecureRandom
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64

/**
 * uc.memory.share — owner side of read-only public sharing. A key holder creates a share for a Memory
 * subtree: the scope content key wrapped under a per-share key (which rides the link fragment, never the
 * server). The server stores ciphertext + a long unguessable shareId, and stays blind to the content.
 */
@ApplicationScoped
class ShareHandler(
    private val nodes: NodeRepository,
    private val shares: ShareRepository,
    private val guard: ScopeAccessGuard,
    private val suites: CryptoSuiteRegistry,
) {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()
    private val rng = SecureRandom()

    @Transactional
    fun listShares(auth: String?, scopeId: String, nodeId: String): ShareList {
        requireKeyHolder(scopeId, auth)
        requireShareNode(scopeId, nodeId)
        return ShareList(shares = shares.listByNodeId(nodeId).map(::toSummary))
    }

    @Transactional
    fun createShare(auth: String?, scopeId: String, nodeId: String, req: CreateShareRequest): ShareSummary {
        val userId = requireKeyHolder(scopeId, auth)
        requireShareNode(scopeId, nodeId)
        if (!suites.isKnown(req.wrappedContentKey.suiteId)) throw ApiException(400, "UNKNOWN_SUITE", "Unknown crypto suite")
        val bytes = ByteArray(32).also(rng::nextBytes)
        val share = Share().apply {
            id = enc.encodeToString(bytes); this.scopeId = scopeId; this.nodeId = nodeId
            suiteId = req.wrappedContentKey.suiteId; wrappedContentKey = dec.decode(req.wrappedContentKey.bytes)
            role = (req.role ?: ShareRole.VIEWER).value
            passwordSalt = req.passwordSalt?.let { dec.decode(it.bytes) }
            expiresAt = req.expiresAt?.toInstant()
            label = (req.label ?: "").take(120)
            noticeMarkdown = req.noticeMarkdown?.trim()?.take(20_000)?.ifEmpty { null }
            createdBy = userId; createdAt = Instant.now()
        }
        shares.persist(share)
        return toSummary(share)
    }

    @Transactional
    fun updateShare(auth: String?, scopeId: String, nodeId: String, shareId: String, req: com.veyq.app.generated.model.UpdateShareRequest): ShareSummary {
        requireKeyHolder(scopeId, auth)
        val share = ownShare(scopeId, nodeId, shareId)
        // Editable server-side fields only — the wrapped key, role and password are immutable.
        share.expiresAt = req.expiresAt?.toInstant() // omit = deactivate expiry
        share.label = (req.label ?: "").take(120)
        share.noticeMarkdown = req.noticeMarkdown?.trim()?.take(20_000)?.ifEmpty { null }
        return toSummary(share)
    }

    @Transactional
    fun revokeShare(auth: String?, scopeId: String, nodeId: String, shareId: String) {
        requireKeyHolder(scopeId, auth)
        shares.delete(ownShare(scopeId, nodeId, shareId))
    }

    private fun ownShare(scopeId: String, nodeId: String, shareId: String): Share {
        val share = shares.findById(shareId) ?: throw ApiException(404, "SHARE_NOT_FOUND", "Share not found")
        if (share.scopeId != scopeId || share.nodeId != nodeId) throw ApiException(404, "SHARE_NOT_FOUND", "Share not found here")
        return share
    }

    private fun requireShareNode(scopeId: String, nodeId: String) {
        val node = nodes.findById(nodeId) ?: throw ApiException(404, "NODE_NOT_FOUND", "Node not found")
        if (node.scopeId != scopeId) throw ApiException(404, "NODE_NOT_FOUND", "Node not found in this scope")
        if (node.type == NodeType.FOLDER) throw ApiException(400, "NOT_A_MEMORY", "Only a Memory/Chapter can be shared")
    }

    private fun toSummary(s: Share): ShareSummary =
        ShareSummary(
            shareId = s.id, nodeId = s.nodeId, role = ShareRole.valueOf(s.role),
            passwordProtected = s.passwordSalt != null,
            expiresAt = s.expiresAt?.let { OffsetDateTime.ofInstant(it, ZoneOffset.UTC) },
            label = s.label,
            noticeMarkdown = s.noticeMarkdown,
            createdAt = OffsetDateTime.ofInstant(s.createdAt, ZoneOffset.UTC),
        )

    private fun requireKeyHolder(scopeId: String, auth: String?): String =
        guard.requireKeyHolder(auth, scopeId)

    companion object {
        // Share-role permissions. VIEWER reads; CONTRIBUTOR reads + writes; UPLOADER is a blind drop-off —
        // it may add media but cannot read the gallery/journal or write the journal.
        fun canRead(role: String) = role == ShareRole.VIEWER.value || role == ShareRole.CONTRIBUTOR.value
        fun canUpload(role: String) = role == ShareRole.CONTRIBUTOR.value || role == ShareRole.UPLOADER.value
        fun isExpired(share: Share) = share.expiresAt?.isBefore(Instant.now()) == true

        /** The shared node's subtree = the root id + every descendant (BFS over the scope's nodes). */
        fun subtreeIds(all: List<Node>, rootId: String): Set<String> {
            val childrenOf = all.groupBy { it.parentId }
            val ids = mutableSetOf(rootId)
            val stack = ArrayDeque(listOf(rootId))
            while (stack.isNotEmpty()) {
                val cur = stack.removeLast()
                for (c in childrenOf[cur].orEmpty()) if (ids.add(c.id)) stack.addLast(c.id)
            }
            return ids
        }

        fun nodeToApi(node: Node, scopeId: String, enc: Base64.Encoder): MemoryNode =
            NodeApiMapper.toApi(node, scopeId, enc)
    }
}
