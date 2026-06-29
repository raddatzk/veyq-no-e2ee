package com.veyq.client

import com.veyq.api.content.model.NodeKind

/**
 * Client-side tree navigation (cr-0039, parity of web `browse.ts` / apple `MemoryBrowse.swift`). The
 * operator-blind server computes no navigation, so the current level + the breadcrumb are derived here from
 * the locally-decrypted tree ([VeyqClient.listTree]). Pure Kotlin/JVM — the same logic the web + Apple
 * clients run.
 */
object MemoryBrowse {
    /** A breadcrumb crumb: `nodeId == null` is the scope root. */
    data class Crumb(val nodeId: String?, val name: String)

    /** A browse level: the children of a parent, split into folders + Memories, each ordered. */
    data class Level(val folders: List<VeyqClient.TreeNode>, val memories: List<VeyqClient.TreeNode>)

    /** The children of [parentId] (null = the scope root), split into folders + Memories, each ordered. */
    fun childrenOf(parentId: String?, nodes: List<VeyqClient.TreeNode>): Level {
        val kids = nodes.filter { it.parentId == parentId }
            .sortedWith(compareBy({ it.orderIndex }, { it.name }))
        return Level(
            folders = kids.filter { it.kind == NodeKind.FOLDER },
            memories = kids.filter { it.kind == NodeKind.MEMORY },
        )
    }

    /** The breadcrumb from the root down to [nodeId] (null = root): `[root, …ancestors, node]`. */
    fun breadcrumb(nodeId: String?, nodes: List<VeyqClient.TreeNode>, rootName: String): List<Crumb> {
        val byId = nodes.associateBy { it.id }
        val trail = ArrayDeque<Crumb>()
        var cur = nodeId?.let { byId[it] }
        while (cur != null) {
            trail.addFirst(Crumb(cur.id, cur.name))
            cur = cur.parentId?.let { byId[it] }
        }
        trail.addFirst(Crumb(null, rootName))
        return trail.toList()
    }

    /** A node by id (null id → null), e.g. to title the folder currently open. */
    fun nodeById(nodeId: String?, nodes: List<VeyqClient.TreeNode>): VeyqClient.TreeNode? {
        if (nodeId == null) return null
        return nodes.firstOrNull { it.id == nodeId }
    }

    /** The drill path after tapping a breadcrumb crumb ([nodeId] == null → the root): truncate the path to
     *  that crumb (inclusive); leave it unchanged if the crumb isn't on the current path. */
    fun pathNavigatingTo(path: List<VeyqClient.TreeNode>, nodeId: String?): List<VeyqClient.TreeNode> {
        if (nodeId == null) return emptyList()
        val idx = path.indexOfFirst { it.id == nodeId }
        return if (idx < 0) path else path.take(idx + 1)
    }

    /** The drill path after deleting [nodeId]: if the open path runs through it, step back to its parent
     *  (drop it and everything below); otherwise unchanged. Mirrors the web client stepping out of a
     *  subtree it just removed. */
    fun pathAfterDeleting(path: List<VeyqClient.TreeNode>, nodeId: String): List<VeyqClient.TreeNode> {
        val idx = path.indexOfFirst { it.id == nodeId }
        return if (idx < 0) path else path.take(idx)
    }
}
