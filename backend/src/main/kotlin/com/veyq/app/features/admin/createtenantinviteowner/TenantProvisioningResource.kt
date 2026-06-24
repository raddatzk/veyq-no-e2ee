package com.veyq.app.features.admin.createtenantinviteowner

import com.veyq.app.generated.api.CurrentTenantApi
import com.veyq.app.generated.api.TenantBrandingApi
import com.veyq.app.generated.api.TenantLifecycleApi
import com.veyq.app.generated.model.CreateTenantRequest
import com.veyq.app.generated.model.UploadTenantLogoRequest
import com.veyq.app.shared.tenancy.CurrentTenantResolver
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response

/** Thin REST entry implementing tenant lifecycle and branding APIs; delegates to the handler. */
@ApplicationScoped
class TenantProvisioningResource(
    private val handler: TenantProvisioningHandler,
    private val currentTenant: CurrentTenantResolver,
) : TenantLifecycleApi, TenantBrandingApi, CurrentTenantApi {

    override fun createTenant(authorization: String, createTenantRequest: CreateTenantRequest): Response =
        Response.ok(handler.createTenant(authorization, createTenantRequest)).build()

    override fun getCurrentTenant(authorization: String): Response =
        Response.ok(handler.currentTenant(authorization, currentTenant.currentTenant())).build()

    override fun deleteTenant(authorization: String): Response {
        val tenantId = currentTenant.currentTenantId()
        handler.deleteTenant(authorization, tenantId)
        return Response.noContent().build()
    }

    override fun cancelTenantDeletion(authorization: String): Response {
        val tenantId = currentTenant.currentTenantId()
        handler.cancelTenantDeletion(authorization, tenantId)
        return Response.noContent().build()
    }

    override fun uploadTenantLogo(authorization: String, uploadTenantLogoRequest: UploadTenantLogoRequest): Response =
        Response.ok(handler.uploadTenantLogo(authorization, currentTenant.currentTenantId(), uploadTenantLogoRequest)).build()

    override fun clearTenantLogo(authorization: String): Response =
        Response.ok(handler.clearTenantLogo(authorization, currentTenant.currentTenantId())).build()

    override fun getTenantLogo(): Response {
        val logo = handler.getTenantLogo(currentTenant.currentTenantId())
        return Response.ok(logo.bytes, logo.contentType).build()
    }

}
