package com.lunva.api.library.media

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.io.InputStream

@Path("/api/media/uploads")
@ApplicationScoped
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
class MediaUploadResource @Inject constructor(
    private val mediaService: MediaService,
) {

    @POST
    fun createUpload(request: CreateMediaUploadRequest): MediaUploadSessionSummary =
        mediaService.createUpload(request)

    @GET
    fun listUploads(): List<PendingMediaUploadSummary> =
        mediaService.listUploads()

    @GET
    @Path("/{uploadId}")
    fun getUpload(@PathParam("uploadId") uploadId: String): PendingMediaUploadSummary =
        mediaService.uploadStatus(uploadId)

    @PUT
    @Path("/{uploadId}/chunks/{chunkIndex}")
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    fun uploadChunk(
        @PathParam("uploadId") uploadId: String,
        @PathParam("chunkIndex") chunkIndex: Int,
        chunk: InputStream,
    ): Response {
        mediaService.uploadChunk(uploadId, chunkIndex, chunk)
        return Response.noContent().build()
    }

    @POST
    @Path("/{uploadId}/complete")
    fun completeUpload(@PathParam("uploadId") uploadId: String): UploadMediaSummary =
        mediaService.completeUpload(uploadId)

    @DELETE
    @Path("/{uploadId}")
    fun abortUpload(@PathParam("uploadId") uploadId: String): Response {
        mediaService.abortUpload(uploadId)
        return Response.noContent().build()
    }
}
