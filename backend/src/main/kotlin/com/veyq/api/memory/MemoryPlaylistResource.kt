package com.veyq.api.memory

import com.veyq.api.generated.api.MemoryPlaylistsApi
import com.veyq.api.generated.model.CreateMemoryPlaylistRequest
import com.veyq.api.generated.model.UpdateMemoryPlaylistRequest
import com.veyq.api.library.playlist.MemoryPlaylistService
import jakarta.inject.Inject
import jakarta.ws.rs.core.Response

class MemoryPlaylistResource @Inject constructor(
    private val playlistService: MemoryPlaylistService,
) : MemoryPlaylistsApi {
    override fun listMemoryPlaylists(tenantName: String, memoryId: String): Response =
        Response.ok(playlistService.listMemoryPlaylists(memoryId)).build()

    override fun createMemoryPlaylist(
        tenantName: String,
        memoryId: String,
        createMemoryPlaylistRequest: CreateMemoryPlaylistRequest,
    ): Response =
        Response.ok(playlistService.createMemoryPlaylist(memoryId, createMemoryPlaylistRequest)).build()

    override fun updateMemoryPlaylist(
        tenantName: String,
        memoryId: String,
        playlistId: String,
        updateMemoryPlaylistRequest: UpdateMemoryPlaylistRequest,
    ): Response =
        Response.ok(playlistService.updateMemoryPlaylist(memoryId, playlistId, updateMemoryPlaylistRequest)).build()

    override fun deleteMemoryPlaylist(tenantName: String, memoryId: String, playlistId: String): Response {
        playlistService.deleteMemoryPlaylist(memoryId, playlistId)
        return Response.noContent().build()
    }
}
