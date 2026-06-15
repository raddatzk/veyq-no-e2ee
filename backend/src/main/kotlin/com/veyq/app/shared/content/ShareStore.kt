package com.veyq.app.shared.content

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

// uc.memory.share — a read-only public link to a Memory subtree. The scope content key is wrapped under a
// per-share key (the share key rides the link fragment, never the server); the server stays blind and only
// gates which ciphertext a guest may fetch (the shared node's subtree).

@Entity
@Table(name = "veyq_share")
class Share {
    @Id var id: String = ""
    @Column(name = "scope_id", nullable = false) var scopeId: String = ""
    @Column(name = "node_id", nullable = false) var nodeId: String = ""
    @Column(name = "suite_id", nullable = false) var suiteId: String = ""
    @Column(name = "wrapped_content_key", nullable = false) var wrappedContentKey: ByteArray = ByteArray(0)
    @Column(nullable = false) var role: String = "VIEWER"
    @Column(name = "created_by", nullable = false) var createdBy: String = ""
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.EPOCH
}

@ApplicationScoped
class ShareRepository : PanacheRepositoryBase<Share, String> {
    fun listByNodeId(nodeId: String): List<Share> = list("nodeId = ?1 order by createdAt", nodeId)
}
