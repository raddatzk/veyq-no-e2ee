package com.veyq.app.shared.content

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

// The encrypted Memory tree (AD-26). A node is a Folder/Memory/Chapter under a scope; the server holds
// only the ID graph, the structural type, a blinded slug token, an order index and the sealed name.
// A root-level node stores the SCOPE id in parent_id (the root sentinel) so slug uniqueness holds at root.

@Entity
@Table(name = "veyq_node")
class Node {
    @Id var id: String = ""
    @Column(name = "scope_id", nullable = false) var scopeId: String = ""
    @Column(name = "parent_id", nullable = false) var parentId: String = ""
    @Column(nullable = false) var type: String = ""
    @Column(name = "slug_token", nullable = false) var slugToken: String = ""
    @Column(name = "order_index", nullable = false) var orderIndex: Int = 0
    @Column(name = "suite_id", nullable = false) var suiteId: String = ""
    @Column(name = "sealed_content", nullable = false) var sealedContent: ByteArray = ByteArray(0)
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.EPOCH
}

@ApplicationScoped
class NodeRepository : PanacheRepositoryBase<Node, String> {
    fun listByScopeId(scopeId: String): List<Node> =
        list("scopeId = ?1 order by orderIndex, createdAt", scopeId)

    /** Direct children of a parent within a scope (scope-scoped so a foreign parent id can't leak nodes). */
    fun listByParent(scopeId: String, parentId: String): List<Node> =
        list("scopeId = ?1 and parentId = ?2 order by orderIndex, createdAt", scopeId, parentId)

    fun findByParentAndSlug(parentId: String, slugToken: String): Node? =
        find("parentId = ?1 and slugToken = ?2", parentId, slugToken).firstResult()
}
