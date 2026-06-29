package com.veyq.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.veyq.api.scopes.model.ScopeKind
import com.veyq.client.MemoryBrowse
import com.veyq.client.VeyqClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Memory shell's state (cr-0039) — the Android twin of Apple's `HomeModel`. Opens the personal scope,
 * loads the decrypted tree once, and derives the current level + breadcrumb **client-side** (the
 * operator-blind server computes no navigation). Drill is a path stack, like web + Apple; the path-stack
 * navigation lives as pure helpers in [MemoryBrowse] (unit-tested in `:client`). A plain Compose state
 * holder (`mutableStateOf`-backed), created via `remember` in `HomeScreen`; the blocking `:client` calls run
 * on [Dispatchers.IO].
 */
class HomeModel(private val client: VeyqClient?) {
    var nodes by mutableStateOf<List<VeyqClient.TreeNode>>(emptyList())
        private set

    /** The drill path from the scope root to the folder currently open (empty = root). */
    var path by mutableStateOf<List<VeyqClient.TreeNode>>(emptyList())
        private set

    var error by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(true)
        private set

    private var scopeId: String? = null
    private var scopeKey: ByteArray? = null
    val rootName = "Personal"

    val currentParentId: String? get() = path.lastOrNull()?.id
    val level: MemoryBrowse.Level get() = MemoryBrowse.childrenOf(currentParentId, nodes)
    val crumbs: List<MemoryBrowse.Crumb> get() = MemoryBrowse.breadcrumb(currentParentId, nodes, rootName)

    /** Open the personal scope (auto-birth if absent) + load the tree. Idempotent across re-entry. */
    suspend fun start() {
        if (scopeId != null) return // already opened
        val client = client ?: run { error = "Not connected."; loading = false; return }
        loading = true
        error = null
        try {
            withContext(Dispatchers.IO) { client.ensurePersonalScope() }
            val personal = withContext(Dispatchers.IO) { client.listMyScopes() }
                .firstOrNull { it.kind == ScopeKind.PERSONAL }
            val key = withContext(Dispatchers.IO) { client.openPersonalScope() }
            if (personal == null || key == null) {
                error = "Couldn’t open your space."; loading = false; return
            }
            scopeId = personal.id.toString()
            scopeKey = key
            reload()
        } catch (e: Exception) {
            error = "Couldn’t open your space."
        }
        loading = false
    }

    private suspend fun reload() {
        val id = scopeId ?: return
        val key = scopeKey ?: return
        nodes = withContext(Dispatchers.IO) { client!!.listTree(id, key) }
    }

    fun openFolder(node: VeyqClient.TreeNode) { path = path + node }

    fun goUp() { if (path.isNotEmpty()) path = path.dropLast(1) }

    fun goto(crumb: MemoryBrowse.Crumb) { path = MemoryBrowse.pathNavigatingTo(path, crumb.nodeId) }

    suspend fun createFolder(name: String) = mutate { c, id, key ->
        c.createFolder(id, key, name, currentParentId)
    }

    suspend fun createMemory(name: String) = mutate { c, id, key ->
        c.createMemory(id, key, name, currentParentId)
    }

    suspend fun rename(node: VeyqClient.TreeNode, name: String) = mutate { c, id, key ->
        c.renameNode(id, key, node, name)
    }

    suspend fun delete(node: VeyqClient.TreeNode) {
        // If we're inside the node (or its subtree) being deleted, step back to its parent first.
        path = MemoryBrowse.pathAfterDeleting(path, node.id)
        mutate { c, id, _ -> c.deleteNode(id, node.id) }
    }

    /** Run a mutation against the open scope, then reload the tree; surface a generic error on failure. */
    private suspend fun mutate(op: suspend (VeyqClient, String, ByteArray) -> Unit) {
        val c = client ?: return
        val id = scopeId ?: return
        val key = scopeKey ?: return
        try {
            withContext(Dispatchers.IO) { op(c, id, key) }
            reload()
        } catch (e: Exception) {
            error = "That didn’t work — please try again."
        }
    }
}
