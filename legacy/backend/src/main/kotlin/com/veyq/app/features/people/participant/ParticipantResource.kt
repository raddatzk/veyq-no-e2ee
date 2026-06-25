package com.veyq.app.features.people.participant

import com.veyq.app.generated.api.MemoryParticipantApi
import com.veyq.app.generated.model.CreateParticipantRequest
import com.veyq.app.generated.model.UpdateParticipantRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response

/** Thin REST entry for sealed Memory participants (GET/POST/PATCH/DELETE /nodes/{nodeId}/participants). */
@ApplicationScoped
class ParticipantResource(private val handler: ParticipantHandler) : MemoryParticipantApi {
    override fun listParticipants(authorization: String, scopeId: String, nodeId: String): Response =
        Response.ok(handler.listParticipants(authorization, scopeId, nodeId)).build()

    override fun createParticipant(authorization: String, scopeId: String, nodeId: String, createParticipantRequest: CreateParticipantRequest): Response =
        Response.ok(handler.createParticipant(authorization, scopeId, nodeId, createParticipantRequest)).build()

    override fun updateParticipant(authorization: String, scopeId: String, nodeId: String, participantId: String, updateParticipantRequest: UpdateParticipantRequest): Response =
        Response.ok(handler.updateParticipant(authorization, scopeId, nodeId, participantId, updateParticipantRequest)).build()

    override fun deleteParticipant(authorization: String, scopeId: String, nodeId: String, participantId: String): Response {
        handler.deleteParticipant(authorization, scopeId, nodeId, participantId)
        return Response.noContent().build()
    }
}
