package com.lunva.api.media

import com.lunva.api.auth.BadRequestException
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

data class ShareUserRequest(val userId: Long?)
data class ShareGroupRequest(val groupId: Long?)

@Path("/api/{tenantName}/media")
@ApplicationScoped
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
class MediaResource @Inject constructor(
    private val mediaService: MediaService,
) {

    @GET
    fun media(@PathParam("tenantName") tenantName: String): MediaSummary {
        return mediaService.listAccessibleMedia()
    }

//    @GET
//    @Path("/{mediaId}")
//    fun mediaDetail(
//        @PathParam("tenantName") tenantName: String,
//        @PathParam("mediaId") mediaId: Long,
//    ): MediaDetail =
//        mediaService.getAccessibleMedia(tenantName, mediaId)
//
//    @POST
//    @Path("/{mediaId}/shares/users")
//    fun shareWithUser(
//        @PathParam("tenantName") tenantName: String,
//        @PathParam("mediaId") mediaId: Long,
//        request: ShareUserRequest,
//    ) {
//        mediaService.shareWithUser(tenantName, mediaId, request.userId ?: throw BadRequestException("userId is required"))
//    }
//
//    @DELETE
//    @Path("/{mediaId}/shares/users/{userId}")
//    fun removeUserShare(
//        @PathParam("tenantName") tenantName: String,
//        @PathParam("mediaId") mediaId: Long,
//        @PathParam("userId") userId: Long,
//    ) {
//        mediaService.removeUserShare(tenantName, mediaId, userId)
//    }
//
//    @POST
//    @Path("/{mediaId}/shares/groups")
//    fun shareWithGroup(
//        @PathParam("tenantName") tenantName: String,
//        @PathParam("mediaId") mediaId: Long,
//        request: ShareGroupRequest,
//    ) {
//        mediaService.shareWithGroup(tenantName, mediaId, request.groupId ?: throw BadRequestException("groupId is required"))
//    }
//
//    @DELETE
//    @Path("/{mediaId}/shares/groups/{groupId}")
//    fun removeGroupShare(
//        @PathParam("tenantName") tenantName: String,
//        @PathParam("mediaId") mediaId: Long,
//        @PathParam("groupId") groupId: Long,
//    ) {
//        mediaService.removeGroupShare(tenantName, mediaId, groupId)
//    }
}
