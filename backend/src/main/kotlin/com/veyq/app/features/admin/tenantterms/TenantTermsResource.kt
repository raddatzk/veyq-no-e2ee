package com.veyq.app.features.admin.tenantterms

import com.veyq.app.generated.api.TenantTermsApi
import com.veyq.app.generated.model.UpdateTenantTermsRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response

/** Thin REST entry for the generated TenantTermsApi (GET/PUT /tenants/{id}/terms); delegates to the handler. */
@ApplicationScoped
class TenantTermsResource(private val handler: TenantTermsHandler) : TenantTermsApi {
    override fun getTenantTerms(authorization: String, tenantId: String): Response =
        Response.ok(handler.getTenantTerms(authorization, tenantId)).build()

    override fun setTenantTerms(authorization: String, tenantId: String, updateTenantTermsRequest: UpdateTenantTermsRequest): Response =
        Response.ok(handler.setTenantTerms(authorization, tenantId, updateTenantTermsRequest)).build()
}
