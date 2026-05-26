package com.veyq.api.memory

import com.veyq.api.generated.api.SearchApi
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.core.Response

@ApplicationScoped
class SearchResource @Inject constructor(
    private val searchService: SearchService,
) : SearchApi {
    override fun searchTenant(tenantName: String, q: String, limit: Int?): Response =
        Response.ok(searchService.search(q, limit)).build()
}
