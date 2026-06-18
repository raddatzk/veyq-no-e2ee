package com.veyq.app.features.memory.playlist

import com.veyq.app.generated.model.Blob
import com.veyq.app.generated.model.CreatePlaylistRequest
import com.veyq.app.generated.model.MemoryPlaylist as ApiPlaylist
import com.veyq.app.generated.model.MemoryPlaylistList
import com.veyq.app.generated.model.NodeType
import com.veyq.app.generated.model.UpdatePlaylistRequest
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.content.MemoryPlaylist
import com.veyq.app.shared.content.MemoryPlaylistRepository
import com.veyq.app.shared.content.NodeRepository
import com.veyq.app.shared.content.SealedContent
import com.veyq.app.shared.crypto.CryptoSuiteRegistry
import com.veyq.app.shared.security.ScopeAccessGuard
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

/**
 * uc.memory.playlist (ADR 0010, operator-blind) — link streaming playlists to a Memory/Chapter. The payload
 * {provider, url, title?, note?} is sealed client-side; the server stores ciphertext + a benign `featured` flag
 * (at most one featured per node). Mirrors the track handler's sealed-blob pattern.
 */
@ApplicationScoped
class PlaylistHandler(
    private val nodes: NodeRepository,
    private val playlists: MemoryPlaylistRepository,
    private val guard: ScopeAccessGuard,
    private val suites: CryptoSuiteRegistry,
    private val content: com.veyq.app.shared.config.ContentConfig,
) {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()

    @Transactional
    fun listPlaylists(auth: String?, scopeId: String, nodeId: String): MemoryPlaylistList {
        guard.requireKeyHolder(auth, scopeId)
        requirePlaylistNode(scopeId, nodeId)
        return MemoryPlaylistList(playlists = playlists.listByNodeId(nodeId).map(::toApi))
    }

    @Transactional
    fun createPlaylist(auth: String?, scopeId: String, nodeId: String, req: CreatePlaylistRequest): ApiPlaylist {
        guard.requireKeyHolder(auth, scopeId)
        requirePlaylistNode(scopeId, nodeId)
        if (!suites.isKnown(req.sealedContent.suiteId)) throw ApiException(400, "UNKNOWN_SUITE", "Unknown crypto suite")
        val p = MemoryPlaylist().apply {
            id = UUID.randomUUID().toString(); this.nodeId = nodeId; this.scopeId = scopeId
            suiteId = req.sealedContent.suiteId
            sealedContent = SealedContent.decodeCapped(req.sealedContent.bytes, content.maxSealedBytes(), dec)
            featured = req.featured ?: false; createdAt = Instant.now()
        }
        playlists.persist(p)
        if (p.featured) playlists.clearFeatured(nodeId, p.id)
        return toApi(p)
    }

    @Transactional
    fun updatePlaylist(auth: String?, scopeId: String, nodeId: String, playlistId: String, req: UpdatePlaylistRequest): ApiPlaylist {
        guard.requireKeyHolder(auth, scopeId)
        val p = require(scopeId, nodeId, playlistId)
        req.sealedContent?.let {
            if (!suites.isKnown(it.suiteId)) throw ApiException(400, "UNKNOWN_SUITE", "Unknown crypto suite")
            p.suiteId = it.suiteId; p.sealedContent = SealedContent.decodeCapped(it.bytes, content.maxSealedBytes(), dec)
        }
        req.featured?.let { p.featured = it; if (it) playlists.clearFeatured(nodeId, p.id) }
        return toApi(p)
    }

    @Transactional
    fun deletePlaylist(auth: String?, scopeId: String, nodeId: String, playlistId: String) {
        guard.requireKeyHolder(auth, scopeId)
        playlists.delete(require(scopeId, nodeId, playlistId))
    }

    private fun require(scopeId: String, nodeId: String, playlistId: String): MemoryPlaylist {
        val p = playlists.findById(playlistId) ?: throw ApiException(404, "PLAYLIST_NOT_FOUND", "Playlist not found")
        if (p.scopeId != scopeId || p.nodeId != nodeId) throw ApiException(404, "PLAYLIST_NOT_FOUND", "Playlist not found here")
        return p
    }

    private fun requirePlaylistNode(scopeId: String, nodeId: String) {
        val node = nodes.findById(nodeId) ?: throw ApiException(404, "NODE_NOT_FOUND", "Node not found")
        if (node.scopeId != scopeId) throw ApiException(404, "NODE_NOT_FOUND", "Node not found in this scope")
        if (node.type == NodeType.FOLDER) throw ApiException(400, "NOT_A_MEMORY", "Only a Memory/Chapter has playlists")
    }

    private fun toApi(p: MemoryPlaylist): ApiPlaylist =
        ApiPlaylist(
            id = p.id, nodeId = p.nodeId,
            sealedContent = Blob(suiteId = p.suiteId, bytes = enc.encodeToString(p.sealedContent)),
            featured = p.featured,
            createdAt = OffsetDateTime.ofInstant(p.createdAt, ZoneOffset.UTC),
        )
}
