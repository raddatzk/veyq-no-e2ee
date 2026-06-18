package com.veyq.app.shared.content

import com.veyq.app.shared.keymgmt.ScopeGrantRepository
import com.veyq.app.shared.keymgmt.ScopeRepository
import jakarta.enterprise.context.ApplicationScoped

/**
 * Cascade deletion of encrypted content (AD-26). Removing a node or a whole scope must take everything that
 * hangs off it — media + derivatives, journal, tracks, shares — so no orphan ciphertext rows are left behind.
 * The server only ever drops opaque rows; it never reads plaintext. Shared by node-delete and group-delete.
 *
 * Note: blob rows (deduplicated content) + their stored frames are NOT deleted here — they are dedup'd
 * ciphertext that other media may still reference. Removing a node/scope only drops the references; the
 * unreferenced blobs are reclaimed by BlobUploadGc.purgeUnreferencedBlobs (ADR 0007), grace-gated by TTL.
 */
@ApplicationScoped
class ContentPurge(
    private val nodes: NodeRepository,
    private val media: MediaRepository,
    private val mediaBuckets: MediaBucketRepository,
    private val derivatives: MediaDerivativeRepository,
    private val journals: MemoryJournalRepository,
    private val journalUpdates: MemoryJournalUpdateRepository,
    private val tracks: MemoryTrackRepository,
    private val playlists: MemoryPlaylistRepository,
    private val shares: ShareRepository,
    private val contentKeys: ScopeContentKeyRepository,
    private val items: ScopeItemRepository,
    private val grants: ScopeGrantRepository,
    private val scopes: ScopeRepository,
) {
    /** Delete the given nodes and everything attached to them (media + derivatives, journal, tracks, shares). */
    fun purgeNodes(nodeIds: Collection<String>) {
        if (nodeIds.isEmpty()) return
        val mediaIds = media.listByNodeIds(nodeIds).map { it.id }
        derivatives.deleteByMediaIds(mediaIds)
        media.deleteByNodeIds(nodeIds)
        mediaBuckets.deleteByNodeIds(nodeIds)
        journals.deleteByNodeIds(nodeIds)
        journalUpdates.deleteByNodeIds(nodeIds)
        tracks.deleteByNodeIds(nodeIds)
        playlists.deleteByNodeIds(nodeIds)
        shares.deleteByNodeIds(nodeIds)
        nodes.clearCoverByMediaIds(mediaIds) // null any cover ref still pointing at deleted media
        nodes.deleteByIds(nodeIds)
    }

    /** Delete an entire scope: every node's content plus the scope's content key, items, grants, and the scope row. */
    fun purgeScope(scopeId: String) {
        val mediaIds = media.listByScopeId(scopeId).map { it.id }
        derivatives.deleteByMediaIds(mediaIds)
        media.deleteByScopeId(scopeId)
        mediaBuckets.deleteByScopeId(scopeId)
        journals.deleteByScopeId(scopeId)
        journalUpdates.deleteByScopeId(scopeId)
        tracks.deleteByScopeId(scopeId)
        playlists.deleteByScopeId(scopeId)
        shares.deleteByScopeId(scopeId)
        nodes.deleteByScopeId(scopeId)
        contentKeys.deleteByScopeId(scopeId)
        items.deleteByScopeId(scopeId)
        grants.deleteByScopeId(scopeId)
        scopes.deleteById(scopeId)
    }
}
