package com.lunva.api.tenant

import com.lunva.api.auth.BadRequestException
import com.lunva.api.auth.ConflictException
import com.lunva.api.auth.ForbiddenException
import com.lunva.api.auth.NotFoundException
import com.lunva.api.auth.filterchain.RequestContext
import com.lunva.api.domain.*
import com.lunva.api.generated.tenants.model.CreateTenantRequest
import com.lunva.api.generated.tenants.model.TenantRole as ApiTenantRole
import com.lunva.api.generated.tenants.model.TenantSettingsResponse
import com.lunva.api.generated.tenants.model.TenantSummary
import com.lunva.api.generated.tenants.model.TenantSummaryAdminsInner
import com.lunva.api.generated.tenants.model.TenantsSummary
import com.lunva.api.generated.tenants.model.UpdateNamedSettingsRequest
import com.lunva.api.generated.users.model.ManagedUserTenantSummary
import com.lunva.api.usermgmt.toUsersApiRole
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional

@ApplicationScoped
class TenantService @Inject constructor(
    private val tenantRepo: TenantRepo,
    private val tenantMemberRepo: TenantMemberRepo,
    private val requestContext: RequestContext,
) {

    @Transactional
    fun createTenantForCurrentUser(request: CreateTenantRequest): TenantSummary {
        val user = requestContext.user!!
        val tenant = createTenant(request.name!!, request.displayName!!, request.domain!!, user)

        return TenantSummary(tenant.id, tenant.name, tenant.displayName, tenant.domain, ApiTenantRole.TenantOwner, listOf(user.toTenantAdminSummary()))
    }

    fun listMyTenants(): TenantsSummary {
        return TenantsSummary(
            requestContext.user!!.tenantMemberships
                .sortedBy { it.tenant.name }
                .map {
                    val tenantOwners = findUsersWithRole(it.tenant, TenantRole.TenantOwner).map { user -> user.toTenantAdminSummary() }
                    TenantSummary(it.tenant.id, it.tenant.name, it.tenant.displayName, it.tenant.domain, it.role.toTenantsApiRole(), tenantOwners)
                }
        )
    }

    private fun findUsersWithRole(tenant: Tenant, role: TenantRole): List<User> {
        return tenant.members.filter { it.role == role }.map { it.user }
    }

    fun requireTenantMembership(tenant: Tenant, user: User) {
        tenantMemberRepo.findByTenantAndUser(tenant, user)
            ?: throw ForbiddenException("User is not member of this tenant")
    }

    fun addUserToTenant(tenant: Tenant, user: User) {
        TenantMember().also {
            it.id = TenantMemberId(tenant.id, user.id)
            it.tenant = tenant
            it.user = user
            it.role = TenantRole.TenantMember
            it.persist()
        }
    }

    private fun createTenant(name: String, displayName: String, domainInput: String, owner: User): Tenant {
        validateTenantName(name)
        val domain = normalizeDomain(domainInput)
        if (tenantRepo.existsByDomain(domain)) {
            throw ConflictException("Tenant domain already exists")
        }

        val tenant = Tenant().also {
            it.name = name
            it.displayName = displayName.trim()
            it.domain = domain
            tenantRepo.persist(it)
        }
        tenantRepo.flush()

        TenantMember().also {
            it.id = TenantMemberId(tenant.id, owner.id)
            it.tenant = tenant
            it.user = owner
            it.role = TenantRole.TenantOwner
            tenantMemberRepo.persist(it)
        }

        return tenant
    }

    fun tenantSettings(): TenantSettingsResponse =
        TenantSettingsResponse(tenant().id, tenant().name, tenant().displayName)

    @Transactional
    fun updateTenantSettings(request: UpdateNamedSettingsRequest): TenantSettingsResponse {
        tenant().displayName = request.displayName!!.trim()
        return TenantSettingsResponse(tenant().id, tenant().name, tenant().displayName)
    }

    fun validateTenantName(name: String) {
        if (!TenantName.isValid(name)) {
            throw BadRequestException("Invalid tenant name")
        }
        if (tenantRepo.existsByName(name)) {
            throw ConflictException("Tenant name already exists")
        }
    }

    fun findTenantByName(name: String): Tenant {
        return tenantRepo.findByName(name)
            ?: throw NotFoundException("Tenant not found")
    }

    fun findTenantById(id: Long): Tenant =
        tenantRepo.findById(id)
            ?: throw NotFoundException("Tenant not found")

    fun normalizeDomain(input: String): String {
        val domain = input.trim().lowercase()
        if (
            domain.isBlank() ||
            domain.contains("://") ||
            domain.contains("/") ||
            domain.contains(":") ||
            domain.length > 255 ||
            !DOMAIN_REGEX.matches(domain)
        ) {
            throw BadRequestException("Invalid tenant domain")
        }
        return domain
    }

    private fun tenant(): Tenant = requestContext.tenant!!

    private companion object {
        val DOMAIN_REGEX = Regex("^(?=.{1,255}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$")
    }
}

fun Tenant.toManagedTenantSummary(): ManagedUserTenantSummary =
    ManagedUserTenantSummary(id, name, displayName)

private fun TenantRole.toTenantsApiRole(): ApiTenantRole =
    when (this) {
        TenantRole.TenantOwner -> ApiTenantRole.TenantOwner
        TenantRole.TenantMember -> ApiTenantRole.TenantMember
    }

private fun User.toTenantAdminSummary(): TenantSummaryAdminsInner =
    TenantSummaryAdminsInner(id, email, displayName, role.toUsersApiRole().let {
        when (it) {
            com.lunva.api.generated.users.model.ServerRole.ServerOwner -> TenantSummaryAdminsInner.ServerRole.ServerOwner
            com.lunva.api.generated.users.model.ServerRole.ServerMember -> TenantSummaryAdminsInner.ServerRole.ServerMember
        }
    })
