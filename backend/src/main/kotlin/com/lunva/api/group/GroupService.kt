package com.lunva.api.group

import com.lunva.api.auth.ConflictException
import com.lunva.api.auth.NotFoundException
import com.lunva.api.domain.*
import com.lunva.api.security.RequestContext
import com.lunva.api.user.UserService
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
                val members = it.members.map { GroupMemberSummary(UserService.toUserSummary(it.user), it.role) }
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
            it.role = GroupRole.GROUP_OWNER
            groupMemberRepo.persist(it)
        }

        val members = listOf(GroupMemberSummary(UserService.toUserSummary(user), GroupRole.GROUP_OWNER))

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
                member.id = TenantGroupMemberId(group.id, it.id!!)
                member.group = group
                member.user = userService.findUser(it.id)
                member.role = it.role
                member.persist()
            }
        }

        return getGroup(request.name!!)
    }
}