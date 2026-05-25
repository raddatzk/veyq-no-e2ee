package com.veyq.api.library.media

import com.veyq.api.auth.BadRequestException
import com.veyq.api.auth.NotFoundException
import com.veyq.api.domain.ArchiveMediaKeyset
import com.veyq.api.domain.ArchiveMediaRow
import com.veyq.api.auth.filterchain.RequestContext
import com.veyq.api.domain.Media
import com.veyq.api.domain.MediaBlob
import com.veyq.api.domain.MediaBlobRepo
import com.veyq.api.domain.MediaRepo
import com.veyq.api.domain.Memory
import com.veyq.api.domain.MemoryShareIdentity
import com.veyq.api.domain.MemoryRepo
import com.veyq.api.domain.TenantFolderRepo
import com.veyq.api.domain.TenantGroupMemberRepo
import com.veyq.api.domain.TenantGroupRepo
import com.veyq.api.domain.User
import com.veyq.api.generated.model.MediaUploader
import com.veyq.api.generated.model.MemoryMediaBatchResponse
import com.veyq.api.generated.model.MemoryMediaSummary
import com.veyq.api.generated.model.PublicShareMediaSummary
import com.veyq.api.generated.model.UpdateMemoryMediaMetadataRequest
import com.veyq.api.generated.model.UpdateMemoryMediaMetadataBatchRequest
import com.veyq.api.library.LibraryScopeKey
import com.veyq.api.library.LibraryService
import com.veyq.api.library.LibraryScopes
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
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.text.Normalizer
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Comparator
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

@Singleton
class MediaService @Inject constructor(
    private val mediaRepo: MediaRepo,
    private val mediaBlobRepo: MediaBlobRepo,
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

    fun downloadMemoryMedia(memoryId: String, includeChildren: Boolean, locale: String?): Response {
        val memory = resolveVisibleMemory(memoryId)
        val memories = if (includeChildren) descendantsAndSelf(memory).filter(::isMemoryVisible) else listOf(memory)
        return archiveResponse(
            memoryDirectories = archiveMemoryDirectories(memory, memories),
            archiveName = archiveFilename(memory, includeChildren, locale),
            locale = locale,
        )
    }

    fun archiveResponse(memoryDirectories: Map<Long, String>, archiveName: String, locale: String?): Response {
        val memoryIds = memoryDirectories.keys
        val output = StreamingOutput { stream ->
            try {
                ZipOutputStream(stream).use { zip ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    val usedNames = mutableMapOf<String, Int>()
                    var after: ArchiveMediaKeyset? = null
                    while (true) {
                        val rows = mediaRepo.listArchiveRowsByMemoryIds(memoryIds, after, mediaConfig.archiveBatchSize)
                        if (rows.isEmpty()) break

                        rows.forEach { row ->
                            val path = storagePath(row.storageKey)
                            if (!Files.isRegularFile(path)) throw NotFoundException("Media file not found")
                            val zipEntry = ZipEntry(archiveEntryName(memoryDirectories, row, locale, usedNames))
                            zipEntry.time = row.sortTime.toEpochMilli()
                            zip.putNextEntry(zipEntry)
                            Files.newInputStream(path).use { input ->
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read == -1) break
                                    zip.write(buffer, 0, read)
                                }
                            }
                            zip.closeEntry()
                        }
                        val last = rows.last()
                        after = ArchiveMediaKeyset(last.sortTime, last.createdAt, last.id)
                    }
                }
            } catch (e: IOException) {
                if (isClientAbort(e)) {
                    log.debug("Media archive stream ended because the client closed the connection")
                    return@StreamingOutput
                }
                throw e
            }
        }

        return Response.ok(output, "application/zip")
            .header("Content-Disposition", "attachment; filename=\"${safeHeaderFilename(archiveName)}\"")
            .header("X-Content-Type-Options", "nosniff")
            .build()
    }

    @Transactional
    fun uploadMemoryMedia(memoryId: String, file: FileUpload?, title: String?): MemoryMediaSummary {
        val memory = resolveVisibleMemory(memoryId)
        val upload = file ?: throw BadRequestException("file is required", "MEDIA_UPLOAD_REQUIRED")
        return uploadMemoryMediaInternal(
            memory = memory,
            uploadPath = upload.uploadedFile(),
            originalFilename = upload.fileName(),
            title = title,
            uploaderUser = user(),
            shareIdentity = null,
        ).toMemorySummary()
    }

    @Transactional
    fun uploadPublicShareMedia(
        shareId: String,
        memory: Memory,
        uploadPath: Path,
        originalFilename: String?,
        title: String?,
        uploaderUser: User?,
        shareIdentity: MemoryShareIdentity?,
    ): PublicShareMediaSummary =
        uploadMemoryMediaInternal(
            memory = memory,
            uploadPath = uploadPath,
            originalFilename = originalFilename,
            title = title,
            uploaderUser = uploaderUser,
            shareIdentity = shareIdentity,
        ).toPublicShareSummary(shareId)

    private fun uploadMemoryMediaInternal(
        memory: Memory,
        uploadPath: Path,
        originalFilename: String?,
        title: String?,
        uploaderUser: User?,
        shareIdentity: MemoryShareIdentity?,
    ): Media {
        if ((uploaderUser == null) == (shareIdentity == null)) {
            throw BadRequestException("Exactly one uploader source is required")
        }

        val size = Files.size(uploadPath)
        if (size <= 0) throw BadRequestException("file must not be empty", "MEDIA_UPLOAD_EMPTY")
        val mediaType = mediaTypeDetector.detect(uploadPath)
            ?: throw BadRequestException("Only JPEG, PNG, GIF, MP4 and QuickTime uploads are supported", "MEDIA_UPLOAD_TYPE_UNSUPPORTED")
        val contentSha256 = sha256(uploadPath)
        val blob = mediaBlobRepo.findByTenantAndSha256(memory.tenant, contentSha256)
            ?: createMediaBlob(memory, uploadPath, mediaType.mimeType, size, contentSha256)

        val media = Media().also {
            it.tenant = memory.tenant
            applyScope(it, LibraryScopes.scope(memory))
            it.memory = memory
            it.title = title?.trim()?.takeIf { value -> value.isNotBlank() }
                ?: originalFilename?.substringBeforeLast('.')?.takeIf { value -> value.isNotBlank() }
                ?: "Upload"
            it.blob = blob
            it.originalFilename = originalFilename
            it.uploaderUser = uploaderUser
            it.shareIdentity = shareIdentity
            it.capturedAt = blob.capturedAt
            it.capturedAtOverride = false
            mediaRepo.persist(it)
        }

        return media
    }

    private fun createMediaBlob(
        memory: Memory,
        uploadPath: Path,
        mimeType: String,
        size: Long,
        contentSha256: String,
    ): MediaBlob {
        val storageKey = "tenant-${memory.tenant.id}/blobs/$contentSha256/original"
        writeMediaFile(uploadPath, storageKey)
        return MediaBlob().also {
            it.tenant = memory.tenant
            it.contentSha256 = contentSha256
            it.storageKey = storageKey
            it.mimeType = mimeType
            it.sizeBytes = size
            mediaMetadataExtractor.apply(it, mediaMetadataExtractor.extract(storagePath(storageKey), mimeType))
            mediaBlobRepo.persist(it)
            mediaBlobRepo.flush()
            generatePreviewIfSupported(it)
        }
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

    @Transactional
    fun updateMemoryMediaMetadataBatch(
        memoryId: String,
        request: UpdateMemoryMediaMetadataBatchRequest,
    ): MemoryMediaBatchResponse {
        val memory = resolveVisibleMemory(memoryId)
        val mediaIds = request.mediaIds.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (mediaIds.isEmpty()) throw BadRequestException("mediaIds must not be empty", "MEDIA_BATCH_EMPTY")

        val updated = mediaIds.map { publicId ->
            val media = mediaRepo.findByTenantAndPublicId(tenant(), publicId)
                ?: throw NotFoundException("Media not found")
            if (media.memory?.id != memory.id) throw NotFoundException("Media not found")

            media.capturedAt = (media.capturedAt ?: media.createdAt).plusSeconds(request.capturedAtOffsetSeconds)
            media.capturedAtOverride = true
            media
        }
        return MemoryMediaBatchResponse(media = updated.map { it.toMemorySummary() })
    }

    fun mediaContent(publicId: String, rangeHeader: String?): Response {
        val media = resolveAccessibleMedia(publicId)
        return streamOriginal(media, rangeHeader)
    }

    fun streamOriginal(media: Media, rangeHeader: String?): Response {
        val path = storagePath(media.blob.storageKey)
        if (!Files.isRegularFile(path)) throw NotFoundException("Media file not found")
        val filename = downloadFilename(
            media.originalFilename?.takeIf { it.isNotBlank() } ?: media.publicId,
            media.blob.mimeType,
        )
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
                .type(media.blob.mimeType)
                .entity(streamRange(path, range))
                .header("Accept-Ranges", "bytes")
                .header("Content-Length", range.length)
                .header("Content-Range", "bytes ${range.start}-${range.end}/$size")
                .header("Content-Disposition", "inline; filename=\"${safeHeaderFilename(filename)}\"")
                .header("X-Content-Type-Options", "nosniff")
                .build()
        }

        return Response.ok(path.toFile(), media.blob.mimeType)
            .header("Accept-Ranges", "bytes")
            .header("Content-Length", media.blob.sizeBytes)
            .header("Content-Disposition", "inline; filename=\"${safeHeaderFilename(filename)}\"")
            .header("X-Content-Type-Options", "nosniff")
            .build()
    }

    fun mediaPreview(publicId: String): Response {
        val media = resolveAccessibleMedia(publicId)
        return streamPreview(media)
    }

    fun streamPreview(media: Media): Response {
        if (!canGeneratePreview(media.blob)) throw NotFoundException("Media not found")

        val previewPath = previewPath(media.blob)
        if (!Files.isRegularFile(previewPath)) {
            if (!generatePreview(media.blob, throwOnFailure = false)) throw NotFoundException("Media preview not found")
        }

        return Response.ok(previewPath.toFile(), "image/jpeg")
            .header("Cache-Control", "private, max-age=31536000, immutable")
            .header("Content-Length", Files.size(previewPath))
            .header("Content-Disposition", "inline; filename=\"${safeHeaderFilename(media.publicId)}-preview.jpg\"")
            .header("X-Content-Type-Options", "nosniff")
            .build()
    }

    fun publicShareSummary(media: Media, shareId: String): PublicShareMediaSummary =
        media.toPublicShareSummary(shareId)

    fun deleteBlobIfUnreferenced(blob: MediaBlob) {
        if (mediaRepo.count("blob", blob) > 0) return
        val directory = blobDirectory(blob)
        blob.delete()
        mediaBlobRepo.flush()
        deleteRecursively(directory)
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

    fun archiveMemoryDirectories(rootMemory: Memory, memories: List<Memory>): Map<Long, String> =
        memories.associate { memory -> memory.id to memoryPath(rootMemory, memory).joinToString("/") }

    private fun archiveEntryName(
        memoryDirectories: Map<Long, String>,
        media: ArchiveMediaRow,
        locale: String?,
        usedNames: MutableMap<String, Int>,
    ): String {
        val directory = memoryDirectories[media.memoryId] ?: throw NotFoundException("Media not found")
        val baseFilename = mediaFilename(media, locale)
        return uniqueArchiveName("$directory/$baseFilename", usedNames)
    }

    private fun memoryPath(rootMemory: Memory, memory: Memory): List<String> {
        val path = generateSequence(memory) { it.parentMemory }
            .takeWhile { it.id != rootMemory.parentMemory?.id }
            .toList()
            .asReversed()
        return path.map { safeZipSegment(it.displayName) }
    }

    private fun mediaFilename(media: ArchiveMediaRow, locale: String?): String {
        val fallback = fallbackMediaFilename(media, locale)
        val name = media.originalFilename?.takeIf { it.isNotBlank() }?.let { safeZipSegment(it, fallback) }
            ?: media.title.takeIf { it.isNotBlank() }?.let { safeZipSegment(it, fallback) }
            ?: fallback
        return "${ARCHIVE_TIMESTAMP_FORMAT.format(media.sortTime)} ${downloadFilename(name, media.mimeType)}"
    }

    private fun fallbackMediaFilename(media: ArchiveMediaRow, locale: String?): String =
        when (normalizedLocale(locale)) {
            "en" -> downloadFilename("file-${media.publicId}", media.mimeType)
            else -> downloadFilename("datei-${media.publicId}", media.mimeType)
        }

    private fun downloadFilename(name: String, mimeType: String): String {
        val filename = name.trim().takeIf { it.isNotBlank() } ?: "file"
        if (filename.substringAfterLast('/').contains('.')) return filename
        val extension = extensionForMimeType(mimeType) ?: return filename
        return "$filename.$extension"
    }

    private fun extensionForMimeType(mimeType: String): String? =
        when (mimeType.lowercase()) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/gif" -> "gif"
            "video/mp4" -> "mp4"
            "video/quicktime" -> "mov"
            else -> null
        }

    private fun uniqueArchiveName(candidate: String, usedNames: MutableMap<String, Int>): String {
        val key = archiveNameKey(candidate)
        val count = usedNames.getOrDefault(key, 0)
        usedNames[key] = count + 1
        if (count == 0) return candidate

        val slashIndex = candidate.lastIndexOf('/')
        val directory = candidate.substring(0, slashIndex + 1)
        val filename = candidate.substring(slashIndex + 1)
        val dotIndex = filename.lastIndexOf('.').takeIf { it > 0 }
        val stem = dotIndex?.let { filename.substring(0, it) } ?: filename
        val extension = dotIndex?.let { filename.substring(it) } ?: ""
        val deduplicated = "$directory$stem-${count + 1}$extension"
        return uniqueArchiveName(deduplicated, usedNames)
    }

    private fun archiveNameKey(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFC).lowercase()

    private fun archiveFilename(memory: Memory, includeChildren: Boolean, locale: String?): String {
        val name = safeZipSegment(memory.displayName, archiveFallbackName(locale))
        val suffix = when (normalizedLocale(locale)) {
            "en" -> if (includeChildren) "-with-chapters" else ""
            else -> if (includeChildren) "-mit-kapiteln" else ""
        }
        return "$name$suffix.zip"
    }

    private fun archiveFallbackName(locale: String?): String =
        when (normalizedLocale(locale)) {
            "en" -> "memory"
            else -> "erinnerung"
        }

    fun normalizedLocale(locale: String?): String =
        locale?.trim()?.lowercase()?.substringBefore('-')?.takeIf { it == "en" } ?: "de"

    private fun safeZipSegment(value: String, fallback: String = "untitled"): String =
        value
            .replace("\\", "-")
            .replace("/", "-")
            .replace(Regex("[\\x00-\\x1F\\x7F:*?\"<>|]+"), "-")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.')
            .takeIf { it.isNotBlank() }
            ?: fallback

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

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun generatePreviewIfSupported(blob: MediaBlob) {
        if (!canGeneratePreview(blob)) return
        generatePreview(blob, throwOnFailure = false)
    }

    private fun generatePreview(blob: MediaBlob, throwOnFailure: Boolean): Boolean {
        val originalPath = storagePath(blob.storageKey)
        if (!Files.isRegularFile(originalPath)) return false
        return when {
            blob.mimeType.startsWith("image/") -> generateImagePreview(blob, originalPath, throwOnFailure)
            blob.mimeType.startsWith("video/") -> generateVideoPreview(blob, originalPath, throwOnFailure)
            else -> false
        }
    }

    private fun generateImagePreview(blob: MediaBlob, originalPath: Path, throwOnFailure: Boolean): Boolean {
        val previewPath = previewPath(blob)
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
            blob.previewStorageKey = previewStorageKey(blob)
            return true
        } catch (e: Exception) {
            if (throwOnFailure) throw e
            log.warn("Failed to generate media preview for blob ${blob.id}: ${e.message}")
            return false
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun generateVideoPreview(blob: MediaBlob, originalPath: Path, throwOnFailure: Boolean): Boolean {
        val previewPath = previewPath(blob)
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
            blob.previewStorageKey = previewStorageKey(blob)
            return true
        } catch (e: Exception) {
            if (throwOnFailure) throw e
            log.warn("Failed to generate media preview for blob ${blob.id}: ${e.message}")
            return false
        } finally {
            Files.deleteIfExists(temp)
            Files.deleteIfExists(logFile)
        }
    }

    private fun storagePath(storageKey: String): Path {
        val root = mediaConfig.storageDir.toAbsolutePath().normalize()
        val path = root.resolve(storageKey).normalize()
        if (!path.startsWith(root)) throw BadRequestException("Invalid media storage key", "MEDIA_STORAGE_KEY_INVALID")
        return path
    }

    private fun blobDirectory(blob: MediaBlob): Path {
        val originalPath = storagePath(blob.storageKey)
        return originalPath.parent ?: storagePath("tenant-${blob.tenant.id}/blobs/${blob.contentSha256}")
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
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
            uploader = uploader(),
            createdAt = createdAt.toApiOffsetDateTime(),
            capturedAt = capturedAt?.toApiOffsetDateTime(),
            capturedAtOverride = capturedAtOverride,
            mimeType = blob.mimeType,
            sizeBytes = blob.sizeBytes,
            contentUrl = contentUrl(),
            previewUrl = previewUrl(),
            cameraMake = blob.cameraMake,
            cameraModel = blob.cameraModel,
            lensModel = blob.lensModel,
            imageWidth = blob.imageWidth,
            imageHeight = blob.imageHeight,
            gpsLatitude = blob.gpsLatitude,
            gpsLongitude = blob.gpsLongitude,
        )

    private fun Media.toPublicShareSummary(shareId: String): PublicShareMediaSummary =
        PublicShareMediaSummary(
            id = publicId,
            memoryId = memory?.publicId ?: "",
            title = title,
            uploader = uploader(),
            createdAt = createdAt.toApiOffsetDateTime(),
            capturedAt = capturedAt?.toApiOffsetDateTime(),
            capturedAtOverride = capturedAtOverride,
            mimeType = blob.mimeType,
            sizeBytes = blob.sizeBytes,
            contentUrl = "/api/public-shares/${url(shareId)}/media/${url(publicId)}/content",
            previewUrl = if (canGeneratePreview(blob)) "/api/public-shares/${url(shareId)}/media/${url(publicId)}/preview" else null,
        )

    private fun Media.uploader(): MediaUploader {
        uploaderUser?.let { user ->
            return MediaUploader(
                type = MediaUploader.Type.USER,
                displayName = user.displayName,
                permalink = user.permalink,
                userId = user.id,
            )
        }
        shareIdentity?.let { identity ->
            return MediaUploader(
                type = MediaUploader.Type.ANONYMOUS,
                displayName = identity.displayName,
                permalink = null,
                userId = null,
            )
        }
        return MediaUploader(
            type = MediaUploader.Type.ANONYMOUS,
            displayName = "Unbekannt",
            permalink = null,
            userId = null,
        )
    }

    private fun Media.contentUrl(): String =
        "/api/${url(tenant().name)}/memory-media/${url(publicId)}/content"

    private fun Media.previewUrl(): String? =
        if (canGeneratePreview(blob)) "/api/${url(tenant().name)}/memory-media/${url(publicId)}/preview" else null

    private fun canGeneratePreview(blob: MediaBlob): Boolean =
        blob.mimeType.startsWith("image/") || blob.mimeType.startsWith("video/")

    private fun previewPath(blob: MediaBlob): Path =
        storagePath(blob.previewStorageKey ?: previewStorageKey(blob))

    private fun previewStorageKey(blob: MediaBlob): String =
        "tenant-${blob.tenant.id}/blobs/${blob.contentSha256}/preview.jpg"

    private fun url(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    private fun safeHeaderFilename(value: String): String =
        value.replace("\\", "_").replace("\"", "_").replace("\r", "_").replace("\n", "_")

    private fun Instant.toApiOffsetDateTime(): OffsetDateTime =
        OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
    companion object {
        private const val PREVIEW_MAX_EDGE = 720
        private val ARCHIVE_TIMESTAMP_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss").withZone(ZoneOffset.UTC)
    }
}
