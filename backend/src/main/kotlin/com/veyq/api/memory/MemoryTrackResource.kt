package com.veyq.api.memory

import com.veyq.api.generated.api.MemoryTracksApi
import com.veyq.api.generated.model.MemoryTrackActivityType
import com.veyq.api.generated.model.UpdateMemoryTrackRequest
import com.veyq.api.library.track.MemoryTrackService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.core.Response
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@ApplicationScoped
class MemoryTrackResource @Inject constructor(
    private val trackService: MemoryTrackService,
) : MemoryTracksApi {
    override fun listMemoryTracks(tenantName: String, memoryId: String): Response =
        Response.ok(trackService.listMemoryTracks(memoryId)).build()

    override fun uploadMemoryTrack(
        tenantName: String,
        memoryId: String,
        fileInputStream: InputStream,
        title: String?,
        activityType: MemoryTrackActivityType?,
    ): Response {
        val temp = Files.createTempFile("veyq-memory-track-", ".gpx")
        return try {
            fileInputStream.use { input -> Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING) }
            Response.ok(trackService.uploadMemoryTrack(memoryId, temp, null, title, activityType)).build()
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    override fun updateMemoryTrack(
        tenantName: String,
        memoryId: String,
        trackId: String,
        updateMemoryTrackRequest: UpdateMemoryTrackRequest,
    ): Response =
        Response.ok(trackService.updateMemoryTrack(memoryId, trackId, updateMemoryTrackRequest)).build()

    override fun deleteMemoryTrack(tenantName: String, memoryId: String, trackId: String): Response {
        trackService.deleteMemoryTrack(memoryId, trackId)
        return Response.noContent().build()
    }

    override fun memoryTrackContent(tenantName: String, memoryId: String, trackId: String): Response =
        trackService.memoryTrackContent(memoryId, trackId)
}
