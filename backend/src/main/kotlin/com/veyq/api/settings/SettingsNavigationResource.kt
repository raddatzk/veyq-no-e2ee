package com.veyq.api.settings

import com.veyq.api.auth.filterchain.ServerRolesRequired
import com.veyq.api.domain.ServerRole
import com.veyq.api.generated.model.SettingsResponse
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType

@Path("/api/settings")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
class SettingsNavigationResource @Inject constructor(
    private val settingsNavigationService: SettingsNavigationService,
    private val settingsService: SettingsService,
    private val serverOperationsService: ServerOperationsService,
) {

    @GET
    fun getSettings(@QueryParam("path") path: String): SettingsResponse =
        settingsNavigationService.settings(path)

    @GET
    @Path("/server")
    @ServerRolesRequired(ServerRole.ServerOwner)
    fun getServerSettings() =
        settingsService.serverSettings()

    @GET
    @Path("/server/update-events")
    @ServerRolesRequired(ServerRole.ServerOwner)
    fun listServerUpdateEvents() =
        serverOperationsService.listUpdateEvents()

    @GET
    @Path("/server/notifications")
    @ServerRolesRequired(ServerRole.ServerOwner)
    fun listServerNotifications() =
        serverOperationsService.listServerNotifications()

    @DELETE
    @Path("/server/notifications/{notificationId}")
    @ServerRolesRequired(ServerRole.ServerOwner)
    fun deleteServerNotification(@PathParam("notificationId") notificationId: Long) =
        serverOperationsService.deleteServerNotification(notificationId)
}
