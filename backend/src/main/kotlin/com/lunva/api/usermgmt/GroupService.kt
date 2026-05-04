package com.lunva.api.usermgmt

import com.lunva.api.auth.ConflictException
import com.lunva.api.auth.NotFoundException
import com.lunva.api.domain.*
import com.lunva.api.auth.filterchain.RequestContext
import com.lunva.api.generated.groups.model.CreateGroupRequest
import com.lunva.api.generated.groups.model.GroupMemberSummary
import com.lunva.api.generated.groups.model.GroupMemberSummaryUser
import com.lunva.api.generated.groups.model.GroupRole as ApiGroupRole
import com.lunva.api.generated.groups.model.GroupSummary
import com.lunva.api.generated.groups.model.GroupsSummary
import com.lunva.api.generated.groups.model.SimplifiedGroupSummary
import com.lunva.api.generated.groups.model.UpdateGroupRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional

@ApplicationScoped
class GroupService @Inject constructor(
    private val tenantGroupRepo: TenantGroupRepo,
    private val userService: UserService,
    private val groupMemberRepo: TenantGroupMemberRepo,
    private val requestContext: RequestContext,
) {

    fun getGroups(): GroupsSummary {
        return GroupsSummary(
            requestContext.tenant!!.groups
            .sortedBy { it.name }
            .map {
                SimplifiedGroupSummary(it.id, it.name, it.displayName)
            }
        )
    }

    fun getGroup(groupName: String): GroupSummary {
        return requestContext.tenant!!.groups
            .filter { it.name == groupName }
            .map {
                val members = it.members.map { GroupMemberSummary(it.user.toGroupMemberSummaryUser(), it.role.toGroupsApiRole()) }
                GroupSummary(it.id, it.name, it.displayName, members)
            }.firstOrNull()
            ?: throw NotFoundException("Group $groupName not found")
    }

    @Transactional
    fun createGroup(request: CreateGroupRequest): GroupSummary {
        val tenant = requestContext.tenant!!
        val groupName = request.name!!.trim().lowercase()
        val user = requestContext.user!!

        if (tenantGroupRepo.findByTenantAndName(tenant, groupName) != null) {
            throw ConflictException("Group already exists")
        }

        val group = TenantGroup().also {
            it.tenant = tenant
            it.name = groupName
            it.displayName = request.displayName!!
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
        val tenant = requestContext.tenant!!

        val group = tenantGroupRepo.findByTenantAndName(tenant, oldName)?.also {
            it.name = request.name!!
            it.displayName = request.displayName!!
            it.members.forEach { member -> member.delete() }
        } ?: throw NotFoundException("Group $oldName not found")

        request.members?.forEach {
            TenantGroupMember().also { member ->
                member.id = TenantGroupMemberId(group.id, it.id)
                member.group = group
                member.user = userService.findUser(it.id)
                member.role = it.role.toDomainRole()
                member.persist()
            }
        }

        return getGroup(request.name!!)
    }
}

private fun User.toGroupMemberSummaryUser(): GroupMemberSummaryUser =
    GroupMemberSummaryUser(
        id,
        email,
        displayName,
        when (role) {
            ServerRole.ServerOwner -> GroupMemberSummaryUser.ServerRole.ServerOwner
            ServerRole.ServerMember -> GroupMemberSummaryUser.ServerRole.ServerMember
        },
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
