package com.lunva.api.library.media

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.StreamingOutput
import java.time.Instant

data class StreamMediaSummary(
    val id: String,
    val title: String,
    val albumPath: String?,
    val ownerUserId: Long,
    val createdAt: Instant,
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
}