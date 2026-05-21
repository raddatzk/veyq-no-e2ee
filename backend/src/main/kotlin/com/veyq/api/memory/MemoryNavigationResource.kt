package com.veyq.api.memory

import com.veyq.api.generated.api.MemoryNavigationApi
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.core.Response

@ApplicationScoped
class MemoryNavigationResource @Inject constructor(
    private val memoryNavigationService: MemoryNavigationService,
) : MemoryNavigationApi {
    override fun getMemoryNavigation(tenantName: String, path: String): Response =
        Response.ok(memoryNavigationService.navigation(path)).build()
}
