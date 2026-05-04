package com.lunva.api.usermgmt

import com.lunva.api.auth.filterchain.ServerRolesRequired
import com.lunva.api.domain.ServerRole
import com.lunva.api.generated.users.api.UsersApi
import com.lunva.api.generated.users.model.AcceptInvitationRequest
import com.lunva.api.generated.users.model.ChangePasswordRequest
import com.lunva.api.generated.users.model.CreateManagedUserRequest
import com.lunva.api.generated.users.model.UpdateManagedUserRequest
import com.lunva.api.generated.users.model.UpdateUserSettingsRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import jakarta.ws.rs.core.Response

@ApplicationScoped
class UserResource(
    private val userService: UserService,
) : UsersApi {

    override fun me(): Response =
        Response.ok(userService.me()).build()

    @Transactional
    override fun updateMySettings(request: UpdateUserSettingsRequest): Response =
        Response.ok(userService.updateMySettings(request)).build()

    @Transactional
    override fun changeMyPassword(request: ChangePasswordRequest): Response {
        userService.changeMyPassword(request)
        return Response.noContent().build()
    }

    @ServerRolesRequired(ServerRole.ServerOwner)
    override fun getAllUsers(): Response =
        Response.ok(userService.getAllUsers()).build()

    @ServerRolesRequired(ServerRole.ServerOwner)
    override fun createManagedUser(request: CreateManagedUserRequest): Response =
        Response.ok(userService.createUser(request)).build()

    @ServerRolesRequired(ServerRole.ServerOwner)
    override fun updateManagedUser(
        userId: Long,
        request: UpdateManagedUserRequest,
    ): Response =
        Response.ok(userService.updateManagedUser(userId, request)).build()

    @ServerRolesRequired(ServerRole.ServerOwner)
    override fun sendManagedUserInvitation(userId: Long): Response =
        Response.ok(userService.sendInvitation(userId)).build()

    override fun acceptInvitation(
        userId: Long,
        request: AcceptInvitationRequest,
    ): Response {
        val (response, accessCookie, refreshCookie) = userService.acceptInvitation(userId, request)

        return Response.ok(response)
            .cookie(accessCookie, refreshCookie)
            .build()
    }
}
