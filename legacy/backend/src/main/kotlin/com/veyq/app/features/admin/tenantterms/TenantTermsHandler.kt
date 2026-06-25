package com.veyq.app.features.admin.tenantterms

import com.veyq.app.generated.model.TenantTerms
import com.veyq.app.generated.model.UpdateTenantTermsRequest
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.security.TenantAdminGuard
import com.veyq.app.shared.tenancy.TenantRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

/**
 * uc.share.terms — a Tenant Owner sets the Terms of Service that a guest must accept on any share of this
 * tenant's memories. Setting them bumps termsVersion so guests re-accept. Read/write are Owner-gated; the
 * terms themselves are served to guests (no auth) inside the public-share response.
 */
@ApplicationScoped
class TenantTermsHandler(
    private val tenants: TenantRepository,
    private val guard: TenantAdminGuard,
) {
    @Transactional
    fun getTenantTerms(auth: String?, tenantId: String): TenantTerms {
        val tenant = requireOwner(auth, tenantId)
        return TenantTerms(tenantId = tenant.id, termsMarkdown = tenant.termsMarkdown, version = tenant.termsVersion)
    }

    @Transactional
    fun setTenantTerms(auth: String?, tenantId: String, req: UpdateTenantTermsRequest): TenantTerms {
        val tenant = requireOwner(auth, tenantId)
        val md = req.termsMarkdown?.trim()?.take(20_000)?.ifEmpty { null }
        if (md != tenant.termsMarkdown) {
            tenant.termsMarkdown = md
            tenant.termsVersion += 1 // guests re-accept on any change
        }
        return TenantTerms(tenantId = tenant.id, termsMarkdown = tenant.termsMarkdown, version = tenant.termsVersion)
    }

    private fun requireOwner(auth: String?, tenantId: String): com.veyq.app.shared.tenancy.Tenant {
        guard.requireOwner(auth, tenantId)
        return tenants.findById(tenantId) ?: throw ApiException(404, "NOT_FOUND", "Tenant not found")
    }
}
