package com.veyq.api.settings

import com.veyq.api.auth.filterchain.RequestContext
import com.veyq.api.generated.model.CapabilityNotificationsResponse
import com.veyq.api.generated.model.UpdaterUpdateCompletedRequest
import com.veyq.api.generated.model.UpdaterUpdateFailedRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType

@Path("/api")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
class SettingsResource @Inject constructor(
    private val serverOperationsService: ServerOperationsService,
    private val requestContext: RequestContext,
) {
    @Context
    lateinit var httpHeaders: HttpHeaders

    @GET
    @Path("/notifications/capabilities")
    fun listCapabilityNotifications(): CapabilityNotificationsResponse =
        serverOperationsService.listCapabilityNotifications(requestContext.user!!)

    @DELETE
    @Path("/notifications/capabilities/{capabilityId}")
    fun dismissCapabilityNotification(@PathParam("capabilityId") capabilityId: String) =
        serverOperationsService.dismissCapabilityNotification(requestContext.user!!, capabilityId)

    @POST
    @Path("/internal/updater/update-completed")
    @Consumes(MediaType.APPLICATION_JSON)
    fun reportUpdaterUpdateCompleted(updaterUpdateCompletedRequest: UpdaterUpdateCompletedRequest) =
        serverOperationsService.reportCompleted(httpHeaders.getHeaderString(HttpHeaders.AUTHORIZATION), updaterUpdateCompletedRequest)

    @POST
    @Path("/internal/updater/update-failed")
    @Consumes(MediaType.APPLICATION_JSON)
    fun reportUpdaterUpdateFailed(updaterUpdateFailedRequest: UpdaterUpdateFailedRequest) =
        serverOperationsService.reportFailed(httpHeaders.getHeaderString(HttpHeaders.AUTHORIZATION), updaterUpdateFailedRequest)
}
