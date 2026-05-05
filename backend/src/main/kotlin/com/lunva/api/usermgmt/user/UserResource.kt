package com.lunva.api.usermgmt.user

import com.lunva.api.auth.filterchain.ServerRolesRequired
import com.lunva.api.domain.ServerRole
import com.lunva.api.generated.api.UsersApi
import com.lunva.api.generated.model.AcceptInvitationRequest
import com.lunva.api.generated.model.ChangePasswordRequest
import com.lunva.api.generated.model.ConfirmEmailChangeRequest
import com.lunva.api.generated.model.CreateManagedUserRequest
import com.lunva.api.generated.model.ResetPasswordRequest
import com.lunva.api.generated.model.UpdateManagedUserRoleRequest
import com.lunva.api.generated.model.UpdateUserSettingsRequest
import com.lunva.api.usermgmt.ResetPasswordService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response

@ApplicationScoped
class UserResource(
    private val userService: UserService,
    private val resetPasswordService: ResetPasswordService,
) : UsersApi {

    override fun me(): Response =
        Response.ok(userService.me()).build()

    override fun updateMySettings(request: UpdateUserSettingsRequest): Response =
        Response.ok(userService.updateMySettings(request)).build()

    override fun confirmMyEmailChange(request: ConfirmEmailChangeRequest): Response =
        Response.ok(userService.confirmMyEmailChange(request)).build()

    override fun changeMyPassword(request: ChangePasswordRequest): Response {
        userService.changeMyPassword(request)
        return Response.noContent().build()
    }

    override fun forgotMyPassword(): Response {
        userService.forgotMyPassword()
        return Response.noContent().build()
    }

    override fun resetMyPassword(userId: Long, request: ResetPasswordRequest): Response? {
        userService.resetMyPassword(userId, request)
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
        request: UpdateManagedUserRoleRequest,
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
