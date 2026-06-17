package com.veyq.app.features.memory.track

import com.veyq.app.generated.model.Blob
import com.veyq.app.generated.model.CreateTrackRequest
import com.veyq.app.generated.model.MemoryTrack as ApiMemoryTrack
import com.veyq.app.generated.model.MemoryTrackList
import com.veyq.app.generated.model.NodeType
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.content.MemoryTrack
import com.veyq.app.shared.content.MemoryTrackRepository
import com.veyq.app.shared.content.NodeRepository
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
 * uc.memory.track — list / add / remove a Memory's GPX tracks. Each track is a {name, points} route sealed
 * client-side under the scope content key; the server stores ciphertext + a timestamp only and never sees
 * the route. Tracks live only on a Memory or Chapter (not a Folder).
 */
@ApplicationScoped
class TrackHandler(
    private val nodes: NodeRepository,
    private val tracks: MemoryTrackRepository,
    private val guard: ScopeAccessGuard,
    private val suites: CryptoSuiteRegistry,
) {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()

    @Transactional
    fun listTracks(auth: String?, scopeId: String, nodeId: String): MemoryTrackList {
        requireKeyHolder(scopeId, auth)
        requireTrackNode(scopeId, nodeId)
        return MemoryTrackList(tracks = tracks.listByNodeId(nodeId).map(::toApi))
    }

    @Transactional
    fun createTrack(auth: String?, scopeId: String, nodeId: String, req: CreateTrackRequest): ApiMemoryTrack {
        requireKeyHolder(scopeId, auth)
        requireTrackNode(scopeId, nodeId)
        if (!suites.isKnown(req.sealedContent.suiteId)) throw ApiException(400, "UNKNOWN_SUITE", "Unknown crypto suite")
        val track = MemoryTrack().apply {
            id = UUID.randomUUID().toString(); this.nodeId = nodeId; this.scopeId = scopeId
            suiteId = req.sealedContent.suiteId; sealedContent = dec.decode(req.sealedContent.bytes); createdAt = Instant.now()
        }
        tracks.persist(track)
        return toApi(track)
    }

    @Transactional
    fun deleteTrack(auth: String?, scopeId: String, nodeId: String, trackId: String) {
        requireKeyHolder(scopeId, auth)
        val track = tracks.findById(trackId) ?: throw ApiException(404, "TRACK_NOT_FOUND", "Track not found")
        if (track.scopeId != scopeId || track.nodeId != nodeId) throw ApiException(404, "TRACK_NOT_FOUND", "Track not found here")
        tracks.delete(track)
    }

    private fun requireTrackNode(scopeId: String, nodeId: String) {
        val node = nodes.findById(nodeId) ?: throw ApiException(404, "NODE_NOT_FOUND", "Node not found")
        if (node.scopeId != scopeId) throw ApiException(404, "NODE_NOT_FOUND", "Node not found in this scope")
        if (node.type == NodeType.FOLDER) throw ApiException(400, "NOT_A_MEMORY", "Only a Memory/Chapter has tracks")
    }

    private fun toApi(t: MemoryTrack): ApiMemoryTrack =
        ApiMemoryTrack(
            id = t.id, nodeId = t.nodeId,
            sealedContent = Blob(suiteId = t.suiteId, bytes = enc.encodeToString(t.sealedContent)),
            createdAt = OffsetDateTime.ofInstant(t.createdAt, ZoneOffset.UTC),
        )

    private fun requireKeyHolder(scopeId: String, auth: String?) {
        guard.requireKeyHolder(auth, scopeId)
    }
}
