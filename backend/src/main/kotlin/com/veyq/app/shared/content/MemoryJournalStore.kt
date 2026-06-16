package com.veyq.app.shared.content

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import java.time.Instant

// uc.memory.journal — one journal per Memory/Chapter. The server stores ciphertext only and never reads it.
//
// Two projections of the same content, both sealed under the scope content key:
//  - `sealedContent`  — the Markdown body every READER uses (detail view, public share). Updated at compaction.
//  - `sealedSnapshot` + the append-only `MemoryJournalUpdate` log — the operator-blind CRDT the EDITOR uses, so
//    non-overlapping sessions converge. A client applies snapshot + updates(seq > baseSeq); appends sealed Yjs
//    updates as it edits; compaction writes a new snapshot covering some seq and prunes the folded-in updates.

@Entity
@Table(name = "veyq_memory_journal")
class MemoryJournal {
    @Id @Column(name = "node_id") var nodeId: String = ""
    @Column(name = "scope_id", nullable = false) var scopeId: String = ""
    @Column(name = "suite_id", nullable = false) var suiteId: String = ""
    @Column(name = "sealed_content") var sealedContent: ByteArray? = null            // Markdown projection (readers)
    @Column(name = "sealed_snapshot") var sealedSnapshot: ByteArray? = null          // Yjs state snapshot (editor)
    @Column(name = "snapshot_suite_id") var snapshotSuiteId: String? = null
    @Column(name = "base_seq", nullable = false) var baseSeq: Long = 0               // seq folded into the snapshot
    @Column(name = "next_seq", nullable = false) var nextSeq: Long = 0               // next seq to assign on append
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.EPOCH
}

@Entity
@Table(name = "veyq_memory_journal_update")
class MemoryJournalUpdate {
    @Id var id: String = ""
    @Column(name = "node_id", nullable = false) var nodeId: String = ""
    @Column(name = "scope_id", nullable = false) var scopeId: String = ""
    @Column(nullable = false) var seq: Long = 0
    @Column(name = "suite_id", nullable = false) var suiteId: String = ""
    @Column(name = "sealed_update", nullable = false) var sealedUpdate: ByteArray = ByteArray(0)
    @Column(name = "author_id", nullable = false) var authorId: String = ""
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.EPOCH
}

@ApplicationScoped
class MemoryJournalRepository : PanacheRepositoryBase<MemoryJournal, String> {
    /** Row-locked fetch so concurrent appends to the same journal hand out distinct, monotonic seq values. */
    fun findByIdForUpdate(nodeId: String): MemoryJournal? =
        find("nodeId", nodeId).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult()

    fun deleteByNodeIds(nodeIds: Collection<String>): Long = if (nodeIds.isEmpty()) 0 else delete("nodeId in ?1", nodeIds)
    fun deleteByScopeId(scopeId: String): Long = delete("scopeId", scopeId)
}

@ApplicationScoped
class MemoryJournalUpdateRepository : PanacheRepositoryBase<MemoryJournalUpdate, String> {
    fun listByNodeId(nodeId: String): List<MemoryJournalUpdate> = list("nodeId = ?1 order by seq", nodeId)
    fun deleteUpToSeq(nodeId: String, seq: Long): Long = delete("nodeId = ?1 and seq <= ?2", nodeId, seq)
    fun deleteByNodeIds(nodeIds: Collection<String>): Long = if (nodeIds.isEmpty()) 0 else delete("nodeId in ?1", nodeIds)
    fun deleteByScopeId(scopeId: String): Long = delete("scopeId", scopeId)
}
