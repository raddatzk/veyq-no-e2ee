package com.veyq.app.features.memory.journal

import com.veyq.app.generated.model.AppendJournalUpdateRequest
import com.veyq.app.generated.model.AppendJournalUpdateResponse
import com.veyq.app.generated.model.Blob
import com.veyq.app.generated.model.CompactJournalRequest
import com.veyq.app.generated.model.JournalLiveState
import com.veyq.app.generated.model.JournalUpdate
import com.veyq.app.generated.model.MemoryJournal as ApiMemoryJournal
import com.veyq.app.generated.model.NodeType
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.content.MemoryJournal
import com.veyq.app.shared.content.MemoryJournalRepository
import com.veyq.app.shared.content.MemoryJournalUpdate
import com.veyq.app.shared.content.MemoryJournalUpdateRepository
import com.veyq.app.shared.content.NodeRepository
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
 * uc.memory.journal — a Memory/Chapter journal, stored operator-blind in two sealed projections of the same
 * content (the server never reads either):
 *  - the Markdown body (`getJournal`/`getJournalAs`) every READER uses — detail view + public share;
 *  - the editable CRDT (`getJournalLive` snapshot + `appendJournalUpdate` log, compacted by `compactJournal`)
 *    so non-overlapping editing sessions converge without a server-held plaintext document. Editors apply the
 *    snapshot + updates(seq > baseSeq), append sealed Yjs updates, and periodically compact (writing a fresh
 *    snapshot + the Markdown projection and pruning folded updates). Compaction is the only Markdown writer,
 *    so the reader projection can't diverge from the CRDT.
 */
@ApplicationScoped
class JournalHandler(
    private val nodes: NodeRepository,
    private val journals: MemoryJournalRepository,
    private val updates: MemoryJournalUpdateRepository,
    private val guard: ScopeAccessGuard,
    private val suites: CryptoSuiteRegistry,
) {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()

    // ---- Markdown read projection (readers) ----

    @Transactional
    fun getJournal(auth: String?, scopeId: String, nodeId: String): ApiMemoryJournal {
        guard.requireKeyHolder(auth, scopeId)
        return getJournalAs(scopeId, nodeId)
    }

    /** Read the Markdown projection for an already-authorized reader (a key holder or a public share). */
    @Transactional
    fun getJournalAs(scopeId: String, nodeId: String): ApiMemoryJournal {
        requireJournalNode(scopeId, nodeId)
        val journal = journals.findById(nodeId)
        return if (journal == null) ApiMemoryJournal(nodeId = nodeId) else toApi(journal)
    }

    // ---- operator-blind CRDT (editors) ----

    @Transactional
    fun getJournalLive(auth: String?, scopeId: String, nodeId: String): JournalLiveState {
        guard.requireKeyHolder(auth, scopeId)
        return getJournalLiveAs(scopeId, nodeId)
    }

    /** The CRDT state for an already-authorized reader (a key holder or a read-capable public share). */
    @Transactional
    fun getJournalLiveAs(scopeId: String, nodeId: String): JournalLiveState {
        requireJournalNode(scopeId, nodeId)
        val journal = journals.findById(nodeId)
        val snapshot = journal?.sealedSnapshot?.let { Blob(suiteId = journal.snapshotSuiteId ?: "v1", bytes = enc.encodeToString(it)) }
        val log = updates.listByNodeId(nodeId).map {
            JournalUpdate(seq = it.seq, sealedUpdate = Blob(suiteId = it.suiteId, bytes = enc.encodeToString(it.sealedUpdate)), authorId = it.authorId)
        }
        return JournalLiveState(snapshot = snapshot, baseSeq = journal?.baseSeq ?: 0, updates = log)
    }

    @Transactional
    fun appendJournalUpdate(auth: String?, scopeId: String, nodeId: String, req: AppendJournalUpdateRequest): AppendJournalUpdateResponse {
        val userId = guard.requireKeyHolder(auth, scopeId)
        requireJournalNode(scopeId, nodeId)
        suites.require(req.sealedUpdate.suiteId)
        val journal = lockedJournal(scopeId, nodeId, req.sealedUpdate.suiteId)
        val seq = journal.nextSeq + 1
        journal.nextSeq = seq
        journal.updatedAt = Instant.now()
        updates.persist(
            MemoryJournalUpdate().apply {
                id = UUID.randomUUID().toString(); this.nodeId = nodeId; this.scopeId = scopeId
                this.seq = seq; suiteId = req.sealedUpdate.suiteId; sealedUpdate = dec.decode(req.sealedUpdate.bytes)
                authorId = userId; createdAt = Instant.now()
            },
        )
        return AppendJournalUpdateResponse(seq = seq)
    }

    @Transactional
    fun compactJournal(auth: String?, scopeId: String, nodeId: String, req: CompactJournalRequest): ApiMemoryJournal {
        guard.requireKeyHolder(auth, scopeId)
        requireJournalNode(scopeId, nodeId)
        suites.require(req.snapshot.suiteId)
        suites.require(req.markdown.suiteId)
        val journal = lockedJournal(scopeId, nodeId, req.markdown.suiteId)
        if (req.coveredSeq > journal.nextSeq) throw ApiException(400, "BAD_SEQ", "coveredSeq exceeds the latest update")
        journal.suiteId = req.markdown.suiteId
        journal.sealedContent = dec.decode(req.markdown.bytes)
        journal.snapshotSuiteId = req.snapshot.suiteId
        journal.sealedSnapshot = dec.decode(req.snapshot.bytes)
        journal.baseSeq = req.coveredSeq
        journal.updatedAt = Instant.now()
        updates.deleteUpToSeq(nodeId, req.coveredSeq)
        return toApi(journal)
    }

    /** Row-locked journal (created on first write) so concurrent appends get distinct, monotonic seqs. */
    private fun lockedJournal(scopeId: String, nodeId: String, fallbackSuiteId: String): MemoryJournal =
        journals.findByIdForUpdate(nodeId) ?: MemoryJournal().apply {
            this.nodeId = nodeId; this.scopeId = scopeId; suiteId = fallbackSuiteId; updatedAt = Instant.now()
            journals.persist(this)
        }

    private fun requireJournalNode(scopeId: String, nodeId: String) {
        val node = nodes.findById(nodeId) ?: throw ApiException(404, "NODE_NOT_FOUND", "Node not found")
        if (node.scopeId != scopeId) throw ApiException(404, "NODE_NOT_FOUND", "Node not found in this scope")
        if (NodeType.valueOf(node.type) == NodeType.FOLDER) throw ApiException(400, "NOT_A_MEMORY", "Only a Memory/Chapter has a journal")
    }


    private fun toApi(journal: MemoryJournal): ApiMemoryJournal =
        ApiMemoryJournal(
            nodeId = journal.nodeId,
            sealedContent = journal.sealedContent?.let { Blob(suiteId = journal.suiteId, bytes = enc.encodeToString(it)) },
            updatedAt = OffsetDateTime.ofInstant(journal.updatedAt, ZoneOffset.UTC),
        )
}
