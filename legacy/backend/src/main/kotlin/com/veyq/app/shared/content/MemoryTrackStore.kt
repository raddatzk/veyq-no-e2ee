package com.veyq.app.shared.content

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

// uc.memory.track — a sealed GPX route ({name, points} sealed under the scope content key) attached to a
// Memory/Chapter. Many per node. The server stores and returns the bytes but never reads them.

@Entity
@Table(name = "veyq_memory_track")
class MemoryTrack {
    @Id var id: String = ""
    @Column(name = "node_id", nullable = false) var nodeId: String = ""
    @Column(name = "scope_id", nullable = false) var scopeId: String = ""
    @Column(name = "suite_id", nullable = false) var suiteId: String = ""
    @Column(name = "sealed_content", nullable = false) var sealedContent: ByteArray = ByteArray(0)
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.EPOCH
}

@ApplicationScoped
class MemoryTrackRepository : PanacheRepositoryBase<MemoryTrack, String> {
    fun listByNodeId(nodeId: String): List<MemoryTrack> = list("nodeId = ?1 order by createdAt", nodeId)
    fun deleteByNodeIds(nodeIds: Collection<String>): Long = if (nodeIds.isEmpty()) 0 else delete("nodeId in ?1", nodeIds)
    fun deleteByScopeId(scopeId: String): Long = delete("scopeId", scopeId)
}
