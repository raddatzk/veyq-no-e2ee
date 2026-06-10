package com.veyq.api.memory

import com.veyq.api.generated.api.MemoryMediaApi
import com.veyq.api.generated.model.*
import com.veyq.api.library.media.MediaService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.core.MultivaluedHashMap
import jakarta.ws.rs.core.MultivaluedMap
import jakarta.ws.rs.core.Response
import org.jboss.resteasy.reactive.multipart.FileUpload
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.OffsetDateTime

@ApplicationScoped
class MemoryMediaResource @Inject constructor(
    private val mediaService: MediaService,
) : MemoryMediaApi {
    override fun listMemoryMedia(tenantName: String, memoryId: String, includeChildren: Boolean?): Response =
        Response.ok(mediaService.listMemoryMedia(memoryId, includeChildren ?: false)).build()

    override fun listMemoryMediaBuckets(tenantName: String, memoryId: String, includeChildren: Boolean?): Response =
        Response.ok(mediaService.listMemoryMediaBuckets(memoryId, includeChildren ?: false)).build()

    override fun waitForMemoryMediaUpdates(
        tenantName: String,
        memoryId: String,
        includeChildren: Boolean?,
        afterUpdatedAt: OffsetDateTime?,
        afterMediaId: String?,
        knownMediaSetHash: String?,
        timeoutSeconds: Int?,
    ): Response {
        val updates = mediaService.waitForMemoryMediaUpdates(
            memoryId = memoryId,
            includeChildren = includeChildren ?: false,
            afterUpdatedAt = afterUpdatedAt?.toInstant(),
            afterMediaId = afterMediaId,
            knownMediaSetHash = knownMediaSetHash,
            timeoutSeconds = (timeoutSeconds ?: 25).toLong(),
        )
        return Response.ok(
            MemoryMediaUpdatesResponse(
                media = updates.media,
                currentMediaIds = updates.currentMediaIds,
            )
        ).build()
    }

    override fun createMemoryMediaBucket(
        tenantName: String,
        memoryId: String,
        createMemoryMediaBucketRequest: CreateMemoryMediaBucketRequest,
    ): Response =
        Response.ok(mediaService.createMemoryMediaBucket(memoryId, createMemoryMediaBucketRequest)).build()

    override fun updateMemoryMediaBucket(
        tenantName: String,
        memoryId: String,
        bucketId: String,
        updateMemoryMediaBucketRequest: UpdateMemoryMediaBucketRequest,
    ): Response =
        Response.ok(mediaService.updateMemoryMediaBucket(memoryId, bucketId, updateMemoryMediaBucketRequest)).build()

    override fun deleteMemoryMediaBucket(tenantName: String, memoryId: String, bucketId: String): Response {
        mediaService.deleteMemoryMediaBucket(memoryId, bucketId)
        return Response.noContent().build()
    }

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

    override fun updateMemoryMediaCapturedAt(
        tenantName: String,
        memoryId: String,
        mediaId: String,
        request: UpdateMemoryMediaCapturedAtRequest,
    ): Response =
        Response.ok(mediaService.updateMemoryMediaCapturedAt(memoryId, mediaId, request)).build()

    override fun updateMemoryMediaHighlight(
        tenantName: String,
        memoryId: String,
        mediaId: String,
        request: UpdateMemoryMediaHighlightRequest,
    ): Response =
        Response.ok(mediaService.updateMemoryMediaHighlight(memoryId, mediaId, request)).build()

    override fun updateMemoryMediaMetadataBatch(
        tenantName: String,
        memoryId: String,
        request: UpdateMemoryMediaMetadataBatchRequest,
    ): Response =
        Response.ok(mediaService.updateMemoryMediaMetadataBatch(memoryId, request)).build()

    override fun deleteMemoryMedia(tenantName: String, memoryId: String, mediaId: String): Response {
        mediaService.deleteMemoryMedia(memoryId, mediaId)
        return Response.noContent().build()
    }

    override fun deleteAllMemoryMedia(tenantName: String, memoryId: String): Response {
        mediaService.deleteAllMemoryMedia(memoryId)
        return Response.noContent().build()
    }

    override fun moveMemoryMedia(
        tenantName: String,
        memoryId: String,
        moveMemoryMediaRequest: MoveMemoryMediaRequest,
    ): Response =
        Response.ok(mediaService.moveMemoryMedia(memoryId, moveMemoryMediaRequest)).build()

    override fun deleteMemoryMediaBatch(
        tenantName: String,
        memoryId: String,
        deleteMemoryMediaBatchRequest: DeleteMemoryMediaBatchRequest,
    ): Response {
        mediaService.deleteMemoryMediaBatch(memoryId, deleteMemoryMediaBatchRequest)
        return Response.noContent().build()
    }

    override fun memoryMediaContent(tenantName: String, mediaId: String, range: String?): Response =
        mediaService.mediaContent(mediaId, range)

    override fun memoryMediaThumbnail(tenantName: String, mediaId: String): Response =
        mediaService.mediaThumbnail(mediaId)

    override fun memoryMediaPreview(tenantName: String, mediaId: String): Response =
        mediaService.mediaPreview(mediaId)

    override fun memoryMediaPoster(tenantName: String, mediaId: String): Response =
        mediaService.mediaPoster(mediaId)

    override fun createMemoryMediaUpload(
        tenantName: String,
        memoryId: String,
        createMemoryMediaUploadRequest: CreateMemoryMediaUploadRequest,
    ): Response =
        Response.ok(mediaService.createMemoryMediaUpload(memoryId, createMemoryMediaUploadRequest)).build()

    override fun getMemoryMediaUpload(tenantName: String, memoryId: String, uploadId: String): Response =
        Response.ok(mediaService.memoryMediaUploadStatus(memoryId, uploadId)).build()

    override fun uploadMemoryMediaChunk(
        tenantName: String,
        memoryId: String,
        uploadId: String,
        chunkIndex: Int,
        body: File,
    ): Response {
        mediaService.uploadMemoryMediaChunk(memoryId, uploadId, chunkIndex, body.toPath())
        return Response.noContent().build()
    }

    override fun completeMemoryMediaUpload(tenantName: String, memoryId: String, uploadId: String): Response =
        Response.ok(mediaService.completeMemoryMediaUpload(memoryId, uploadId)).build()

    override fun abortMemoryMediaUpload(tenantName: String, memoryId: String, uploadId: String): Response {
        mediaService.abortMemoryMediaUpload(memoryId, uploadId)
        return Response.noContent().build()
    }

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
