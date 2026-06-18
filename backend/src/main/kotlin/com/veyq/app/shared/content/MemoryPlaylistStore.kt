package com.veyq.app.shared.content

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

// uc.memory.playlist (ADR 0010, operator-blind) — a sealed streaming-playlist link ({provider, url, title?,
// note?} sealed under the scope content key) attached to a Memory/Chapter. Many per node; the benign `featured`
// flag is server-visible. The server stores + returns the bytes but never reads them.

@Entity
@Table(name = "veyq_memory_playlist")
class MemoryPlaylist {
    @Id var id: String = ""
    @Column(name = "node_id", nullable = false) var nodeId: String = ""
    @Column(name = "scope_id", nullable = false) var scopeId: String = ""
    @Column(name = "suite_id", nullable = false) var suiteId: String = ""
    @Column(name = "sealed_content", nullable = false) var sealedContent: ByteArray = ByteArray(0)
    @Column(nullable = false) var featured: Boolean = false
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.EPOCH
}

@ApplicationScoped
class MemoryPlaylistRepository : PanacheRepositoryBase<MemoryPlaylist, String> {
    fun listByNodeId(nodeId: String): List<MemoryPlaylist> = list("nodeId = ?1 order by featured desc, createdAt", nodeId)
    /** Clear the featured flag on every other playlist of a node (at most one featured per node). */
    fun clearFeatured(nodeId: String, exceptId: String): Int = update("featured = false where nodeId = ?1 and id <> ?2 and featured = true", nodeId, exceptId)
    fun deleteByNodeIds(nodeIds: Collection<String>): Long = if (nodeIds.isEmpty()) 0 else delete("nodeId in ?1", nodeIds)
    fun deleteByScopeId(scopeId: String): Long = delete("scopeId", scopeId)
}
