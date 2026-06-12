package com.veyq.api.memory

import com.veyq.api.generated.api.MemoryParticipantsApi
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.core.Response

@ApplicationScoped
class MemoryParticipantResource @Inject constructor(
    private val memoryService: MemoryService,
) : MemoryParticipantsApi {
    override fun listMemoryParticipants(tenantName: String, memoryId: String): Response =
        Response.ok(memoryService.listParticipants(memoryId)).build()

    override fun listMemoryParticipantCandidates(tenantName: String, memoryId: String): Response =
        Response.ok(memoryService.listParticipantCandidates(memoryId)).build()

    override fun addMemoryParticipant(tenantName: String, memoryId: String, userId: Long): Response =
        Response.ok(memoryService.addParticipant(memoryId, userId)).build()

    override fun removeMemoryParticipant(tenantName: String, memoryId: String, userId: Long): Response {
        memoryService.removeParticipant(memoryId, userId)
        return Response.noContent().build()
    }
}
