package com.veyq.app.shared.content

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

// uc.memory.journal — one journal per Memory/Chapter: a sealed Markdown body (ciphertext under the scope
// content key) plus a last-updated timestamp. The server stores and returns the bytes but never reads them.

@Entity
@Table(name = "veyq_memory_journal")
class MemoryJournal {
    @Id @Column(name = "node_id") var nodeId: String = ""
    @Column(name = "scope_id", nullable = false) var scopeId: String = ""
    @Column(name = "suite_id", nullable = false) var suiteId: String = ""
    @Column(name = "sealed_content", nullable = false) var sealedContent: ByteArray = ByteArray(0)
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.EPOCH
}

@ApplicationScoped
class MemoryJournalRepository : PanacheRepositoryBase<MemoryJournal, String> {
    fun deleteByNodeIds(nodeIds: Collection<String>): Long = if (nodeIds.isEmpty()) 0 else delete("nodeId in ?1", nodeIds)
    fun deleteByScopeId(scopeId: String): Long = delete("scopeId", scopeId)
}
