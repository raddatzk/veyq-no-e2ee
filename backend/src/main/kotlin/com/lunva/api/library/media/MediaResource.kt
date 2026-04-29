package com.lunva.api.library.media

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.validation.constraints.NotBlank
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.StreamingOutput
import org.jboss.resteasy.reactive.RestForm
import org.jboss.resteasy.reactive.multipart.FileUpload
import java.time.Instant

data class StreamMediaSummary(
    val id: String,
    val title: String,
    val albumPath: String?,
    val ownerUserId: Long,
    val createdAt: Instant,
    val mimeType: String,
    val sizeBytes: Long,
    val contentUrl: String,
)

data class UploadMediaSummary(
    val id: String,
    val title: String,
    val albumPath: String,
    val ownerUserId: Long,
    val createdAt: Instant,
    val mimeType: String,
    val sizeBytes: Long,
    val contentUrl: String,
)

data class CreateMediaUploadRequest(
    val tenantName: String?,
    val albumPath: String?,
    val filename: String?,
    val mimeType: String?,
    val sizeBytes: Long?,
    val title: String?,
)

data class MediaUploadSessionSummary(
    val uploadId: String,
    val chunkSizeBytes: Long,
)

data class PendingMediaUploadSummary(
    val uploadId: String,
    val tenantName: String,
    val tenantDisplayName: String,
    val albumPath: String,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
    val title: String?,
    val chunkSizeBytes: Long,
    val uploadedChunks: List<Int>,
    val uploadedBytes: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Path("/api/{tenantName}/media")
@ApplicationScoped
@Consumes(MediaType.APPLICATION_JSON)
class MediaResource @Inject constructor(
    private val mediaService: MediaService,
    private val objectMapper: ObjectMapper,
) {

    @GET
    @Produces("application/x-ndjson")
    fun media(@QueryParam("path") path: String?): StreamingOutput =
        StreamingOutput { output ->
            mediaService.streamMedia(path).forEach { media ->
                output.write(objectMapper.writeValueAsBytes(media))
                output.write('\n'.code)
                output.flush()
            }
        }

    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    fun upload(
        @RestForm file: FileUpload?,
        @RestForm @NotBlank albumPath: String?,
        @RestForm title: String?,
    ): UploadMediaSummary =
        mediaService.uploadMedia(file, albumPath, title)

    @GET
    @Path("/{mediaId}/content")
    fun content(@PathParam("mediaId") mediaId: String, @HeaderParam("Range") range: String?): Response =
        mediaService.mediaContent(mediaId, range)
}
