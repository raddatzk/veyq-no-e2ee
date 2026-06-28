package com.veyq.app.features.content

import jakarta.enterprise.context.ApplicationScoped

sealed interface CreateNodeOutcome {
    data class Created(val nodeId: String) : CreateNodeOutcome
    object NoScope : CreateNodeOutcome
    object IllegalStructure : CreateNodeOutcome
    object SlugTaken : CreateNodeOutcome
}

sealed interface ListNodesOutcome {
    data class Nodes(val rows: List<NodeRow>) : ListNodesOutcome
    object NoScope : ListNodesOutcome
}

sealed interface UpdateNodeOutcome {
    object Updated : UpdateNodeOutcome
    object NotFound : UpdateNodeOutcome
    object IllegalStructure : UpdateNodeOutcome
    object SlugTaken : UpdateNodeOutcome
}

sealed interface DeleteNodeOutcome {
    object Deleted : DeleteNodeOutcome
    object NoScope : DeleteNodeOutcome
}

/**
 * Use-case logic for the Memory tree (en.memory.tree, cr-0027). Scope access = the caller holds a membership
 * for the scope (operator-blind: a non-member sees a 404, not a reveal). Structural rules: FOLDER/MEMORY live
 * at the root or under a FOLDER; CHAPTER lives inside a MEMORY; no cycle on move; slug unique per parent.
 */
@ApplicationScoped
class NodesHandler(private val store: NodesStore) {

    private fun parentAllows(kind: String, parent: ParentSlot): Boolean = when (kind) {
        NodeEntity.FOLDER, NodeEntity.MEMORY ->
            parent is ParentSlot.Root || (parent is ParentSlot.Node && parent.kind == NodeEntity.FOLDER)
        NodeEntity.CHAPTER -> parent is ParentSlot.Node && parent.kind == NodeEntity.MEMORY
        else -> false
    }

    fun create(
        userId: String, scopeId: String, parentSlotId: String?, kind: String,
        encryptedName: String, slugToken: String, orderIndex: Int, wrappedContentKey: String?,
    ): CreateNodeOutcome {
        if (!store.isMember(userId, scopeId)) return CreateNodeOutcome.NoScope
        val slot = parentSlotId ?: scopeId // null/absent → the scope root (slot = scope id)
        val parent = store.parentSlot(scopeId, slot)
        if (parent is ParentSlot.Missing) return CreateNodeOutcome.IllegalStructure
        if (!parentAllows(kind, parent)) return CreateNodeOutcome.IllegalStructure
        if (store.slugTaken(slot, slugToken)) return CreateNodeOutcome.SlugTaken
        return CreateNodeOutcome.Created(
            store.create(scopeId, slot, kind, encryptedName, slugToken, orderIndex, wrappedContentKey),
        )
    }

    fun list(userId: String, scopeId: String): ListNodesOutcome {
        if (!store.isMember(userId, scopeId)) return ListNodesOutcome.NoScope
        return ListNodesOutcome.Nodes(store.list(scopeId))
    }

    fun update(
        userId: String, scopeId: String, nodeId: String,
        encryptedName: String?, slugToken: String?, newParentSlotId: String?, orderIndex: Int?,
    ): UpdateNodeOutcome {
        if (!store.isMember(userId, scopeId)) return UpdateNodeOutcome.NotFound
        val node = store.findInScope(scopeId, nodeId) ?: return UpdateNodeOutcome.NotFound

        var moveSlot: String? = null
        if (newParentSlotId != null) { // a move; the root is addressed as the scope id, never null
            val parent = store.parentSlot(scopeId, newParentSlotId)
            if (parent is ParentSlot.Missing) return UpdateNodeOutcome.IllegalStructure
            if (!parentAllows(node.kind, parent)) return UpdateNodeOutcome.IllegalStructure
            if (newParentSlotId == nodeId || store.wouldCycle(scopeId, nodeId, newParentSlotId)) {
                return UpdateNodeOutcome.IllegalStructure
            }
            moveSlot = newParentSlotId
        }
        val targetSlot = moveSlot ?: node.parentId
        if (slugToken != null && store.slugTaken(targetSlot, slugToken, exceptNodeId = nodeId)) {
            return UpdateNodeOutcome.SlugTaken
        }
        store.applyUpdate(nodeId, encryptedName, slugToken, moveSlot, orderIndex)
        return UpdateNodeOutcome.Updated
    }

    fun delete(userId: String, scopeId: String, nodeId: String): DeleteNodeOutcome {
        if (!store.isMember(userId, scopeId)) return DeleteNodeOutcome.NoScope
        store.deleteSubtree(scopeId, nodeId)
        return DeleteNodeOutcome.Deleted
    }
}
