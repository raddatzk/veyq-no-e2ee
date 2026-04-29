package com.lunva.api.usermgmt

import com.lunva.api.domain.GroupRole
import com.lunva.api.auth.filterchain.GroupOwnerRequired
import com.lunva.api.tenant.TenantService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType

data class CreateGroupRequest(
    @field:NotBlank(message = "name must not be blank") val name: String?,
    @field:NotBlank(message = "displayName must not be blank") val displayName: String?,
)

data class GroupMemberRequest(
    @field:NotBlank(message = "id must not be blank") val id: Long?,
    @field:NotBlank(message = "role must not be blank") val role: GroupRole,
)

data class UpdateGroupRequest(
    @field:NotBlank(message = "name must not be blank") val name: String?,
    @field:NotBlank(message = "displayName must not be blank") val displayName: String?,
    val members: Set<GroupMemberRequest>?
)

data class GroupMemberSummary(
    val user: UserSummary,
    val role: GroupRole?,
)

data class GroupSummary(
    val id: Long,
    val name: String,
    val displayName: String,
    val members: List<GroupMemberSummary>
)

data class SimplifiedGroupSummary(
    val id: Long,
    val name: String,
    val displayName: String,
)

data class GroupsSummary(
    val groups: List<SimplifiedGroupSummary>
)

@Path("/api/{tenantName}/groups")
@ApplicationScoped
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
class GroupResource @Inject constructor(
    private val tenantService: TenantService,
    private val groupService: GroupService,
) {

    @GET
    fun getGroups(@PathParam("tenantName") tenantName: String): GroupsSummary {
        return groupService.getGroups()
    }

    @GET
    @Path("/{groupName}")
    fun getGroup(
        @PathParam("tenantName") tenantName: String,
        @PathParam("groupName") groupName: String): GroupSummary {
        return groupService.getGroup(groupName)
    }

    @POST
    fun createGroup(
        @PathParam("tenantName") tenantName: String,
        @Valid request: CreateGroupRequest,
    ): GroupSummary {
        return groupService.createGroup(request)
    }

    @POST
    @Path("/{groupName}")
    @GroupOwnerRequired
    fun updateGroup(
        @PathParam("tenantName") tenantName: String,
        @PathParam("groupName") groupName: String,
        request: UpdateGroupRequest,
    ): GroupSummary {
        return groupService.updateGroup(groupName, request)
    }
}
