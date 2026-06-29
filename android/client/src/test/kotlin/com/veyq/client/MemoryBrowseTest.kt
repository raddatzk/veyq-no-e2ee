package com.veyq.client

import com.veyq.api.content.model.NodeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * cr-0039 — client-side level + breadcrumb derivation, the Android twin of Apple's `MemoryBrowseTests`
 * (web `browse.test.ts`). The operator-blind server computes no navigation, so this logic assembles the
 * level + breadcrumb from the decrypted rows.
 */
class MemoryBrowseTest {
    private fun node(id: String, kind: NodeKind, name: String, parent: String? = null, order: Int = 0) =
        VeyqClient.TreeNode(
            id = id, parentId = parent, kind = kind, orderIndex = order, name = name, wrappedContentKey = null,
        )

    // root: folder "Trips" + memory "Solo"; "Trips" holds memory "Italy" + folder "2025".
    private val tree = listOf(
        node("trips", NodeKind.FOLDER, "Trips", order = 1),
        node("solo", NodeKind.MEMORY, "Solo", order = 0),
        node("italy", NodeKind.MEMORY, "Italy", parent = "trips"),
        node("2025", NodeKind.FOLDER, "2025", parent = "trips"),
    )

    @Test
    fun childrenSplitsFoldersAndMemoriesOrdered() {
        val root = MemoryBrowse.childrenOf(null, tree)
        assertEquals(listOf("Trips"), root.folders.map { it.name })
        assertEquals(listOf("Solo"), root.memories.map { it.name })

        val inTrips = MemoryBrowse.childrenOf("trips", tree)
        assertEquals(listOf("2025"), inTrips.folders.map { it.name })
        assertEquals(listOf("Italy"), inTrips.memories.map { it.name })
    }

    @Test
    fun childrenEmptyForALeaf() {
        val level = MemoryBrowse.childrenOf("solo", tree)
        assertTrue(level.folders.isEmpty() && level.memories.isEmpty())
    }

    @Test
    fun breadcrumbWalksRootToNode() {
        val trail = MemoryBrowse.breadcrumb("2025", tree, "Personal")
        assertEquals(listOf("Personal", "Trips", "2025"), trail.map { it.name })
        assertEquals(listOf(null, "trips", "2025"), trail.map { it.nodeId })
    }

    @Test
    fun breadcrumbIsJustTheRootAtTheTopLevel() {
        val trail = MemoryBrowse.breadcrumb(null, tree, "Personal")
        assertEquals(listOf("Personal"), trail.map { it.name })
    }

    @Test
    fun nodeLookup() {
        assertEquals("Italy", MemoryBrowse.nodeById("italy", tree)?.name)
        assertNull(MemoryBrowse.nodeById(null, tree))
        assertNull(MemoryBrowse.nodeById("nope", tree))
    }

    // A drill path Trips → 2025 (the shell's `HomeModel` path stack).
    private val deepPath = listOf(
        node("trips", NodeKind.FOLDER, "Trips", order = 1),
        node("2025", NodeKind.FOLDER, "2025", parent = "trips"),
    )

    @Test
    fun navigatingToRootClearsThePath() {
        assertEquals(emptyList<String>(), MemoryBrowse.pathNavigatingTo(deepPath, null).map { it.id })
    }

    @Test
    fun navigatingToACrumbTruncatesToIt() {
        assertEquals(listOf("trips"), MemoryBrowse.pathNavigatingTo(deepPath, "trips").map { it.id })
        // tapping the deepest crumb keeps the whole path
        assertEquals(listOf("trips", "2025"), MemoryBrowse.pathNavigatingTo(deepPath, "2025").map { it.id })
    }

    @Test
    fun navigatingToANodeNotOnThePathLeavesItUnchanged() {
        assertEquals(listOf("trips", "2025"), MemoryBrowse.pathNavigatingTo(deepPath, "elsewhere").map { it.id })
    }

    @Test
    fun deletingAnAncestorStepsBackToItsParent() {
        assertEquals(emptyList<String>(), MemoryBrowse.pathAfterDeleting(deepPath, "trips").map { it.id })
        assertEquals(listOf("trips"), MemoryBrowse.pathAfterDeleting(deepPath, "2025").map { it.id })
    }

    @Test
    fun deletingASiblingOutsideThePathLeavesItUnchanged() {
        assertEquals(listOf("trips", "2025"), MemoryBrowse.pathAfterDeleting(deepPath, "solo").map { it.id })
    }
}
