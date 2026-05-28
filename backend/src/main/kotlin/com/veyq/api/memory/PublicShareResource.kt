package com.veyq.api.memory

import com.veyq.api.generated.api.PublicSharesApi
import com.veyq.api.generated.model.CreateMemoryMediaUploadRequest
import com.veyq.api.generated.model.CreateMemoryMediaBucketRequest
import com.veyq.api.generated.model.CreateMemoryPlaylistRequest
import com.veyq.api.generated.model.CreatePublicShareMemoryRequest
import com.veyq.api.generated.model.DeleteMemoryMediaBatchRequest
import com.veyq.api.generated.model.MemoryTrackActivityType
import com.veyq.api.generated.model.MoveMemoryMediaRequest
import com.veyq.api.generated.model.PublicShareLoginRequest
import com.veyq.api.generated.model.UpdateMemoryJournalRequest
import com.veyq.api.generated.model.UpdateMemoryMediaBucketRequest
import com.veyq.api.generated.model.UpdateMemoryMediaMetadataRequest
import com.veyq.api.generated.model.UpdateMemoryPlaylistRequest
import com.veyq.api.generated.model.UpdateMemoryTrackRequest
import com.veyq.api.generated.model.UpdatePublicShareMemoryRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.core.Response
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@ApplicationScoped
class PublicShareResource @Inject constructor(
    private val memoryShareService: MemoryShareService,
) : PublicSharesApi {
    override fun getPublicShare(shareId: String): Response =
        Response.ok(memoryShareService.getPublicShare(shareId)).build()

    override fun loginPublicShare(shareId: String, publicShareLoginRequest: PublicShareLoginRequest): Response {
        val response = memoryShareService.loginPublicShare(shareId, publicShareLoginRequest)
        return Response.ok(response.summary).cookie(response.accessCookie, response.refreshCookie).build()
    }

    override fun refreshPublicShare(shareId: String): Response {
        val response = memoryShareService.refreshPublicShare(shareId)
        return Response.ok(response.summary).cookie(response.accessCookie, response.refreshCookie).build()
    }

    override fun logoutPublicShare(shareId: String): Response =
        memoryShareService.logoutPublicShare(shareId).let { (accessCookie, refreshCookie) ->
            Response.noContent().cookie(accessCookie, refreshCookie).build()
        }

    override fun getPublicShareTree(shareId: String): Response =
        Response.ok(memoryShareService.getPublicShareTree(shareId)).build()

    override fun getPublicShareMemoryByPath(shareId: String, path: String): Response =
        Response.ok(memoryShareService.getPublicShareMemoryByPath(shareId, path)).build()

    override fun getPublicShareMemoryHeader(shareId: String, memoryId: String): Response =
        Response.ok(memoryShareService.getPublicShareMemoryHeader(shareId, memoryId)).build()

    override fun getPublicShareMemoryJournal(shareId: String, memoryId: String): Response =
        Response.ok(memoryShareService.getPublicShareMemoryJournal(shareId, memoryId)).build()

    override fun updatePublicShareMemoryJournal(
        shareId: String,
        memoryId: String,
        updateMemoryJournalRequest: UpdateMemoryJournalRequest,
    ): Response =
        Response.ok(memoryShareService.updatePublicShareMemoryJournal(shareId, memoryId, updateMemoryJournalRequest)).build()

    override fun listPublicShareChildMemories(shareId: String, memoryId: String): Response =
        Response.ok(memoryShareService.listPublicShareChildMemories(shareId, memoryId)).build()

    override fun createPublicShareChildMemory(
        shareId: String,
        memoryId: String,
        createPublicShareMemoryRequest: CreatePublicShareMemoryRequest,
    ): Response =
        Response.ok(memoryShareService.createPublicShareChildMemory(shareId, memoryId, createPublicShareMemoryRequest)).build()

    override fun updatePublicShareMemory(
        shareId: String,
        memoryId: String,
        updatePublicShareMemoryRequest: UpdatePublicShareMemoryRequest,
    ): Response =
        Response.ok(memoryShareService.updatePublicShareMemory(shareId, memoryId, updatePublicShareMemoryRequest)).build()

    override fun deletePublicShareMemory(shareId: String, memoryId: String): Response =
        memoryShareService.deletePublicShareMemory(shareId, memoryId).let { Response.noContent().build() }

    override fun listPublicShareMemoryMedia(shareId: String, memoryId: String, includeChildren: Boolean?): Response =
        Response.ok(memoryShareService.listPublicShareMemoryMedia(shareId, memoryId, includeChildren ?: false)).build()

    override fun listPublicShareMemoryMediaBuckets(shareId: String, memoryId: String, includeChildren: Boolean?): Response =
        Response.ok(memoryShareService.listPublicShareMemoryMediaBuckets(shareId, memoryId, includeChildren ?: false)).build()

    override fun createPublicShareMemoryMediaBucket(
        shareId: String,
        memoryId: String,
        createMemoryMediaBucketRequest: CreateMemoryMediaBucketRequest,
    ): Response =
        Response.ok(memoryShareService.createPublicShareMemoryMediaBucket(shareId, memoryId, createMemoryMediaBucketRequest)).build()

    override fun updatePublicShareMemoryMediaBucket(
        shareId: String,
        memoryId: String,
        bucketId: String,
        updateMemoryMediaBucketRequest: UpdateMemoryMediaBucketRequest,
    ): Response =
        Response.ok(memoryShareService.updatePublicShareMemoryMediaBucket(shareId, memoryId, bucketId, updateMemoryMediaBucketRequest)).build()

    override fun deletePublicShareMemoryMediaBucket(shareId: String, memoryId: String, bucketId: String): Response {
        memoryShareService.deletePublicShareMemoryMediaBucket(shareId, memoryId, bucketId)
        return Response.noContent().build()
    }

    override fun downloadPublicShareMemoryMedia(shareId: String, memoryId: String, includeChildren: Boolean?, locale: String?): Response =
        memoryShareService.downloadPublicShareMemoryMedia(shareId, memoryId, includeChildren ?: false, locale)

    override fun listPublicShareMemoryTracks(shareId: String, memoryId: String): Response =
        Response.ok(memoryShareService.listPublicShareMemoryTracks(shareId, memoryId)).build()

    override fun uploadPublicShareMemoryTrack(
        shareId: String,
        memoryId: String,
        fileInputStream: InputStream,
        title: String?,
        activityType: MemoryTrackActivityType?,
    ): Response {
        val temp = Files.createTempFile("veyq-public-share-track-", ".gpx")
        return try {
            fileInputStream.use { input -> Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING) }
            Response.ok(
                memoryShareService.uploadPublicShareMemoryTrack(
                    shareId = shareId,
                    memoryId = memoryId,
                    uploadPath = temp,
                    originalFilename = null,
                    title = title,
                    activityType = activityType,
                )
            ).build()
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    override fun updatePublicShareMemoryTrack(
        shareId: String,
        memoryId: String,
        trackId: String,
        updateMemoryTrackRequest: UpdateMemoryTrackRequest,
    ): Response =
        Response.ok(memoryShareService.updatePublicShareMemoryTrack(shareId, memoryId, trackId, updateMemoryTrackRequest)).build()

    override fun deletePublicShareMemoryTrack(shareId: String, memoryId: String, trackId: String): Response =
        memoryShareService.deletePublicShareMemoryTrack(shareId, memoryId, trackId).let { Response.noContent().build() }

    override fun publicShareMemoryTrackContent(shareId: String, memoryId: String, trackId: String): Response =
        memoryShareService.publicShareMemoryTrackContent(shareId, memoryId, trackId)

    override fun listPublicShareMemoryPlaylists(shareId: String, memoryId: String): Response =
        Response.ok(memoryShareService.listPublicShareMemoryPlaylists(shareId, memoryId)).build()

    override fun createPublicShareMemoryPlaylist(
        shareId: String,
        memoryId: String,
        createMemoryPlaylistRequest: CreateMemoryPlaylistRequest,
    ): Response =
        Response.ok(memoryShareService.createPublicShareMemoryPlaylist(shareId, memoryId, createMemoryPlaylistRequest)).build()

    override fun updatePublicShareMemoryPlaylist(
        shareId: String,
        memoryId: String,
        playlistId: String,
        updateMemoryPlaylistRequest: UpdateMemoryPlaylistRequest,
    ): Response =
        Response.ok(memoryShareService.updatePublicShareMemoryPlaylist(shareId, memoryId, playlistId, updateMemoryPlaylistRequest)).build()

    override fun deletePublicShareMemoryPlaylist(shareId: String, memoryId: String, playlistId: String): Response =
        memoryShareService.deletePublicShareMemoryPlaylist(shareId, memoryId, playlistId).let { Response.noContent().build() }

    override fun listPublicShareMedia(shareId: String): Response =
        Response.ok(memoryShareService.listPublicShareMedia(shareId)).build()

    override fun uploadPublicShareMedia(
        shareId: String,
        fileInputStream: InputStream,
        title: String?,
    ): Response {
        val temp = Files.createTempFile("veyq-public-share-upload-", ".tmp")
        return try {
            fileInputStream.use { input -> Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING) }
            Response.ok(
                memoryShareService.uploadPublicShareMedia(
                    shareId = shareId,
                    uploadPath = temp,
                    originalFilename = title?.trim()?.takeIf { it.isNotBlank() } ?: "Upload",
                    title = title,
                )
            ).build()
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    override fun uploadPublicShareMemoryMedia(
        shareId: String,
        memoryId: String,
        fileInputStream: InputStream,
        title: String?,
    ): Response {
        val temp = Files.createTempFile("veyq-public-share-upload-", ".tmp")
        return try {
            fileInputStream.use { input -> Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING) }
            Response.ok(
                memoryShareService.uploadPublicShareMemoryMedia(
                    shareId = shareId,
                    memoryId = memoryId,
                    uploadPath = temp,
                    originalFilename = title?.trim()?.takeIf { it.isNotBlank() } ?: "Upload",
                    title = title,
                )
            ).build()
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    override fun setPublicShareMemoryCover(
        shareId: String,
        memoryId: String,
        fileInputStream: InputStream?,
        mediaId: String?,
        cropX: Double?,
        cropY: Double?,
        cropWidth: Double?,
        cropHeight: Double?,
    ): Response {
        val temp = fileInputStream?.let { Files.createTempFile("veyq-public-share-cover-", ".tmp") }
        return try {
            if (temp != null) {
                fileInputStream.use { input -> Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING) }
            }
            Response.ok(
                memoryShareService.setPublicShareMemoryCover(
                    shareId = shareId,
                    memoryId = memoryId,
                    uploadPath = temp,
                    originalFilename = "cover",
                    mediaId = mediaId,
                    cropX = cropX,
                    cropY = cropY,
                    cropWidth = cropWidth,
                    cropHeight = cropHeight,
                )
            ).build()
        } finally {
            temp?.let { Files.deleteIfExists(it) }
        }
    }

    override fun removePublicShareMemoryCover(shareId: String, memoryId: String): Response =
        Response.ok(memoryShareService.removePublicShareMemoryCover(shareId, memoryId)).build()

    override fun publicShareMemoryCoverContent(shareId: String, memoryId: String): Response =
        memoryShareService.publicShareMemoryCoverContent(shareId, memoryId)

    override fun updatePublicShareMediaMetadata(
        shareId: String,
        memoryId: String,
        mediaId: String,
        updateMemoryMediaMetadataRequest: UpdateMemoryMediaMetadataRequest,
    ): Response =
        Response.ok(memoryShareService.updatePublicShareMediaMetadata(shareId, memoryId, mediaId, updateMemoryMediaMetadataRequest)).build()

    override fun deletePublicShareMedia(shareId: String, memoryId: String, mediaId: String): Response =
        memoryShareService.deletePublicShareMedia(shareId, memoryId, mediaId).let { Response.noContent().build() }

    override fun movePublicShareMemoryMedia(
        shareId: String,
        memoryId: String,
        moveMemoryMediaRequest: MoveMemoryMediaRequest,
    ): Response =
        Response.ok(memoryShareService.movePublicShareMemoryMedia(shareId, memoryId, moveMemoryMediaRequest)).build()

    override fun deletePublicShareMemoryMediaBatch(
        shareId: String,
        memoryId: String,
        deleteMemoryMediaBatchRequest: DeleteMemoryMediaBatchRequest,
    ): Response =
        memoryShareService.deletePublicShareMemoryMediaBatch(shareId, memoryId, deleteMemoryMediaBatchRequest).let {
            Response.noContent().build()
        }

    override fun publicShareMediaContent(shareId: String, mediaId: String, range: String?): Response =
        memoryShareService.publicShareMediaContent(shareId, mediaId, range)

    override fun publicShareMediaThumbnail(shareId: String, mediaId: String): Response =
        memoryShareService.publicShareMediaThumbnail(shareId, mediaId)

    override fun publicShareMediaPreview(shareId: String, mediaId: String): Response =
        memoryShareService.publicShareMediaPreview(shareId, mediaId)

    override fun publicShareMediaPoster(shareId: String, mediaId: String): Response =
        memoryShareService.publicShareMediaPoster(shareId, mediaId)

    override fun createPublicShareMediaUpload(
        shareId: String,
        createMemoryMediaUploadRequest: CreateMemoryMediaUploadRequest,
    ): Response =
        Response.ok(memoryShareService.createPublicShareMediaUpload(shareId, createMemoryMediaUploadRequest)).build()

    override fun createPublicShareMemoryMediaUpload(
        shareId: String,
        memoryId: String,
        createMemoryMediaUploadRequest: CreateMemoryMediaUploadRequest,
    ): Response =
        Response.ok(memoryShareService.createPublicShareMemoryMediaUpload(shareId, memoryId, createMemoryMediaUploadRequest)).build()

    override fun getPublicShareMediaUpload(shareId: String, uploadId: String): Response =
        Response.ok(memoryShareService.publicShareMediaUploadStatus(shareId, uploadId)).build()

    override fun getPublicShareMemoryMediaUpload(shareId: String, memoryId: String, uploadId: String): Response =
        Response.ok(memoryShareService.publicShareMemoryMediaUploadStatus(shareId, memoryId, uploadId)).build()

    override fun uploadPublicShareMediaChunk(
        shareId: String,
        uploadId: String,
        chunkIndex: Int,
        body: File,
    ): Response {
        memoryShareService.uploadPublicShareMediaChunk(shareId, uploadId, chunkIndex, body.toPath())
        return Response.noContent().build()
    }

    override fun uploadPublicShareMemoryMediaChunk(
        shareId: String,
        memoryId: String,
        uploadId: String,
        chunkIndex: Int,
        body: File,
    ): Response {
        memoryShareService.uploadPublicShareMemoryMediaChunk(shareId, memoryId, uploadId, chunkIndex, body.toPath())
        return Response.noContent().build()
    }

    override fun completePublicShareMediaUpload(shareId: String, uploadId: String): Response =
        Response.ok(memoryShareService.completePublicShareMediaUpload(shareId, uploadId)).build()

    override fun completePublicShareMemoryMediaUpload(shareId: String, memoryId: String, uploadId: String): Response =
        Response.ok(memoryShareService.completePublicShareMemoryMediaUpload(shareId, memoryId, uploadId)).build()

    override fun abortPublicShareMediaUpload(shareId: String, uploadId: String): Response {
        memoryShareService.abortPublicShareMediaUpload(shareId, uploadId)
        return Response.noContent().build()
    }

    override fun abortPublicShareMemoryMediaUpload(shareId: String, memoryId: String, uploadId: String): Response {
        memoryShareService.abortPublicShareMemoryMediaUpload(shareId, memoryId, uploadId)
        return Response.noContent().build()
    }
}
