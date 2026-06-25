package com.veyq.app.features.memory.playlist

import com.veyq.app.generated.api.MemoryPlaylistApi
import com.veyq.app.generated.model.CreatePlaylistRequest
import com.veyq.app.generated.model.UpdatePlaylistRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response

/** Thin REST entry for the generated MemoryPlaylistApi (GET/POST/PATCH/DELETE /nodes/{nodeId}/playlists). */
@ApplicationScoped
class PlaylistResource(private val handler: PlaylistHandler) : MemoryPlaylistApi {
    override fun listPlaylists(authorization: String, scopeId: String, nodeId: String): Response =
        Response.ok(handler.listPlaylists(authorization, scopeId, nodeId)).build()

    override fun createPlaylist(authorization: String, scopeId: String, nodeId: String, createPlaylistRequest: CreatePlaylistRequest): Response =
        Response.ok(handler.createPlaylist(authorization, scopeId, nodeId, createPlaylistRequest)).build()

    override fun updatePlaylist(authorization: String, scopeId: String, nodeId: String, playlistId: String, updatePlaylistRequest: UpdatePlaylistRequest): Response =
        Response.ok(handler.updatePlaylist(authorization, scopeId, nodeId, playlistId, updatePlaylistRequest)).build()

    override fun deletePlaylist(authorization: String, scopeId: String, nodeId: String, playlistId: String): Response {
        handler.deletePlaylist(authorization, scopeId, nodeId, playlistId)
        return Response.noContent().build()
    }
}
