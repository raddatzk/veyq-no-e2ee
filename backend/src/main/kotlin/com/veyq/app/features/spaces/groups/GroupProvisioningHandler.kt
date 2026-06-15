package com.veyq.app.features.spaces.groups

import com.veyq.app.generated.model.Blob
import com.veyq.app.generated.model.CreateGroupRequest
import com.veyq.app.generated.model.AddGroupMemberRequest
import com.veyq.app.generated.model.GroupList
import com.veyq.app.generated.model.GroupMember as ApiGroupMember
import com.veyq.app.generated.model.GroupMemberList
import com.veyq.app.generated.model.GroupSummary
import com.veyq.app.generated.model.KemPublicKey
import com.veyq.app.generated.model.SetGroupRoleRequest
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.identity.UserRepository
import com.veyq.app.shared.content.ScopeContentKey
import com.veyq.app.shared.content.ScopeContentKeyRepository
import com.veyq.app.shared.crypto.CryptoSuiteRegistry
import com.veyq.app.shared.keymgmt.Scope
import com.veyq.app.shared.keymgmt.ScopeGrant
import com.veyq.app.shared.keymgmt.ScopeGrantRepository
import com.veyq.app.shared.keymgmt.ScopeRepository
import com.veyq.app.shared.security.SessionService
import com.veyq.app.shared.tenancy.GroupMembership
import com.veyq.app.shared.tenancy.GroupMembershipRepository
import com.veyq.app.shared.tenancy.GroupRole
import com.veyq.app.shared.tenancy.MembershipRepository
import com.veyq.app.shared.tenancy.TenantRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * uc.spaces.groups — a Group is a collaborative sub-space within a tenant, for a subset of its members
 * (glossary: Tenant > Group > Personal). Any active tenant member may create one and becomes its first
 * GroupOwner. The group is a GROUP_ROOT scope: keys are minted client-side, the name is sealed under the
 * group content key, and access is the wrapped scope grant — the server stays blind to name and content.
 */
@ApplicationScoped
class GroupProvisioningHandler(
    private val tenants: TenantRepository,
    private val memberships: MembershipRepository,
    private val scopes: ScopeRepository,
    private val grants: ScopeGrantRepository,
    private val contentKeys: ScopeContentKeyRepository,
    private val groupMembers: GroupMembershipRepository,
    private val users: UserRepository,
    private val sessions: SessionService,
    private val suites: CryptoSuiteRegistry,
) {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()

    @Transactional
    fun createGroup(auth: String?, tenantId: String, req: CreateGroupRequest): GroupSummary {
        val userId = requireTenantMember(tenantId, auth)
        requireSuite(req.scopePublicKey.suiteId)
        requireSuite(req.wrappedScopePrivateKey.suiteId)
        requireSuite(req.contentKey.suiteId)
        requireSuite(req.sealedName.suiteId)
        val now = Instant.now()

        val scope = Scope().apply {
            id = newId(); this.tenantId = tenantId; kind = "GROUP_ROOT"
            suiteId = req.scopePublicKey.suiteId; kemPublicKey = dec.decode(req.scopePublicKey.kemPublicKey)
            sealedName = dec.decode(req.sealedName.bytes); nameSuiteId = req.sealedName.suiteId; createdAt = now
        }
        scopes.persist(scope)
        grants.persist(ScopeGrant().apply {
            id = newId(); scopeId = scope.id; this.userId = userId
            suiteId = req.wrappedScopePrivateKey.suiteId; wrappedPrivateKey = dec.decode(req.wrappedScopePrivateKey.bytes); createdAt = now
        })
        contentKeys.persist(ScopeContentKey().apply {
            id = newId(); scopeId = scope.id; suiteId = req.contentKey.suiteId; wrappedKey = dec.decode(req.contentKey.bytes); createdAt = now
        })
        groupMembers.persist(GroupMembership().apply {
            id = newId(); scopeId = scope.id; this.userId = userId; role = GroupRole.GroupOwner.name; createdAt = now
        })
        return toSummary(scope, GroupRole.GroupOwner.name)
    }

    @Transactional
    fun listGroups(auth: String?, tenantId: String): GroupList {
        val userId = requireTenantMember(tenantId, auth)
        val groups = scopes.listGroupsByTenantId(tenantId).mapNotNull { scope ->
            val gm = groupMembers.find(scope.id, userId) ?: return@mapNotNull null
            grants.find(scope.id, userId) ?: return@mapNotNull null // access = holds the grant
            toSummary(scope, gm.role)
        }
        return GroupList(groups = groups)
    }

    @Transactional
    fun listGroupMembers(auth: String?, tenantId: String, scopeId: String): GroupMemberList {
        val userId = sessions.resolve(auth) ?: throw unauthorized()
        requireGroupScope(tenantId, scopeId)
        groupMembers.find(scopeId, userId) ?: throw ApiException(403, "NOT_A_GROUP_MEMBER", "You are not a member of this group")
        return GroupMemberList(members = groupMembers.listByScopeId(scopeId).map(::toApiMember))
    }

    @Transactional
    fun addGroupMember(auth: String?, tenantId: String, scopeId: String, req: AddGroupMemberRequest): ApiGroupMember {
        requireGroupOwner(tenantId, scopeId, auth)
        val target = memberships.find(tenantId, req.userId)
        if (target == null || target.status != "ACTIVE") throw ApiException(400, "NOT_A_TENANT_MEMBER", "Target is not an active tenant member")
        if (groupMembers.find(scopeId, req.userId) != null) throw ApiException(409, "ALREADY_GROUP_MEMBER", "Already a member of this group")
        requireSuite(req.wrappedScopePrivateKey.suiteId)
        val role = normalizeRole(req.role ?: GroupRole.GroupMember.name)
        val now = Instant.now()
        grants.persist(ScopeGrant().apply {
            id = newId(); this.scopeId = scopeId; userId = req.userId
            suiteId = req.wrappedScopePrivateKey.suiteId; wrappedPrivateKey = dec.decode(req.wrappedScopePrivateKey.bytes); createdAt = now
        })
        val gm = GroupMembership().apply { id = newId(); this.scopeId = scopeId; userId = req.userId; this.role = role; createdAt = now }
        groupMembers.persist(gm)
        return toApiMember(gm)
    }

    @Transactional
    fun setGroupMemberRole(auth: String?, tenantId: String, scopeId: String, userId: String, req: SetGroupRoleRequest): ApiGroupMember {
        requireGroupOwner(tenantId, scopeId, auth)
        val gm = groupMembers.find(scopeId, userId) ?: throw ApiException(404, "NOT_A_GROUP_MEMBER", "Not a member of this group")
        val role = normalizeRole(req.role)
        // never demote the last remaining GroupOwner — the group must keep an owner
        if (gm.role == GroupRole.GroupOwner.name && role != GroupRole.GroupOwner.name && groupMembers.countOwners(scopeId) <= 1) {
            throw ApiException(409, "LAST_OWNER", "Cannot demote the last GroupOwner")
        }
        gm.role = role
        return toApiMember(gm)
    }

    @Transactional
    fun removeGroupMember(auth: String?, tenantId: String, scopeId: String, userId: String) {
        requireGroupOwner(tenantId, scopeId, auth)
        val gm = groupMembers.find(scopeId, userId) ?: throw ApiException(404, "NOT_A_GROUP_MEMBER", "Not a member of this group")
        if (gm.role == GroupRole.GroupOwner.name && groupMembers.countOwners(scopeId) <= 1) {
            throw ApiException(409, "LAST_OWNER", "Cannot remove the last GroupOwner")
        }
        grants.find(scopeId, userId)?.let { grants.delete(it) }
        groupMembers.delete(gm)
    }

    private fun normalizeRole(role: String): String = when (role) {
        GroupRole.GroupOwner.name, GroupRole.GroupMember.name -> role
        else -> throw ApiException(400, "INVALID_ROLE", "Role must be GroupOwner or GroupMember")
    }

    private fun toApiMember(gm: GroupMembership) =
        ApiGroupMember(userId = gm.userId, displayName = users.findById(gm.userId)?.displayName ?: "", role = gm.role)

    /** The GROUP_ROOT scope, validated to live under this tenant. */
    private fun requireGroupScope(tenantId: String, scopeId: String): Scope {
        val scope = scopes.findById(scopeId) ?: throw ApiException(404, "GROUP_NOT_FOUND", "Group not found")
        if (scope.kind != "GROUP_ROOT" || scope.tenantId != tenantId) throw ApiException(404, "GROUP_NOT_FOUND", "Group not found in this tenant")
        return scope
    }

    private fun requireGroupOwner(tenantId: String, scopeId: String, auth: String?): String {
        val userId = sessions.resolve(auth) ?: throw unauthorized()
        requireGroupScope(tenantId, scopeId)
        val gm = groupMembers.find(scopeId, userId)
        if (gm == null || gm.role != GroupRole.GroupOwner.name) throw ApiException(403, "NOT_A_GROUP_OWNER", "GroupOwner role required")
        return userId
    }

    private fun unauthorized() = ApiException(401, "UNAUTHORIZED", "Missing or invalid session")

    private fun requireTenantMember(tenantId: String, auth: String?): String {
        val userId = sessions.resolve(auth) ?: throw ApiException(401, "UNAUTHORIZED", "Missing or invalid session")
        tenants.findById(tenantId) ?: throw ApiException(404, "TENANT_NOT_FOUND", "Tenant not found")
        val m = memberships.find(tenantId, userId)
        if (m == null || m.status != "ACTIVE") throw ApiException(403, "NOT_A_MEMBER", "Not an active member of this tenant")
        return userId
    }

    private fun requireSuite(suiteId: String) {
        if (!suites.isKnown(suiteId)) throw ApiException(400, "UNKNOWN_SUITE", "Unknown crypto suite")
    }

    private fun toSummary(scope: Scope, role: String) = GroupSummary(
        scopeId = scope.id,
        tenantId = scope.tenantId!!,
        role = role,
        scopePublicKey = KemPublicKey(suiteId = scope.suiteId, kemPublicKey = enc.encodeToString(scope.kemPublicKey)),
        sealedName = Blob(suiteId = scope.nameSuiteId ?: scope.suiteId, bytes = enc.encodeToString(scope.sealedName ?: ByteArray(0))),
    )

    private fun newId() = UUID.randomUUID().toString()
}
