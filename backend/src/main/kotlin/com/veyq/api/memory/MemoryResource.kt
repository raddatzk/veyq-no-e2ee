package com.veyq.api.memory

import com.veyq.api.generated.api.MemoriesApi
import com.veyq.api.generated.model.CreateMemoryRequest
import com.veyq.api.generated.model.SetMemoryCoverRequest
import com.veyq.api.generated.model.UpdateMemoryRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.core.Response

@ApplicationScoped
class MemoryResource @Inject constructor(
    private val memoryService: MemoryService,
) : MemoriesApi {
    override fun listMemories(tenantName: String): Response =
        Response.ok(memoryService.listRootMemories()).build()

    override fun createMemory(tenantName: String, request: CreateMemoryRequest): Response =
        Response.ok(memoryService.createMemory(request)).build()

    override fun getMemory(tenantName: String, memoryId: String): Response =
        Response.ok(memoryService.getMemory(memoryId)).build()

    override fun updateMemory(tenantName: String, memoryId: String, request: UpdateMemoryRequest): Response =
        Response.ok(memoryService.updateMemory(memoryId, request)).build()

    override fun deleteMemory(tenantName: String, memoryId: String): Response {
        memoryService.deleteMemory(memoryId)
        return Response.noContent().build()
    }

    override fun listChildMemories(tenantName: String, memoryId: String): Response =
        Response.ok(memoryService.listChildren(memoryId)).build()

    override fun setMemoryCover(tenantName: String, memoryId: String, request: SetMemoryCoverRequest): Response =
        Response.ok(memoryService.setCover(memoryId, request)).build()
}
