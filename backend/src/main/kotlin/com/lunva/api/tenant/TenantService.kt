package com.lunva.api.tenant

import com.lunva.api.auth.BadRequestException
import com.lunva.api.auth.ConflictException
import com.lunva.api.auth.ForbiddenException
import com.lunva.api.auth.NotFoundException
import com.lunva.api.domain.*
import com.lunva.api.security.RequestContext
import com.lunva.api.user.UserService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import java.time.Instant

@ApplicationScoped
class TenantService @Inject constructor(
    private val tenantRepo: TenantRepo,
    private val tenantMemberRepo: TenantMemberRepo,
    private val requestContext: RequestContext,
) {

    @Transactional
    fun createTenantForCurrentUser(request: CreateTenantRequest): TenantSummary {
        val user = requestContext.user!!
        val tenant = createTenant(request.name!!, request.displayName!!, user)

        return TenantSummary(tenant.id, tenant.name, tenant.displayName, listOf(UserService.toUserSummary(user)))
    }

    fun listMyTenants(): TenantsSummary {
        return TenantsSummary(
            requestContext.user!!.tenantMemberships
            .sortedBy { it.tenant.name }
            .map {
                val tenantOwners = findUsersWithRole(it.tenant, TenantRole.TENANT_OWNER).map { UserService.toUserSummary(it) }
                TenantSummary(it.tenant.id, it.tenant.name, it.tenant.displayName, tenantOwners)
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

    private fun createTenant(name: String, displayName: String, owner: User): Tenant {
        validateTenantName(name)
        val now = Instant.now()

        val tenant = Tenant().also {
            it.name = name
            it.displayName = displayName.trim()
            tenantRepo.persist(it)
        }
        tenantRepo.flush()

        TenantMember().also {
            it.id = TenantMemberId(tenant.id, owner.id)
            it.tenant = tenant
            it.user = owner
            it.role = TenantRole.TENANT_OWNER
            tenantMemberRepo.persist(it)
        }

        return tenant
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
}
