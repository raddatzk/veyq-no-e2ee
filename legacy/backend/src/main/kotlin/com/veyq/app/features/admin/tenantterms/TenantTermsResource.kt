package com.veyq.app.features.admin.tenantterms

import com.veyq.app.generated.api.TenantTermsApi
import com.veyq.app.generated.model.UpdateTenantTermsRequest
import com.veyq.app.shared.tenancy.CurrentTenantResolver
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response

/** Thin REST entry for the generated TenantTermsApi; delegates to the handler. */
@ApplicationScoped
class TenantTermsResource(
    private val handler: TenantTermsHandler,
    private val currentTenant: CurrentTenantResolver,
) : TenantTermsApi {
    override fun getTenantTerms(authorization: String): Response =
        Response.ok(handler.getTenantTerms(authorization, currentTenant.currentTenantId())).build()

    override fun setTenantTerms(authorization: String, updateTenantTermsRequest: UpdateTenantTermsRequest): Response =
        Response.ok(handler.setTenantTerms(authorization, currentTenant.currentTenantId(), updateTenantTermsRequest)).build()
}
