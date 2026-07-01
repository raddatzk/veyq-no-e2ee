package com.veyq.app.features.content

import com.veyq.app.features.admin.tenant.MembershipEntity
import com.veyq.app.features.spaces.scope.ScopeEntity
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant
import java.util.UUID

/** A node row (parentId is the parent slot — node id, or the scope id at the root). */
data class NodeRow(
    val id: String,
    val parentId: String,
    val kind: String,
    val encryptedName: String,
    val slugToken: String,
    val orderIndex: Int,
    val wrappedContentKey: String?,
    val wrappedMeta: String?,
)

/** What a parent slot resolves to: the scope root, an existing node's kind, or nothing (foreign/missing). */
sealed interface ParentSlot {
    object Root : ParentSlot
    data class Node(val kind: String) : ParentSlot
    object Missing : ParentSlot
}

/**
 * Persistence for the Memory tree (cr-0027). Operator-blind: stores ciphertext + the blinded slug token only.
 * Scope access = the caller holds a membership for the scope (cr-0026 scope identity).
 */
@ApplicationScoped
class NodesStore {

    fun isMember(userId: String, scopeId: String): Boolean =
        MembershipEntity.count("userId = ?1 and scopeId = ?2", userId, scopeId) > 0

    fun scopeExists(scopeId: String): Boolean = ScopeEntity.findById(scopeId) != null

    /** Resolve a parent slot within the scope: the scope id (root), a node in the same scope, or missing. */
    fun parentSlot(scopeId: String, parentSlot: String): ParentSlot {
        if (parentSlot == scopeId) return ParentSlot.Root
        val n = NodeEntity.findById(parentSlot) ?: return ParentSlot.Missing
        return if (n.scopeId == scopeId) ParentSlot.Node(n.kind) else ParentSlot.Missing
    }

    fun slugTaken(parentSlot: String, slugToken: String, exceptNodeId: String? = null): Boolean =
        NodeEntity.count("parentId = ?1 and slugToken = ?2 and id <> ?3", parentSlot, slugToken, exceptNodeId ?: "") > 0

    fun findInScope(scopeId: String, nodeId: String): NodeEntity? =
        NodeEntity.findById(nodeId)?.takeIf { it.scopeId == scopeId }

    /** Would moving `nodeId` under `newParentSlot` create a cycle (the slot is the node or a descendant)? */
    fun wouldCycle(scopeId: String, nodeId: String, newParentSlot: String): Boolean {
        var cur = newParentSlot
        while (cur != scopeId) {
            if (cur == nodeId) return true
            val p = NodeEntity.findById(cur) ?: return false
            if (p.scopeId != scopeId) return false
            cur = p.parentId
        }
        return false
    }

    @Transactional
    fun create(
        scopeId: String, parentSlot: String, kind: String, encryptedName: String,
        slugToken: String, orderIndex: Int, wrappedContentKey: String?, wrappedMeta: String?,
    ): String {
        val id = UUID.randomUUID().toString()
        NodeEntity().apply {
            this.id = id
            this.scopeId = scopeId
            this.parentId = parentSlot
            this.kind = kind
            this.encryptedName = encryptedName
            this.slugToken = slugToken
            this.orderIndex = orderIndex
            this.wrappedContentKey = wrappedContentKey
            this.wrappedMeta = wrappedMeta
            createdAt = Instant.now()
        }.persist()
        return id
    }

    fun list(scopeId: String): List<NodeRow> =
        NodeEntity.find("scopeId", scopeId).list().map {
            NodeRow(it.id, it.parentId, it.kind, it.encryptedName, it.slugToken, it.orderIndex, it.wrappedContentKey, it.wrappedMeta)
        }

    /** Re-load the node **inside** the transaction (the one the handler validated may be detached) and mutate
     *  it — the managed entity is flushed on commit; no `persist()` of a detached instance. */
    @Transactional
    fun applyUpdate(
        nodeId: String, encryptedName: String?, slugToken: String?, newParentSlot: String?, orderIndex: Int?,
        wrappedMeta: String?,
    ) {
        val node = NodeEntity.findById(nodeId) ?: return
        encryptedName?.let { node.encryptedName = it }
        slugToken?.let { node.slugToken = it }
        newParentSlot?.let { node.parentId = it }
        orderIndex?.let { node.orderIndex = it }
        wrappedMeta?.let { node.wrappedMeta = it } // partial: present replaces, omitted/null leaves unchanged
    }

    /** Delete the node + its whole subtree (within the scope). Idempotent. */
    @Transactional
    fun deleteSubtree(scopeId: String, nodeId: String) {
        val all = NodeEntity.find("scopeId", scopeId).list()
        val byParent = all.groupBy { it.parentId }
        val doomed = mutableListOf<String>()
        val stack = ArrayDeque(listOf(nodeId))
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            doomed += cur
            byParent[cur]?.forEach { stack.addLast(it.id) }
        }
        if (doomed.isNotEmpty()) NodeEntity.delete("id in ?1", doomed)
    }
}
