package com.veyq.app.features.admin.invitemembersroles

import com.veyq.app.generated.model.IdentityPublicKey
import com.veyq.app.generated.model.TenantMember
import com.veyq.app.generated.model.TenantMemberList
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.identity.UserRepository
import com.veyq.app.shared.keymgmt.IdentityKeyRepository
import com.veyq.app.shared.security.SessionService
import com.veyq.app.shared.tenancy.MembershipRepository
import com.veyq.app.shared.tenancy.TenantRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.util.Base64

/**
 * uc.admin.invite-members-roles (member list) — enumerate a tenant's members with their published
 * identity public keys, so an Owner can re-wrap a rotated scope to everyone. Public material only.
 */
@ApplicationScoped
class MemberListHandler(
    private val users: UserRepository,
    private val identityKeys: IdentityKeyRepository,
    private val memberships: MembershipRepository,
    private val tenants: TenantRepository,
    private val sessions: SessionService,
) {
    private val enc = Base64.getUrlEncoder().withoutPadding()

    @Transactional
    fun listTenantMembers(auth: String?, tenantId: String): TenantMemberList {
        val callerId = sessions.resolve(auth) ?: throw unauthorized()
        tenants.findById(tenantId) ?: throw notFound("Tenant")
        val callerMembership = memberships.find(tenantId, callerId)
        if (callerMembership == null || callerMembership.status != "ACTIVE") {
            throw ApiException(403, "FORBIDDEN", "Tenant membership required")
        }

        val members = memberships.listByTenant(tenantId).mapNotNull { membership ->
            val user = users.findById(membership.userId) ?: return@mapNotNull null
            val identity = identityKeys.findByUserId(user.id) ?: return@mapNotNull null
            TenantMember(
                userId = user.id,
                displayName = user.displayName,
                role = membership.role,
                status = membership.status,
                identityPublicKey = IdentityPublicKey(
                    suiteId = identity.suiteId,
                    kemPublicKey = enc.encodeToString(identity.kemPublicKey),
                    signPublicKey = enc.encodeToString(identity.signPublicKey),
                ),
            )
        }
        return TenantMemberList(members = members)
    }

    private fun unauthorized() = ApiException(401, "UNAUTHORIZED", "Missing or invalid session")
    private fun notFound(what: String) = ApiException(404, "NOT_FOUND", "$what not found")
}
