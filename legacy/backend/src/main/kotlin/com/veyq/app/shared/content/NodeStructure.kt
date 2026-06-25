package com.veyq.app.shared.content

import com.veyq.app.generated.model.NodeType
import com.veyq.app.platform.error.ApiException

/**
 * Tree-shape invariants for the Memory node graph, shared so node-create (MemoryTreeHandler) and node-move
 * (CurateHandler) enforce one source of truth — the containment matrix can't drift between the two paths.
 */
object NodeStructure {
    /** Folder/Memory at root or in a Folder; only a Chapter inside a Memory/Chapter. [verb] phrases the error. */
    fun requireContainment(parentType: NodeType?, childType: NodeType, verb: String) {
        val allowed = when (parentType) {
            null, NodeType.FOLDER -> childType == NodeType.FOLDER || childType == NodeType.MEMORY
            NodeType.MEMORY, NodeType.CHAPTER -> childType == NodeType.CHAPTER
        }
        if (!allowed) {
            val where = parentType?.value ?: "the scope root"
            throw ApiException(400, "INVALID_CONTAINMENT", "A ${childType.value} cannot $verb $where")
        }
    }
}
