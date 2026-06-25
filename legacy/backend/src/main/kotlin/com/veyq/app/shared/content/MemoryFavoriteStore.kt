package com.veyq.app.shared.content

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "veyq_memory_favorite")
class MemoryFavorite {
    @Id var id: String = ""
    @Column(name = "user_id", nullable = false) var userId: String = ""
    @Column(name = "scope_id", nullable = false) var scopeId: String = ""
    @Column(name = "node_id", nullable = false) var nodeId: String = ""
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.EPOCH
}

@ApplicationScoped
class MemoryFavoriteRepository : PanacheRepositoryBase<MemoryFavorite, String> {
    fun find(userId: String, nodeId: String): MemoryFavorite? =
        find("userId = ?1 and nodeId = ?2", userId, nodeId).firstResult()

    fun listNodeIds(userId: String, scopeId: String): List<String> =
        list("userId = ?1 and scopeId = ?2 order by createdAt desc", userId, scopeId).map { it.nodeId }

    fun deleteByUserAndNode(userId: String, nodeId: String): Long =
        delete("userId = ?1 and nodeId = ?2", userId, nodeId)

    fun deleteByNodeIds(nodeIds: Collection<String>): Long =
        if (nodeIds.isEmpty()) 0 else delete("nodeId in ?1", nodeIds)

    fun deleteByScopeId(scopeId: String): Long = delete("scopeId", scopeId)
}
