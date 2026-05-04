package com.lunva.api.library.media

import com.fasterxml.jackson.databind.ObjectMapper
import com.lunva.api.generated.api.MediaApi
import com.lunva.api.generated.model.UpdateMediaMetadataRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.StreamingOutput
import jakarta.ws.rs.core.MultivaluedHashMap
import jakarta.ws.rs.core.MultivaluedMap
import org.jboss.resteasy.reactive.multipart.FileUpload
import java.io.InputStream
import java.net.URLConnection
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@ApplicationScoped
class MediaResource @Inject constructor(
    private val mediaService: MediaService,
    private val objectMapper: ObjectMapper,
) : MediaApi {

    override fun media(tenantName: String, path: String?): Response {
        val stream = StreamingOutput { output ->
            mediaService.streamMedia(path).forEach { media ->
                output.write(objectMapper.writeValueAsBytes(media))
                output.write('\n'.code)
                output.flush()
            }
        }

        return Response.ok(stream, "application/x-ndjson").build()
    }

    override fun upload(
        tenantName: String,
        fileInputStream: InputStream,
        albumPath: String?,
        title: String?,
    ): Response {
        val uploadPath = Files.createTempFile("lunva-upload-", ".tmp")
        return try {
            fileInputStream.use { input ->
                Files.copy(input, uploadPath, StandardCopyOption.REPLACE_EXISTING)
            }
            Response.ok(mediaService.uploadMedia(GeneratedFileUpload(uploadPath, title), albumPath, title)).build()
        } finally {
            Files.deleteIfExists(uploadPath)
        }
    }

    override fun content(tenantName: String, mediaId: String, range: String?): Response =
        mediaService.mediaContent(mediaId, range)

    override fun updateMetadata(
        tenantName: String,
        mediaId: String,
        request: UpdateMediaMetadataRequest,
    ): Response =
        Response.ok(mediaService.updateMetadata(mediaId, request)).build()

    private class GeneratedFileUpload(
        private val path: Path,
        private val title: String?,
    ) : FileUpload {
        override fun name(): String = "file"
        override fun filePath(): Path = path
        override fun fileName(): String = title?.trim()?.takeIf { it.isNotBlank() } ?: "Upload"
        override fun size(): Long = Files.size(path)
        override fun contentType(): String? =
            detectContentType(path)
                ?: Files.newInputStream(path).use { input -> URLConnection.guessContentTypeFromStream(input) }
                ?: Files.probeContentType(path)

        override fun charSet(): String? = null
        override fun getHeaders(): MultivaluedMap<String, String> = MultivaluedHashMap()

        private fun detectContentType(path: Path): String? {
            val header = ByteArray(16)
            val read = Files.newInputStream(path).use { input -> input.read(header) }
            if (read >= 3 &&
                header[0] == 0xFF.toByte() &&
                header[1] == 0xD8.toByte() &&
                header[2] == 0xFF.toByte()
            ) {
                return "image/jpeg"
            }
            if (read >= 8 &&
                header[4] == 'f'.code.toByte() &&
                header[5] == 't'.code.toByte() &&
                header[6] == 'y'.code.toByte() &&
                header[7] == 'p'.code.toByte()
            ) {
                return "video/mp4"
            }
            return null
        }
    }
}
