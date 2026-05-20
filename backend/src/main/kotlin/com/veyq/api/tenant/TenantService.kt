package com.veyq.api.tenant

import com.veyq.api.auth.BadRequestException
import com.veyq.api.auth.ConflictException
import com.veyq.api.auth.ForbiddenException
import com.veyq.api.auth.NotFoundException
import com.veyq.api.auth.filterchain.RequestContext
import com.veyq.api.domain.*
import com.veyq.api.generated.model.CreateTenantRequest
import com.veyq.api.generated.model.TenantRole as ApiTenantRole
import com.veyq.api.generated.model.TenantSettingsResponse
import com.veyq.api.generated.model.TenantSummary
import com.veyq.api.generated.model.TenantsSummary
import com.veyq.api.generated.model.UpdateTenantSettingsRequest
import com.veyq.api.generated.model.ManagedUserTenantSummary
import com.veyq.api.generated.model.UserSummary
import com.veyq.api.library.LibraryDeletionService
import com.veyq.api.usermgmt.user.toUsersApiRole
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import java.time.Instant

@ApplicationScoped
class TenantService @Inject constructor(
    private val tenantRepo: TenantRepo,
    private val tenantMemberRepo: TenantMemberRepo,
    private val libraryDeletionService: LibraryDeletionService,
    private val requestContext: RequestContext,
) {

    @Transactional
    fun createTenantForCurrentUser(request: CreateTenantRequest): TenantSummary {
        val user = requestContext.user!!
        val name = request.name!!.trim().lowercase()
        if (!TenantName.isValid(name)) throw BadRequestException("Invalid tenant name")
        val tenant = createTenant(name, request.displayName!!, request.domain!!, user)

        return TenantSummary(tenant.id, tenant.name, tenant.displayName, tenant.domain, ApiTenantRole.TenantOwner, listOf(user.toTenantAdminSummary()))
    }

    fun listMyTenants(): TenantsSummary {
        return TenantsSummary(
            requestContext.user!!.tenantMemberships
                .filter { it.acceptedAt != null }
                .sortedBy { it.tenant.name }
                .map {
                    val tenantOwners = findUsersWithRole(it.tenant, TenantRole.TenantOwner).map { user -> user.toTenantAdminSummary() }
                    TenantSummary(it.tenant.id, it.tenant.name, it.tenant.displayName, it.tenant.domain, it.role.toTenantsApiRole(), tenantOwners)
                }
        )
    }

    private fun findUsersWithRole(tenant: Tenant, role: TenantRole): List<User> {
        return tenant.members.filter { it.role == role && it.acceptedAt != null }.map { it.user }
    }

    fun requireTenantMembership(tenant: Tenant, user: User) {
        tenantMemberRepo.findByTenantAndUser(tenant, user)?.takeIf { it.acceptedAt != null }
            ?: throw ForbiddenException("User is not member of this tenant")
    }

    fun addUserToTenant(tenant: Tenant, user: User, role: TenantRole = TenantRole.TenantMember, acceptedAt: Instant? = null): TenantMember {
        tenantMemberRepo.findByTenantAndUser(tenant, user)?.let { return it }

        TenantMember().also {
            it.id = TenantMemberId(tenant.id, user.id)
            it.tenant = tenant
            it.user = user
            it.role = role
            it.acceptedAt = acceptedAt
            it.persist()
            return it
        }
    }

    private fun createTenant(name: String, displayName: String, domain: String, owner: User): Tenant {
        if (tenantRepo.existsByName(name)) {
            throw ConflictException("Tenant name already exists")
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
            it.acceptedAt = Instant.now()
            tenantMemberRepo.persist(it)
        }

        return tenant
    }

    fun tenantSettings(): TenantSettingsResponse =
        TenantSettingsResponse(tenant().id, tenant().name, tenant().displayName)

    @Transactional
    fun updateTenantSettings(request: UpdateTenantSettingsRequest): TenantSettingsResponse {
        tenant().displayName = request.displayName!!.trim()
        return TenantSettingsResponse(tenant().id, tenant().name, tenant().displayName)
    }

    @Transactional
    fun deleteTenant() {
        libraryDeletionService.deleteTenant(tenant())
        requestContext.tenant = null
    }

    fun validateTenantName(name: String) {
        if (tenantRepo.existsByName(name)) {
            throw ConflictException("Tenant name already exists")
        }
    }

    fun findTenantByName(name: String): Tenant {
        return tenantRepo.findByName(name)
            ?: throw NotFoundException("Tenant not found")
    }

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

private fun User.toTenantAdminSummary(): UserSummary =
    UserSummary(id, email, displayName, role.toUsersApiRole())
