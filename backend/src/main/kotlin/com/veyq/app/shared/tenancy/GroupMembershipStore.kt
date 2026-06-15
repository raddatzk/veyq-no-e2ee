package com.veyq.app.shared.tenancy

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

enum class GroupRole { GroupOwner, GroupMember }

/**
 * Membership of a Group (a GROUP_ROOT scope within a tenant), with its own role. Access to the content is
 * still "holds the scope grant" (ADR 0005); this row records the management role (GroupOwner can manage
 * members + roles + delete the group). A tenant member may belong to many groups.
 */
@Entity
@Table(name = "veyq_group_membership")
class GroupMembership {
    @Id var id: String = ""
    @Column(name = "scope_id", nullable = false) var scopeId: String = ""
    @Column(name = "user_id", nullable = false) var userId: String = ""
    @Column(nullable = false) var role: String = GroupRole.GroupMember.name
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.EPOCH
}

@ApplicationScoped
class GroupMembershipRepository : PanacheRepositoryBase<GroupMembership, String> {
    fun find(scopeId: String, userId: String): GroupMembership? =
        find("scopeId = ?1 and userId = ?2", scopeId, userId).firstResult()

    fun listByScopeId(scopeId: String): List<GroupMembership> = list("scopeId", scopeId)

    fun listByUserId(userId: String): List<GroupMembership> = list("userId", userId)

    fun countOwners(scopeId: String): Long = count("scopeId = ?1 and role = ?2", scopeId, GroupRole.GroupOwner.name)

    fun deleteByScopeId(scopeId: String): Long = delete("scopeId", scopeId)
}
