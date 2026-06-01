package com.veyq.api.memory

import com.veyq.api.generated.api.MemorySharesApi
import com.veyq.api.generated.model.CreateMemoryShareRequest
import com.veyq.api.generated.model.UpdateMemoryShareRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.core.Response

@ApplicationScoped
class MemoryShareResource @Inject constructor(
    private val memoryShareService: MemoryShareService,
) : MemorySharesApi {
    override fun listMemoryShares(tenantName: String, memoryId: String): Response =
        Response.ok(memoryShareService.listMemoryShares(memoryId)).build()

    override fun createMemoryShare(
        tenantName: String,
        memoryId: String,
        createMemoryShareRequest: CreateMemoryShareRequest,
    ): Response =
        Response.ok(memoryShareService.createMemoryShare(memoryId, createMemoryShareRequest)).build()

    override fun updateMemoryShare(
        tenantName: String,
        shareId: String,
        updateMemoryShareRequest: UpdateMemoryShareRequest,
    ): Response =
        Response.ok(memoryShareService.updateMemoryShare(shareId, updateMemoryShareRequest)).build()
}
