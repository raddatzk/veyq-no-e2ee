package com.veyq.app.features.memory.track

import com.veyq.app.generated.api.MemoryTrackApi
import com.veyq.app.generated.model.CreateTrackRequest
import com.veyq.app.generated.model.UpdateTrackRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response

/** Thin REST entry for the generated MemoryTrackApi (GET/POST/PATCH/DELETE /nodes/{nodeId}/tracks); delegates. */
@ApplicationScoped
class TrackResource(private val handler: TrackHandler) : MemoryTrackApi {
    override fun listTracks(authorization: String, scopeId: String, nodeId: String): Response =
        Response.ok(handler.listTracks(authorization, scopeId, nodeId)).build()

    override fun createTrack(authorization: String, scopeId: String, nodeId: String, createTrackRequest: CreateTrackRequest): Response =
        Response.ok(handler.createTrack(authorization, scopeId, nodeId, createTrackRequest)).build()

    override fun updateTrack(authorization: String, scopeId: String, nodeId: String, trackId: String, updateTrackRequest: UpdateTrackRequest): Response =
        Response.ok(handler.updateTrack(authorization, scopeId, nodeId, trackId, updateTrackRequest)).build()

    override fun deleteTrack(authorization: String, scopeId: String, nodeId: String, trackId: String): Response {
        handler.deleteTrack(authorization, scopeId, nodeId, trackId)
        return Response.noContent().build()
    }
}
