package com.veyq.app.features.spaces.groups

import com.veyq.app.generated.model.Blob
import com.veyq.app.generated.model.CreateGroupRequest
import com.veyq.app.generated.model.GroupList
import com.veyq.app.generated.model.GroupSummary
import com.veyq.app.generated.model.KemPublicKey
import com.veyq.app.platform.error.ApiException
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
