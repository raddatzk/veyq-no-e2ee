package com.veyq.app.features.admin.invitemembersroles

import com.veyq.app.generated.model.IdentityPublicKey
import com.veyq.app.generated.model.TenantMember
import com.veyq.app.generated.model.TenantMemberList
import com.veyq.app.shared.identity.UserRepository
import com.veyq.app.shared.keymgmt.IdentityKeyRepository
import com.veyq.app.shared.security.TenantAdminGuard
import com.veyq.app.shared.tenancy.MembershipRepository
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
    private val guard: TenantAdminGuard,
) {
    private val enc = Base64.getUrlEncoder().withoutPadding()

    @Transactional
    fun listTenantMembers(auth: String?, tenantId: String): TenantMemberList {
        guard.requireActiveMember(auth, tenantId)

        // Batch the user + identity lookups (one IN-query each) instead of an N+1 per member row.
        val rows = memberships.listByTenant(tenantId)
        val userIds = rows.map { it.userId }
        val usersById = users.listByIds(userIds).associateBy { it.id }
        val identityByUserId = identityKeys.listByUserIds(userIds).associateBy { it.userId }
        val members = rows.mapNotNull { membership ->
            val user = usersById[membership.userId] ?: return@mapNotNull null
            val identity = identityByUserId[user.id] ?: return@mapNotNull null
            TenantMember(
                userId = user.id,
                displayName = user.displayName,
                role = membership.role.name,
                status = membership.status.name,
                identityPublicKey = IdentityPublicKey(
                    suiteId = identity.suiteId,
                    kemPublicKey = enc.encodeToString(identity.kemPublicKey),
                    signPublicKey = enc.encodeToString(identity.signPublicKey),
                ),
            )
        }
        return TenantMemberList(members = members)
    }
}
