package com.veyq.api.library.media

import com.veyq.api.auth.BadRequestException
import com.veyq.api.auth.NotFoundException
import com.veyq.api.auth.filterchain.RequestContext
import com.veyq.api.domain.Media
import com.veyq.api.domain.MediaRepo
import com.veyq.api.domain.Memory
import com.veyq.api.domain.MemoryRepo
import com.veyq.api.domain.TenantFolderRepo
import com.veyq.api.domain.TenantGroupMemberRepo
import com.veyq.api.domain.TenantGroupRepo
import com.veyq.api.generated.model.MemoryMediaSummary
import com.veyq.api.generated.model.UpdateMemoryMediaMetadataRequest
import com.veyq.api.library.LibraryScopeKey
import com.veyq.api.library.LibraryService
import jakarta.inject.Inject
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.StreamingOutput
import org.jboss.logging.Logger
import org.jboss.resteasy.reactive.multipart.FileUpload
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.IOException
import java.io.OutputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Comparator
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

@Singleton
class MediaService @Inject constructor(
    private val mediaRepo: MediaRepo,
    private val memoryRepo: MemoryRepo,
    private val mediaConfig: MediaConfig,
    private val mediaTypeDetector: MediaTypeDetector,
    private val mediaMetadataExtractor: MediaMetadataExtractor,
    folderRepo: TenantFolderRepo,
    groupRepo: TenantGroupRepo,
    private val mediaGroupMemberRepo: TenantGroupMemberRepo,
    requestContext: RequestContext
) : LibraryService(folderRepo, groupRepo, mediaGroupMemberRepo, requestContext) {
    private val log: Logger = Logger.getLogger(MediaService::class.java)

    fun listMemoryMedia(memoryId: String, includeChildren: Boolean): List<MemoryMediaSummary> {
        val memory = resolveVisibleMemory(memoryId)
        val memories = if (includeChildren) descendantsAndSelf(memory) else listOf(memory)
        return memories
            .filter(::isMemoryVisible)
            .flatMap { mediaRepo.listByMemory(it) }
            .distinctBy { it.id }
            .sortedWith(compareBy<Media> { it.capturedAt ?: it.createdAt }.thenBy { it.createdAt })
            .map { it.toMemorySummary() }
    }

    @Transactional
    fun uploadMemoryMedia(memoryId: String, file: FileUpload?, title: String?): MemoryMediaSummary {
        val memory = resolveVisibleMemory(memoryId)
        val upload = file ?: throw BadRequestException("file is required")
        val uploadPath = upload.uploadedFile()
        val size = Files.size(uploadPath)
        if (size <= 0) throw BadRequestException("file must not be empty")
        val mediaType = mediaTypeDetector.detect(uploadPath)
            ?: throw BadRequestException("Only JPEG, PNG, GIF, MP4 and QuickTime uploads are supported")

        val media = Media().also {
            it.tenant = tenant()
            it.owner = user()
            applyScope(it, memory.scope())
            it.memory = memory
            it.title = title?.trim()?.takeIf { value -> value.isNotBlank() }
                ?: upload.fileName()?.substringBeforeLast('.')?.takeIf { value -> value.isNotBlank() }
                ?: "Upload"
            it.mimeType = mediaType.mimeType
            it.sizeBytes = size
            it.storageKey = ""
            it.originalFilename = upload.fileName()
            mediaRepo.persist(it)
        }
        mediaRepo.flush()

        val storageKey = "tenant-${tenant().id}/${media.publicId}/original"
        writeMediaFile(uploadPath, storageKey)
        media.storageKey = storageKey
        mediaMetadataExtractor.apply(media, mediaMetadataExtractor.extract(storagePath(storageKey), media.mimeType))
        generatePreviewIfSupported(media)

        return media.toMemorySummary()
    }

    @Transactional
    fun updateMemoryMediaMetadata(memoryId: String, publicId: String, request: UpdateMemoryMediaMetadataRequest): MemoryMediaSummary {
        val memory = resolveVisibleMemory(memoryId)
        val media = mediaRepo.findByTenantAndPublicId(tenant(), publicId)
            ?: throw NotFoundException("Media not found")
        if (media.memory?.id != memory.id) throw NotFoundException("Media not found")

        media.capturedAt = request.capturedAt?.toInstant()
        media.capturedAtOverride = true
        return media.toMemorySummary()
    }

    fun mediaContent(publicId: String, rangeHeader: String?): Response {
        val media = resolveAccessibleMedia(publicId)
        val path = storagePath(media.storageKey)
        if (!Files.isRegularFile(path)) throw NotFoundException("Media file not found")
        val filename = media.originalFilename?.takeIf { it.isNotBlank() } ?: "${media.publicId}.bin"
        val size = Files.size(path)
        val range = MediaRanges.parse(rangeHeader, size)

        if (rangeHeader != null && range == null) {
            return Response.status(Response.Status.REQUESTED_RANGE_NOT_SATISFIABLE)
                .header("Accept-Ranges", "bytes")
                .header("Content-Range", "bytes */$size")
                .build()
        }

        if (range != null) {
            return Response.status(Response.Status.PARTIAL_CONTENT)
                .type(media.mimeType)
                .entity(streamRange(path, range))
                .header("Accept-Ranges", "bytes")
                .header("Content-Length", range.length)
                .header("Content-Range", "bytes ${range.start}-${range.end}/$size")
                .header("Content-Disposition", "inline; filename=\"${safeHeaderFilename(filename)}\"")
                .header("X-Content-Type-Options", "nosniff")
                .build()
        }

        return Response.ok(path.toFile(), media.mimeType)
            .header("Accept-Ranges", "bytes")
            .header("Content-Length", media.sizeBytes)
            .header("Content-Disposition", "inline; filename=\"${safeHeaderFilename(filename)}\"")
            .header("X-Content-Type-Options", "nosniff")
            .build()
    }

    fun mediaPreview(publicId: String): Response {
        val media = resolveAccessibleMedia(publicId)
        if (!canGeneratePreview(media)) throw NotFoundException("Media not found")

        val previewPath = previewPath(media)
        if (!Files.isRegularFile(previewPath)) {
            if (!generatePreview(media, throwOnFailure = false)) throw NotFoundException("Media preview not found")
        }

        return Response.ok(previewPath.toFile(), "image/jpeg")
            .header("Cache-Control", "private, max-age=31536000, immutable")
            .header("Content-Length", Files.size(previewPath))
            .header("Content-Disposition", "inline; filename=\"${safeHeaderFilename(media.publicId)}-preview.jpg\"")
            .header("X-Content-Type-Options", "nosniff")
            .build()
    }

    private fun resolveAccessibleMedia(publicId: String): Media {
        val media = mediaRepo.findByTenantAndPublicId(tenant(), publicId)
            ?: throw NotFoundException("Media not found")
        if (media.memory?.let(::isMemoryVisible) != true) throw NotFoundException("Media not found")
        return media
    }

    private fun resolveVisibleMemory(publicId: String): Memory {
        val memory = memoryRepo.findByTenantAndPublicId(tenant(), publicId)
            ?: throw NotFoundException("Memory not found")
        if (!isMemoryVisible(memory)) throw NotFoundException("Memory not found")
        return memory
    }

    private fun isMemoryVisible(memory: Memory): Boolean =
        memory.tenant.id == tenant().id && when (val scope = memory.scope()) {
            LibraryScopeKey.Tenant -> true
            is LibraryScopeKey.Group -> scope.group.tenant.id == tenant().id && mediaGroupMemberRepo.findByGroupAndUser(scope.group, user()) != null
            is LibraryScopeKey.User -> scope.user.id == user().id
        }

    private fun descendantsAndSelf(root: Memory): List<Memory> =
        listOf(root) + memoryRepo.list("tenant", tenant())
            .filter { candidate -> generateSequence(candidate.parentMemory) { it.parentMemory }.any { it.id == root.id } }

    private fun writeMediaFile(source: Path, storageKey: String) {
        val target = storagePath(storageKey)
        Files.createDirectories(target.parent)
        val temp = Files.createTempFile(target.parent, "upload-", ".tmp")
        try {
            Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING)
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Files.deleteIfExists(temp)
            throw e
        }
    }

    private fun generatePreviewIfSupported(media: Media) {
        if (!canGeneratePreview(media)) return
        generatePreview(media, throwOnFailure = false)
    }

    private fun generatePreview(media: Media, throwOnFailure: Boolean): Boolean {
        val originalPath = storagePath(media.storageKey)
        if (!Files.isRegularFile(originalPath)) return false
        return when {
            media.mimeType.startsWith("image/") -> generateImagePreview(media, originalPath, throwOnFailure)
            media.mimeType.startsWith("video/") -> generateVideoPreview(media, originalPath, throwOnFailure)
            else -> false
        }
    }

    private fun generateImagePreview(media: Media, originalPath: Path, throwOnFailure: Boolean): Boolean {
        val previewPath = previewPath(media)
        Files.createDirectories(previewPath.parent)
        val temp = Files.createTempFile(previewPath.parent, "preview-", ".jpg")
        try {
            val source = ImageIO.read(originalPath.toFile()) ?: return false
            val scale = minOf(1.0, PREVIEW_MAX_EDGE.toDouble() / maxOf(source.width, source.height).toDouble())
            val targetWidth = maxOf(1, (source.width * scale).toInt())
            val targetHeight = maxOf(1, (source.height * scale).toInt())
            val preview = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB)
            val graphics = preview.createGraphics()
            try {
                graphics.color = Color.WHITE
                graphics.fillRect(0, 0, targetWidth, targetHeight)
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null)
            } finally {
                graphics.dispose()
            }
            if (!ImageIO.write(preview, "jpg", temp.toFile())) return false
            Files.move(temp, previewPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            return true
        } catch (e: Exception) {
            if (throwOnFailure) throw e
            log.warn("Failed to generate media preview for ${media.publicId}: ${e.message}")
            return false
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun generateVideoPreview(media: Media, originalPath: Path, throwOnFailure: Boolean): Boolean {
        val previewPath = previewPath(media)
        Files.createDirectories(previewPath.parent)
        val temp = Files.createTempFile(previewPath.parent, "preview-", ".jpg")
        val logFile = Files.createTempFile(previewPath.parent, "preview-", ".log")
        try {
            val process = ProcessBuilder(
                mediaConfig.ffmpegPath,
                "-hide_banner",
                "-loglevel",
                "error",
                "-y",
                "-ss",
                "00:00:01",
                "-i",
                originalPath.toString(),
                "-frames:v",
                "1",
                "-vf",
                "scale=720:720:force_original_aspect_ratio=decrease",
                "-q:v",
                "3",
                temp.toString(),
            )
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()))
                .start()

            val completed = process.waitFor(mediaConfig.previewTimeoutSeconds, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                throw IOException("ffmpeg timed out after ${mediaConfig.previewTimeoutSeconds}s")
            }
            if (process.exitValue() != 0) {
                val message = Files.readString(logFile).trim().take(500)
                throw IOException("ffmpeg exited with ${process.exitValue()}: $message")
            }
            if (!Files.isRegularFile(temp) || Files.size(temp) <= 0) return false
            Files.move(temp, previewPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            return true
        } catch (e: Exception) {
            if (throwOnFailure) throw e
            log.warn("Failed to generate media preview for ${media.publicId}: ${e.message}")
            return false
        } finally {
            Files.deleteIfExists(temp)
            Files.deleteIfExists(logFile)
        }
    }

    private fun storagePath(storageKey: String): Path {
        val root = mediaConfig.storageDir.toAbsolutePath().normalize()
        val path = root.resolve(storageKey).normalize()
        if (!path.startsWith(root)) throw BadRequestException("Invalid media storage key")
        return path
    }

    private fun streamRange(path: Path, range: ContentRange): StreamingOutput =
        StreamingOutput { output ->
            try {
                Files.newInputStream(path).use { input ->
                    skipFully(input, range.start)
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var remaining = range.length
                    while (remaining > 0) {
                        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        remaining -= read
                    }
                }
            } catch (e: IOException) {
                if (isClientAbort(e)) {
                    log.debug("Media range stream ended because the client closed the connection")
                    return@StreamingOutput
                }
                throw e
            }
        }

    private fun isClientAbort(exception: IOException): Boolean =
        generateSequence(exception as Throwable?) { it.cause }
            .mapNotNull { it.message }
            .any {
                it.contains("Failed to write", ignoreCase = true) ||
                    it.contains("Broken pipe", ignoreCase = true) ||
                    it.contains("Connection reset", ignoreCase = true) ||
                    it.contains("Stream is closed", ignoreCase = true)
            }

    private fun skipFully(input: java.io.InputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0 && input.read() == -1) break
            remaining -= if (skipped > 0) skipped else 1
        }
    }

    private fun Media.toMemorySummary(): MemoryMediaSummary =
        MemoryMediaSummary(
            id = publicId,
            memoryId = memory?.publicId ?: "",
            title = title,
            ownerUserId = owner.id,
            uploaderDisplayName = owner.displayName,
            createdAt = createdAt.toApiOffsetDateTime(),
            capturedAt = capturedAt?.toApiOffsetDateTime(),
            capturedAtOverride = capturedAtOverride,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            contentUrl = contentUrl(),
            previewUrl = previewUrl(),
            cameraMake = cameraMake,
            cameraModel = cameraModel,
            lensModel = lensModel,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            gpsLatitude = gpsLatitude,
            gpsLongitude = gpsLongitude,
        )

    private fun Media.contentUrl(): String =
        "/api/${url(tenant().name)}/memory-media/${url(publicId)}/content"

    private fun Media.previewUrl(): String? =
        if (canGeneratePreview(this)) "/api/${url(tenant().name)}/memory-media/${url(publicId)}/preview" else null

    private fun canGeneratePreview(media: Media): Boolean =
        media.mimeType.startsWith("image/") || media.mimeType.startsWith("video/")

    private fun previewPath(media: Media): Path =
        storagePath("tenant-${media.tenant.id}/${media.publicId}/preview.jpg")

    private fun url(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    private fun safeHeaderFilename(value: String): String =
        value.replace("\\", "_").replace("\"", "_").replace("\r", "_").replace("\n", "_")

    private fun Instant.toApiOffsetDateTime(): OffsetDateTime =
        OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
    companion object {
        private const val PREVIEW_MAX_EDGE = 720
    }
}
