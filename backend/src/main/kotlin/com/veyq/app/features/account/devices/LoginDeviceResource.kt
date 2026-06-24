package com.veyq.app.features.account.devices

import com.veyq.app.platform.error.ApiException
import com.veyq.app.platform.error.unauthorized
import com.veyq.app.shared.security.Session
import com.veyq.app.shared.security.SessionService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import jakarta.validation.constraints.Size
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

data class RenameLoginDeviceRequest(
    @field:Size(max = 16384) val encryptedName: String = "",
)

data class LoginDeviceSummary(
    val deviceRef: String,
    val encryptedName: String,
    val lastLoginAt: String,
)

data class LoginDeviceList(val devices: List<LoginDeviceSummary>)

@ApplicationScoped
@Path("/api/me/devices")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class LoginDeviceResource(private val sessions: SessionService) {
    @GET
    @Transactional
    fun listDevices(@HeaderParam("Authorization") authorization: String?): LoginDeviceList {
        val userId = sessions.resolve(authorization) ?: throw unauthorized()
        val latestByDevice = sessions.listDevices(userId)
            .groupBy { it.deviceRef!! }
            .values
            .map { sessionsForDevice -> sessionsForDevice.maxBy { it.createdAt } }
            .sortedByDescending { it.createdAt }
            .map(::toSummary)
        return LoginDeviceList(latestByDevice)
    }

    @PUT
    @Path("/{deviceRef}")
    @Transactional
    fun renameDevice(
        @HeaderParam("Authorization") authorization: String?,
        @PathParam("deviceRef") deviceRef: String,
        request: RenameLoginDeviceRequest,
    ): Response {
        val userId = sessions.resolve(authorization) ?: throw unauthorized()
        val encryptedName = request.encryptedName.trim()
        if (encryptedName.isBlank()) throw ApiException(400, "DEVICE_NAME_REQUIRED", "Encrypted device name is required")
        sessions.renameDevice(userId, deviceRef, encryptedName)
        return Response.noContent().build()
    }

    @DELETE
    @Path("/{deviceRef}")
    @Transactional
    fun removeDevice(
        @HeaderParam("Authorization") authorization: String?,
        @PathParam("deviceRef") deviceRef: String,
    ): Response {
        val userId = sessions.resolve(authorization) ?: throw unauthorized()
        sessions.invalidateDevice(userId, deviceRef)
        return Response.noContent().build()
    }

    private fun toSummary(session: Session): LoginDeviceSummary =
        LoginDeviceSummary(
            deviceRef = session.deviceRef!!,
            encryptedName = session.deviceName ?: "",
            lastLoginAt = session.createdAt.toString(),
        )
}
