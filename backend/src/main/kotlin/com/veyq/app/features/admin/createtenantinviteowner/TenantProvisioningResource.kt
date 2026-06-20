package com.veyq.app.features.admin.createtenantinviteowner

import com.veyq.app.generated.api.TenantProvisioningApi
import com.veyq.app.generated.model.ClaimTenantOwnershipRequest
import com.veyq.app.generated.model.CreateTenantRequest
import com.veyq.app.generated.model.GetInviteMaterialRequest
import com.veyq.app.generated.model.UploadTenantLogoRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response

/** Thin REST entry implementing the generated TenantProvisioningApi; delegates to the handler. */
@ApplicationScoped
class TenantProvisioningResource(private val handler: TenantProvisioningHandler) : TenantProvisioningApi {

    override fun createTenant(authorization: String, createTenantRequest: CreateTenantRequest): Response =
        Response.ok(handler.createTenant(authorization, createTenantRequest)).build()

    override fun deleteTenant(authorization: String, tenantId: String): Response {
        handler.deleteTenant(authorization, tenantId)
        return Response.noContent().build()
    }

    override fun cancelTenantDeletion(authorization: String, tenantId: String): Response {
        handler.cancelTenantDeletion(authorization, tenantId)
        return Response.noContent().build()
    }

    override fun uploadTenantLogo(authorization: String, tenantId: String, uploadTenantLogoRequest: UploadTenantLogoRequest): Response =
        Response.ok(handler.uploadTenantLogo(authorization, tenantId, uploadTenantLogoRequest)).build()

    override fun clearTenantLogo(authorization: String, tenantId: String): Response =
        Response.ok(handler.clearTenantLogo(authorization, tenantId)).build()

    override fun getTenantLogo(tenantId: String): Response {
        val logo = handler.getTenantLogo(tenantId)
        return Response.ok(logo.bytes, logo.contentType).build()
    }

    override fun issueOwnerInvite(authorization: String, tenantId: String): Response =
        Response.ok(handler.issueOwnerInvite(authorization, tenantId)).build()

    override fun getTenantInvite(inviteId: String): Response =
        Response.ok(handler.getTenantInvite(inviteId)).build()

    override fun getInviteMaterial(inviteId: String, getInviteMaterialRequest: GetInviteMaterialRequest): Response =
        Response.ok(handler.getInviteMaterial(inviteId, getInviteMaterialRequest)).build()

    override fun claimTenantOwnership(authorization: String, inviteId: String, claimTenantOwnershipRequest: ClaimTenantOwnershipRequest): Response =
        Response.ok(handler.claimTenantOwnership(authorization, inviteId, claimTenantOwnershipRequest)).build()

    override fun getMyScopeGrant(authorization: String, tenantId: String): Response =
        Response.ok(handler.getMyScopeGrant(authorization, tenantId)).build()
}
