package com.veyq.api.settings

import com.veyq.api.auth.filterchain.GroupOwnerRequired
import com.veyq.api.auth.filterchain.RequestContext
import com.veyq.api.auth.filterchain.ServerRolesRequired
import com.veyq.api.auth.filterchain.TenantOwnerRequired
import com.veyq.api.domain.ServerRole
import com.veyq.api.generated.api.ServerApi
import com.veyq.api.generated.model.CapabilityNotificationsResponse
import com.veyq.api.generated.model.ServerNotificationsResponse
import com.veyq.api.generated.model.ServerSettingsResponse
import com.veyq.api.generated.model.ServerUpdateEventsResponse
import com.veyq.api.generated.model.UpdaterUpdateCompletedRequest
import com.veyq.api.generated.model.UpdaterUpdateFailedRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders

@ApplicationScoped
class SettingsResource @Inject constructor(
    private val settingsService: SettingsService,
    private val serverOperationsService: ServerOperationsService,
    private val requestContext: RequestContext,
) : ServerApi {
    @Context
    lateinit var httpHeaders: HttpHeaders

    @ServerRolesRequired(ServerRole.ServerOwner)
    override fun getServerSettings(): ServerSettingsResponse =
        settingsService.serverSettings()

    @ServerRolesRequired(ServerRole.ServerOwner)
    override fun listServerUpdateEvents(): ServerUpdateEventsResponse =
        serverOperationsService.listUpdateEvents()

    @ServerRolesRequired(ServerRole.ServerOwner)
    override fun listServerNotifications(): ServerNotificationsResponse =
        serverOperationsService.listServerNotifications()

    @ServerRolesRequired(ServerRole.ServerOwner)
    override fun deleteServerNotification(notificationId: Long) =
        serverOperationsService.deleteServerNotification(notificationId)

    override fun listCapabilityNotifications(): CapabilityNotificationsResponse =
        serverOperationsService.listCapabilityNotifications(requestContext.user!!)

    override fun dismissCapabilityNotification(capabilityId: String) =
        serverOperationsService.dismissCapabilityNotification(requestContext.user!!, capabilityId)

    override fun reportUpdaterUpdateCompleted(updaterUpdateCompletedRequest: UpdaterUpdateCompletedRequest) =
        serverOperationsService.reportCompleted(httpHeaders.getHeaderString(HttpHeaders.AUTHORIZATION), updaterUpdateCompletedRequest)

    override fun reportUpdaterUpdateFailed(updaterUpdateFailedRequest: UpdaterUpdateFailedRequest) =
        serverOperationsService.reportFailed(httpHeaders.getHeaderString(HttpHeaders.AUTHORIZATION), updaterUpdateFailedRequest)
}
