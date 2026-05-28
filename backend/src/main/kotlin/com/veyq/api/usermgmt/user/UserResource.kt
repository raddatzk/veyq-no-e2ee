package com.veyq.api.usermgmt.user

import com.veyq.api.auth.filterchain.ServerRolesRequired
import com.veyq.api.domain.ServerRole
import com.veyq.api.generated.api.UsersApi
import com.veyq.api.auth.passkey.PasskeyService
import com.veyq.api.generated.model.AcceptInvitationRequest
import com.veyq.api.generated.model.AcceptPasskeyInvitationRequest
import com.veyq.api.generated.model.ChangePasswordRequest
import com.veyq.api.generated.model.ConfirmEmailChangeRequest
import com.veyq.api.generated.model.CreateManagedUserRequest
import com.veyq.api.generated.model.CreatePasskeyRequest
import com.veyq.api.generated.model.ForgotPasswordRequest
import com.veyq.api.generated.model.PasskeyInvitationOptionsRequest
import com.veyq.api.generated.model.PasskeyResetOptionsRequest
import com.veyq.api.generated.model.ResetPasswordRequest
import com.veyq.api.generated.model.ResetPasskeyRequest
import com.veyq.api.generated.model.UpdateManagedUserRoleRequest
import com.veyq.api.generated.model.UpdatePasskeyRequest
import com.veyq.api.generated.model.UpdateUserSettingsRequest
import com.veyq.api.usermgmt.ResetPasswordService
import io.vertx.ext.web.RoutingContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@ApplicationScoped
class UserResource(
    private val userService: UserService,
    private val resetPasswordService: ResetPasswordService,
    private val passkeyService: PasskeyService,
    private val routingContext: RoutingContext,
) : UsersApi {

    override fun me(): Response =
        Response.ok(userService.me()).build()

    override fun updateMySettings(request: UpdateUserSettingsRequest): Response =
        Response.ok(userService.updateMySettings(request)).build()

    override fun getMyAvatar(): Response =
        userService.myAvatar()

    override fun updateMyAvatar(fileInputStream: InputStream): Response {
        val temp = Files.createTempFile("veyq-avatar-", ".tmp")
        return try {
            fileInputStream.use { input -> Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING) }
            Response.ok(userService.updateMyAvatar(temp)).build()
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    override fun deleteMyAvatar(): Response =
        Response.ok(userService.deleteMyAvatar()).build()

    override fun confirmMyEmailChange(request: ConfirmEmailChangeRequest): Response =
        Response.ok(userService.confirmMyEmailChange(request)).build()

    override fun changeMyPassword(request: ChangePasswordRequest): Response {
        userService.changeMyPassword(request)
        return Response.noContent().build()
    }

    override fun listMyPasskeys(): Response =
        Response.ok(passkeyService.listMyPasskeys()).build()

    override fun createMyPasskeyOptions(): Response =
        Response.ok(passkeyService.registrationOptions(routingContext), MediaType.APPLICATION_JSON).build()

    override fun createMyPasskey(request: CreatePasskeyRequest): Response =
        Response.ok(passkeyService.createMyPasskey(request, routingContext)).build()

    override fun updateMyPasskey(passkeyId: Long, request: UpdatePasskeyRequest): Response =
        Response.ok(passkeyService.updateMyPasskey(passkeyId, request)).build()

    override fun deleteMyPasskey(passkeyId: Long): Response {
        passkeyService.deleteMyPasskey(passkeyId)
        return Response.noContent().build()
    }

    override fun forgotPassword(request: ForgotPasswordRequest): Response {
        userService.forgotPassword(request)
        return Response.noContent().build()
    }

    override fun resetMyPassword(userId: Long, request: ResetPasswordRequest): Response? {
        userService.resetMyPassword(userId, request)
        return Response.noContent().build()
    }

    override fun forgotPasskey(request: ForgotPasswordRequest): Response {
        userService.forgotPasskey(request, routingContext)
        return Response.noContent().build()
    }

    override fun createResetPasskeyOptions(userId: Long, request: PasskeyResetOptionsRequest): Response =
        Response.ok(userService.createResetPasskeyOptions(userId, request, routingContext), MediaType.APPLICATION_JSON).build()

    override fun resetMyPasskey(userId: Long, request: ResetPasskeyRequest): Response {
        val (response, accessCookie, refreshCookie) = userService.resetMyPasskey(userId, request, routingContext)
        return Response.ok(response)
            .cookie(accessCookie, refreshCookie)
            .build()
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

    override fun acceptInvitation(
        userId: Long,
        request: AcceptInvitationRequest,
    ): Response {
        val (response, accessCookie, refreshCookie) = userService.acceptInvitation(userId, request)

        return Response.ok(response)
            .cookie(accessCookie, refreshCookie)
            .build()
    }

    override fun createInvitationPasskeyOptions(
        userId: Long,
        request: PasskeyInvitationOptionsRequest,
    ): Response =
        Response.ok(
            userService.createInvitationPasskeyOptions(userId, request, routingContext),
            MediaType.APPLICATION_JSON,
        ).build()

    override fun acceptInvitationWithPasskey(
        userId: Long,
        request: AcceptPasskeyInvitationRequest,
    ): Response {
        val (response, accessCookie, refreshCookie) =
            userService.acceptInvitationWithPasskey(userId, request, routingContext)

        return Response.ok(response)
            .cookie(accessCookie, refreshCookie)
            .build()
    }
}
