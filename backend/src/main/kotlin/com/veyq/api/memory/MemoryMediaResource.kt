package com.veyq.api.memory

import com.veyq.api.generated.api.MemoryMediaApi
import com.veyq.api.generated.model.UpdateMemoryMediaMetadataRequest
import com.veyq.api.generated.model.UpdateMemoryMediaMetadataBatchRequest
import com.veyq.api.library.media.MediaService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.core.MultivaluedHashMap
import jakarta.ws.rs.core.MultivaluedMap
import jakarta.ws.rs.core.Response
import org.jboss.resteasy.reactive.multipart.FileUpload
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@ApplicationScoped
class MemoryMediaResource @Inject constructor(
    private val mediaService: MediaService,
) : MemoryMediaApi {
    override fun listMemoryMedia(tenantName: String, memoryId: String, includeChildren: Boolean?): Response =
        Response.ok(mediaService.listMemoryMedia(memoryId, includeChildren ?: false)).build()

    override fun downloadMemoryMedia(tenantName: String, memoryId: String, includeChildren: Boolean?, locale: String?): Response =
        mediaService.downloadMemoryMedia(memoryId, includeChildren ?: false, locale)

    override fun uploadMemoryMedia(
        tenantName: String,
        memoryId: String,
        fileInputStream: InputStream,
        title: String?,
    ): Response {
        val temp = Files.createTempFile("veyq-memory-upload-", ".tmp")
        return try {
            fileInputStream.use { input -> Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING) }
            Response.ok(mediaService.uploadMemoryMedia(memoryId, GeneratedFileUpload(temp, title), title)).build()
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    override fun updateMemoryMediaMetadata(
        tenantName: String,
        memoryId: String,
        mediaId: String,
        request: UpdateMemoryMediaMetadataRequest,
    ): Response =
        Response.ok(mediaService.updateMemoryMediaMetadata(memoryId, mediaId, request)).build()

    override fun updateMemoryMediaMetadataBatch(
        tenantName: String,
        memoryId: String,
        request: UpdateMemoryMediaMetadataBatchRequest,
    ): Response =
        Response.ok(mediaService.updateMemoryMediaMetadataBatch(memoryId, request)).build()

    override fun memoryMediaContent(tenantName: String, mediaId: String, range: String?): Response =
        mediaService.mediaContent(mediaId, range)

    override fun memoryMediaPreview(tenantName: String, mediaId: String): Response =
        mediaService.mediaPreview(mediaId)

    private class GeneratedFileUpload(
        private val path: Path,
        private val title: String?,
    ) : FileUpload {
        override fun name(): String = "file"
        override fun filePath(): Path = path
        override fun fileName(): String = title?.trim()?.takeIf { it.isNotBlank() } ?: "Upload"
        override fun size(): Long = Files.size(path)
        override fun contentType(): String? = null

        override fun charSet(): String? = null
        override fun getHeaders(): MultivaluedMap<String, String> = MultivaluedHashMap()
    }
}
