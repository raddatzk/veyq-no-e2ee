package com.veyq.api.usermgmt.group

import com.veyq.api.auth.ConflictException
import com.veyq.api.auth.NotFoundException
import com.veyq.api.domain.*
import com.veyq.api.auth.filterchain.RequestContext
import com.veyq.api.generated.model.CreateGroupRequest
import com.veyq.api.generated.model.GroupMemberSummary
import com.veyq.api.generated.model.GroupRole as ApiGroupRole
import com.veyq.api.generated.model.GroupSummary
import com.veyq.api.generated.model.GroupsSummary
import com.veyq.api.generated.model.PublicUserSummary
import com.veyq.api.generated.model.SimplifiedGroupSummary
import com.veyq.api.generated.model.UpdateGroupRequest
import com.veyq.api.library.LibraryDeletionService
import com.veyq.api.usermgmt.user.UserService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional

@ApplicationScoped
class GroupService @Inject constructor(
    private val tenantGroupRepo: TenantGroupRepo,
    private val userService: UserService,
    private val groupMemberRepo: TenantGroupMemberRepo,
    private val tenantMemberRepo: TenantMemberRepo,
    private val libraryDeletionService: LibraryDeletionService,
    private val requestContext: RequestContext,
) {

    fun getGroups(): GroupsSummary {
        return GroupsSummary(
            visibleGroups()
            .sortedBy { it.name }
            .map {
                SimplifiedGroupSummary(it.id, it.name, it.displayName)
            }
        )
    }

    fun getGroup(groupName: String): GroupSummary {
        val group = tenantGroupRepo.findByTenantAndName(requestContext.requireTenant(), groupName)
            ?.takeIf { canReadGroup(it) }
            ?: throw NotFoundException("Group $groupName not found")

        return group.toGroupSummary()
    }

    @Transactional
    fun createGroup(request: CreateGroupRequest): GroupSummary {
        val tenant = requestContext.requireTenant()
        val groupName = request.name.trim().lowercase()
        val user = requestContext.requireUser()

        if (tenantGroupRepo.findByTenantAndName(tenant, groupName) != null) {
            throw ConflictException("Group already exists")
        }

        val group = TenantGroup().also {
            it.tenant = tenant
            it.name = groupName
            it.displayName = request.displayName
            tenantGroupRepo.persist(it)
        }
        tenantGroupRepo.flush()

        TenantGroupMember().also {
            it.id = TenantGroupMemberId(group.id, user.id)
            it.group = group
            it.user = user
            it.role = GroupRole.GroupOwner
            groupMemberRepo.persist(it)
        }

        val members = listOf(GroupMemberSummary(user.toGroupMemberSummaryUser(), ApiGroupRole.GroupOwner))

        return GroupSummary(group.id, group.name, group.displayName, members)
    }

    @Transactional
    fun updateGroup(oldName: String, request: UpdateGroupRequest): GroupSummary {
        val tenant = requestContext.requireTenant()

        val group = tenantGroupRepo.findByTenantAndName(tenant, oldName)?.also {
            val members = request.members ?: emptySet()
            if (members.none { member -> member.role == ApiGroupRole.GroupOwner }) {
                throw ConflictException("At least one group owner is required")
            }

            val seenUserIds = mutableSetOf<Long>()
            val usersById = mutableMapOf<Long, User>()
            members.forEach { member ->
                if (!seenUserIds.add(member.id)) {
                    throw ConflictException("Duplicate group member")
                }
                val user = userService.findUser(member.id)
                val tenantMember = tenantMemberRepo.findByTenantAndUser(tenant, user)
                    ?: throw NotFoundException("Tenant user not found")
                if (tenantMember.status != TenantMemberStatus.Active) {
                    throw NotFoundException("Tenant user not found")
                }
                usersById[member.id] = user
            }

            it.name = request.name
            it.displayName = request.displayName
            it.members.forEach { member -> member.delete() }
            it.members.clear()

            members.forEach { requestMember ->
                TenantGroupMember().also { member ->
                    member.id = TenantGroupMemberId(it.id, requestMember.id)
                    member.group = it
                    member.user = usersById.getValue(requestMember.id)
                    member.role = requestMember.role.toDomainRole()
                    groupMemberRepo.persist(member)
                    it.members.add(member)
                }
            }
        } ?: throw NotFoundException("Group $oldName not found")

        return group.toGroupSummary()
    }

    @Transactional
    fun deleteGroup(groupName: String) {
        val group = tenantGroupRepo.findByTenantAndName(requestContext.requireTenant(), groupName)
            ?: throw NotFoundException("Group $groupName not found")
        libraryDeletionService.deleteGroup(group)
    }

    private fun visibleGroups(): List<TenantGroup> {
        val tenant = requestContext.requireTenant()
        return if (currentTenantRole() == TenantRole.TenantOwner) {
            tenant.groups.toList()
        } else {
            tenant.groups.filter { canReadGroup(it) }
        }
    }

    private fun canReadGroup(group: TenantGroup): Boolean =
        currentTenantRole() == TenantRole.TenantOwner ||
                groupMemberRepo.findByGroupAndUser(group, requestContext.requireUser()) != null

    private fun currentTenantRole(): TenantRole =
        tenantMemberRepo.findByTenantAndUser(requestContext.requireTenant(), requestContext.requireUser())!!.role

    private fun TenantGroup.toGroupSummary(): GroupSummary =
        GroupSummary(
            id,
            name,
            displayName,
            members.map {
                GroupMemberSummary(
                    it.user.toGroupMemberSummaryUser(),
                    if (canManageGroup(this)) it.role.toGroupsApiRole() else null,
                )
            }
        )

    private fun canManageGroup(group: TenantGroup): Boolean {
        if (requestContext.requireUser().role == ServerRole.ServerOwner) return true
        if (currentTenantRole() == TenantRole.TenantOwner) return true
        return groupMemberRepo.findByGroupAndUser(group, requestContext.requireUser())?.role == GroupRole.GroupOwner
    }
}

private fun User.toGroupMemberSummaryUser(): PublicUserSummary =
    PublicUserSummary(
        id,
        displayName,
        permalink,
    )

private fun GroupRole.toGroupsApiRole(): ApiGroupRole =
    when (this) {
        GroupRole.GroupOwner -> ApiGroupRole.GroupOwner
        GroupRole.GroupMember -> ApiGroupRole.GroupMember
    }

private fun ApiGroupRole.toDomainRole(): GroupRole =
    when (this) {
        ApiGroupRole.GroupOwner -> GroupRole.GroupOwner
        ApiGroupRole.GroupMember -> GroupRole.GroupMember
    }
