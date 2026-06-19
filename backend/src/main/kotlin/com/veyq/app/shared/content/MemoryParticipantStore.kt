package com.veyq.app.shared.content

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

// uc.people.manage-participants — a sealed People payload ({displayName, linkedUserId?}) attached to a
// Memory/Chapter. Many per node. The server stores + returns bytes, but never sees participant names or links.

@Entity
@Table(name = "veyq_memory_participant")
class MemoryParticipant {
    @Id var id: String = ""
    @Column(name = "node_id", nullable = false) var nodeId: String = ""
    @Column(name = "scope_id", nullable = false) var scopeId: String = ""
    @Column(name = "suite_id", nullable = false) var suiteId: String = ""
    @Column(name = "sealed_payload", nullable = false) var sealedPayload: ByteArray = ByteArray(0)
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.EPOCH
}

@ApplicationScoped
class MemoryParticipantRepository : PanacheRepositoryBase<MemoryParticipant, String> {
    fun listByNodeId(nodeId: String): List<MemoryParticipant> = list("nodeId = ?1 order by createdAt", nodeId)
    fun deleteByNodeIds(nodeIds: Collection<String>): Long = if (nodeIds.isEmpty()) 0 else delete("nodeId in ?1", nodeIds)
    fun deleteByScopeId(scopeId: String): Long = delete("scopeId", scopeId)
}
