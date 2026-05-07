package com.lunva.api.library.media

import com.lunva.api.generated.api.MediaUploadsApi
import com.lunva.api.generated.model.CreateMediaUploadRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.core.Response
import java.io.File
import java.nio.file.Files

@ApplicationScoped
class MediaUploadResource @Inject constructor(
    private val mediaService: MediaService,
) : MediaUploadsApi {

    override fun createUpload(request: CreateMediaUploadRequest): Response =
        Response.ok(mediaService.createUpload(request)).build()

    override fun listUploads(): Response =
        Response.ok(mediaService.listUploads()).build()

    override fun getUpload(uploadId: String): Response =
        Response.ok(mediaService.uploadStatus(uploadId)).build()

    override fun uploadChunk(
        uploadId: String,
        chunkIndex: Int,
        body: File,
    ): Response {
        Files.newInputStream(body.toPath()).use { chunk ->
            mediaService.uploadChunk(uploadId, chunkIndex, chunk)
        }
        return Response.noContent().build()
    }

    override fun completeUpload(uploadId: String): Response =
        Response.ok(mediaService.completeUpload(uploadId)).build()

    override fun abortUpload(uploadId: String): Response {
        mediaService.abortUpload(uploadId)
        return Response.noContent().build()
    }
}
