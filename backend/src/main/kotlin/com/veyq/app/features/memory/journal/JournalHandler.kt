package com.veyq.app.features.memory.journal

import com.veyq.app.generated.model.Blob
import com.veyq.app.generated.model.MemoryJournal as ApiMemoryJournal
import com.veyq.app.generated.model.NodeType
import com.veyq.app.generated.model.UpdateMemoryJournalRequest
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.content.MemoryJournal
import com.veyq.app.shared.content.MemoryJournalRepository
import com.veyq.app.shared.content.NodeRepository
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

/**
 * uc.memory.journal — read and replace a Memory's journal: a Markdown body sealed client-side under the
 * scope content key. The server stores ciphertext + a timestamp only; it never sees the journal text.
 * Journals live only on a Memory or Chapter (not a Folder).
 */
@ApplicationScoped
class JournalHandler(
    private val scopes: ScopeRepository,
    private val grants: ScopeGrantRepository,
    private val nodes: NodeRepository,
    private val journals: MemoryJournalRepository,
    private val memberships: MembershipRepository,
    private val sessions: SessionService,
    private val suites: CryptoSuiteRegistry,
) {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()

    @Transactional
    fun getJournal(auth: String?, scopeId: String, nodeId: String): ApiMemoryJournal {
        requireKeyHolder(scopeId, auth)
        return getJournalAs(scopeId, nodeId)
    }

    @Transactional
    fun getJournalAs(scopeId: String, nodeId: String): ApiMemoryJournal {
        requireJournalNode(scopeId, nodeId)
        val journal = journals.findById(nodeId)
        return if (journal == null) ApiMemoryJournal(nodeId = nodeId) else toApi(journal)
    }

    @Transactional
    fun updateJournal(auth: String?, scopeId: String, nodeId: String, req: UpdateMemoryJournalRequest): ApiMemoryJournal {
        requireKeyHolder(scopeId, auth)
        return updateJournalAs(scopeId, nodeId, req)
    }

    /** Replace a journal for an already-authorized writer (a session key holder or a CONTRIBUTOR share). */
    @Transactional
    fun updateJournalAs(scopeId: String, nodeId: String, req: UpdateMemoryJournalRequest): ApiMemoryJournal {
        requireJournalNode(scopeId, nodeId)
        if (!suites.isKnown(req.sealedContent.suiteId)) throw ApiException(400, "UNKNOWN_SUITE", "Unknown crypto suite")
        val journal = journals.findById(nodeId) ?: MemoryJournal().apply { this.nodeId = nodeId; this.scopeId = scopeId }
        journal.scopeId = scopeId
        journal.suiteId = req.sealedContent.suiteId
        journal.sealedContent = dec.decode(req.sealedContent.bytes)
        journal.updatedAt = Instant.now()
        journals.persist(journal)
        return toApi(journal)
    }

    private fun requireJournalNode(scopeId: String, nodeId: String) {
        val node = nodes.findById(nodeId) ?: throw ApiException(404, "NODE_NOT_FOUND", "Node not found")
        if (node.scopeId != scopeId) throw ApiException(404, "NODE_NOT_FOUND", "Node not found in this scope")
        if (NodeType.valueOf(node.type) == NodeType.FOLDER) throw ApiException(400, "NOT_A_MEMORY", "Only a Memory/Chapter has a journal")
    }

    private fun toApi(journal: MemoryJournal): ApiMemoryJournal =
        ApiMemoryJournal(
            nodeId = journal.nodeId,
            sealedContent = Blob(suiteId = journal.suiteId, bytes = enc.encodeToString(journal.sealedContent)),
            updatedAt = OffsetDateTime.ofInstant(journal.updatedAt, ZoneOffset.UTC),
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
