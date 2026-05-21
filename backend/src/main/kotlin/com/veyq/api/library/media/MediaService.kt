package com.veyq.api.library.media

import com.veyq.api.auth.BadRequestException
import com.veyq.api.auth.ForbiddenException
import com.veyq.api.auth.NotFoundException
import com.veyq.api.domain.*
import com.veyq.api.library.LibraryScopeKey
import com.veyq.api.library.LibraryScopes
import com.veyq.api.library.LibraryService
import com.veyq.api.library.explorer.ResolvedExplorerLocation
import com.veyq.api.auth.filterchain.RequestContext
import com.veyq.api.generated.model.CreateMediaUploadRequest
import com.veyq.api.generated.model.MediaUploadSessionSummary
import com.veyq.api.generated.model.PendingMediaUploadSummary
import com.veyq.api.generated.model.MediaSummary
import com.veyq.api.generated.model.UpdateMediaMetadataRequest
import com.veyq.api.generated.model.MemoryMediaSummary
import com.veyq.api.generated.model.UpdateMemoryMediaMetadataRequest
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
import javax.imageio.ImageIO
import java.io.IOException
import java.io.InputStream
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
import java.util.Properties
import java.util.concurrent.TimeUnit
import java.util.UUID

@Singleton
class MediaService @Inject constructor(
    private val albumMediaRepo: AlbumMediaRepo,
    private val albumRepo: TenantAlbumRepo,
    private val mediaRepo: MediaRepo,
    private val memoryRepo: MemoryRepo,
    private val mediaConfig: MediaConfig,
    private val mediaMetadataExtractor: MediaMetadataExtractor,
    private val tenantRepo: TenantRepo,
    private val tenantMemberRepo: TenantMemberRepo,
    folderRepo: TenantFolderRepo,
    groupRepo: TenantGroupRepo,
    private val mediaGroupMemberRepo: TenantGroupMemberRepo,
    requestContext: RequestContext
) : LibraryService(folderRepo, albumRepo, groupRepo, mediaGroupMemberRepo, requestContext) {
    private val log: Logger = Logger.getLogger(MediaService::class.java)

    fun streamMedia(path: String?): Sequence<MediaSummary> {
        val context = resolveVisibleContext(path)
        val albums = when (context) {
            ResolvedExplorerLocation.Tenant -> visibleAlbums()
            is ResolvedExplorerLocation.Group -> visibleAlbums().filter { it.hasScope(context.scope) }
            is ResolvedExplorerLocation.User -> visibleAlbums().filter { it.hasScope(context.scope) }
            is ResolvedExplorerLocation.Folder -> visibleAlbums().filter { it.parentFolder != null && isDescendantOrSame(it.parentFolder!!, context.scope) }
            is ResolvedExplorerLocation.Album -> listOf(context.scope)
        }

        return albums.asSequence()
            .flatMap { album ->
                albumMediaRepo.listVisibleByAlbum(album).asSequence()
                    .filter { isMediaVisible(it.media, album) }
                    .map { it.media.toStreamSummary(album) }
            }
            .distinctBy { it.id }
    }

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
    fun uploadMedia(file: FileUpload?, albumPath: String?, title: String?): MediaSummary {
        val album = resolveAlbum(albumPath)
        requireVisible(album)
        val upload = file ?: throw BadRequestException("file is required")
        val uploadPath = upload.uploadedFile()
        val size = Files.size(uploadPath)
        if (size <= 0) throw BadRequestException("file must not be empty")
        val mimeType = upload.contentType() ?: Files.probeContentType(uploadPath) ?: ""
        if (!mimeType.startsWith("image/") && !mimeType.startsWith("video/")) {
            throw BadRequestException("Only image and video uploads are supported")
        }

        val media = Media().also {
            it.tenant = tenant()
            it.owner = user()
            applyScope(it, album.scope())
            it.title = title?.trim()?.takeIf { value -> value.isNotBlank() }
                ?: upload.fileName()?.substringBeforeLast('.')?.takeIf { value -> value.isNotBlank() }
                        ?: "Upload"
            it.mimeType = mimeType
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
        linkMedia(album, media)

        return media.toUploadSummary(album)
    }

    @Transactional
    fun uploadMemoryMedia(memoryId: String, file: FileUpload?, title: String?): MemoryMediaSummary {
        val memory = resolveVisibleMemory(memoryId)
        val upload = file ?: throw BadRequestException("file is required")
        val uploadPath = upload.uploadedFile()
        val size = Files.size(uploadPath)
        if (size <= 0) throw BadRequestException("file must not be empty")
        val mimeType = upload.contentType() ?: Files.probeContentType(uploadPath) ?: ""
        if (!mimeType.startsWith("image/") && !mimeType.startsWith("video/")) {
            throw BadRequestException("Only image and video uploads are supported")
        }

        val media = Media().also {
            it.tenant = tenant()
            it.owner = user()
            applyScope(it, memory.scope())
            it.memory = memory
            it.title = title?.trim()?.takeIf { value -> value.isNotBlank() }
                ?: upload.fileName()?.substringBeforeLast('.')?.takeIf { value -> value.isNotBlank() }
                        ?: "Upload"
            it.mimeType = mimeType
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

    fun createUpload(request: CreateMediaUploadRequest): MediaUploadSessionSummary {
        val targetTenant = resolveUploadTenant(request.tenantName)
        requestContext.tenant = targetTenant
        val album = resolveAlbum(request.albumPath)
        requireVisible(album)
        val filename = request.filename?.trim()?.takeIf { it.isNotBlank() }
            ?: throw BadRequestException("filename is required")
        val mimeType = request.mimeType?.trim()?.takeIf { it.isNotBlank() }
            ?: throw BadRequestException("mimeType is required")
        if (!mimeType.startsWith("image/") && !mimeType.startsWith("video/")) {
            throw BadRequestException("Only image and video uploads are supported")
        }
        val sizeBytes = request.sizeBytes ?: throw BadRequestException("sizeBytes is required")
        if (sizeBytes <= 0) throw BadRequestException("file must not be empty")
        val uploadId = UUID.randomUUID().toString()
        val now = Instant.now()
        val session = UploadSession(
            uploadId = uploadId,
            tenantId = targetTenant.id,
            tenantName = targetTenant.name,
            ownerUserId = user().id,
            albumPath = album.path(),
            filename = filename,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            title = request.title?.trim()?.takeIf { it.isNotBlank() },
            createdAt = now,
            updatedAt = now,
        )
        val directory = uploadDirectory(uploadId)
        Files.createDirectories(directory.resolve("chunks"))
        saveUploadSession(session)
        return MediaUploadSessionSummary(uploadId, mediaConfig.chunkSizeBytes)
    }

    fun uploadChunk(uploadId: String, chunkIndex: Int, chunk: InputStream) {
        if (chunkIndex < 0) throw BadRequestException("chunkIndex must not be negative")
        val session = loadUploadSession(uploadId)
        requireCurrentUploadOwner(session)
        val chunks = uploadDirectory(uploadId).resolve("chunks")
        Files.createDirectories(chunks)
        val target = chunks.resolve("$chunkIndex.part")
        val temp = Files.createTempFile(chunks, "$chunkIndex-", ".tmp")
        try {
            chunk.use { input -> Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING) }
            val chunkSize = Files.size(temp)
            if (chunkSize <= 0) throw BadRequestException("chunk must not be empty")
            if (chunkSize > mediaConfig.chunkSizeBytes) throw BadRequestException("chunk is too large")
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            updateUploadSessionTimestamp(session.uploadId)
        } catch (e: Exception) {
            Files.deleteIfExists(temp)
            throw e
        }
    }

    fun listUploads(): List<PendingMediaUploadSummary> =
        uploadSessions()
            .filter { it.ownerUserId == user().id }
            .sortedByDescending { it.updatedAt }
            .map { it.toPendingSummary() }

    fun uploadStatus(uploadId: String): PendingMediaUploadSummary {
        val session = loadUploadSession(uploadId)
        requireCurrentUploadOwner(session)
        return session.toPendingSummary()
    }

    fun abortUpload(uploadId: String) {
        val session = loadUploadSession(uploadId)
        requireCurrentUploadOwner(session)
        deleteRecursively(uploadDirectory(uploadId))
    }

    @Transactional
    fun completeUpload(uploadId: String): MediaSummary {
        val session = loadUploadSession(uploadId)
        requireCurrentUploadOwner(session)
        requestContext.tenant = loadUploadTenant(session)
        val album = resolveAlbum(session.albumPath)
        requireVisible(album)
        val chunkCount = ((session.sizeBytes + mediaConfig.chunkSizeBytes - 1) / mediaConfig.chunkSizeBytes).toInt()
        val media = Media().also {
            it.tenant = tenant()
            it.owner = user()
            applyScope(it, album.scope())
            it.title = session.title ?: session.filename.substringBeforeLast('.').takeIf { value -> value.isNotBlank() } ?: "Upload"
            it.mimeType = session.mimeType
            it.sizeBytes = session.sizeBytes
            it.storageKey = ""
            it.originalFilename = session.filename
            mediaRepo.persist(it)
        }
        mediaRepo.flush()

        val storageKey = "tenant-${tenant().id}/${media.publicId}/original"
        assembleChunks(uploadId, chunkCount, session.sizeBytes, storageKey)
        media.storageKey = storageKey
        mediaMetadataExtractor.apply(media, mediaMetadataExtractor.extract(storagePath(storageKey), media.mimeType))
        generatePreviewIfSupported(media)
        linkMedia(album, media)
        deleteRecursively(uploadDirectory(uploadId))

        return media.toUploadSummary(album)
    }

    @Transactional
    fun updateMetadata(publicId: String, request: UpdateMediaMetadataRequest): MediaSummary {
        val media = mediaRepo.findByTenantAndPublicId(tenant(), publicId)
            ?: throw NotFoundException("Media not found")
        if (!isMediaAccessible(media)) throw NotFoundException("Media not found")

        media.capturedAt = request.capturedAt?.toInstant()
        media.capturedAtOverride = true
        val album = albumMediaRepo.list("media", media)
            .firstOrNull { isMediaVisible(media, it.album) }
            ?.album
        return media.toStreamSummary(album)
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
        val media = mediaRepo.findByTenantAndPublicId(tenant(), publicId)
            ?: throw NotFoundException("Media not found")
        if (!isMediaAccessible(media)) throw NotFoundException("Media not found")

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
                .build()
        }

        return Response.ok(path.toFile(), media.mimeType)
            .header("Accept-Ranges", "bytes")
            .header("Content-Length", media.sizeBytes)
            .header("Content-Disposition", "inline; filename=\"${safeHeaderFilename(filename)}\"")
            .build()
    }

    fun mediaPreview(publicId: String): Response {
        val media = mediaRepo.findByTenantAndPublicId(tenant(), publicId)
            ?: throw NotFoundException("Media not found")
        if (!isMediaAccessible(media) || !canGeneratePreview(media)) throw NotFoundException("Media not found")

        val previewPath = previewPath(media)
        if (!Files.isRegularFile(previewPath)) {
            if (!generatePreview(media, throwOnFailure = false)) throw NotFoundException("Media preview not found")
        }

        return Response.ok(previewPath.toFile(), "image/jpeg")
            .header("Cache-Control", "private, max-age=31536000, immutable")
            .header("Content-Length", Files.size(previewPath))
            .header("Content-Disposition", "inline; filename=\"${safeHeaderFilename(media.publicId)}-preview.jpg\"")
            .build()
    }

    private fun visibleAlbums(): List<TenantAlbum> =
        albumRepo.listByTenant(tenant()).filter { isAlbumVisible(it) }

    private fun isDescendantOrSame(candidate: TenantFolder, ancestor: TenantFolder): Boolean =
        generateSequence(candidate) { it.parentFolder }.any { it.id == ancestor.id }

    private fun isMediaVisible(media: Media, album: TenantAlbum): Boolean =
        isAlbumVisible(album) && media.scope().sameAs(album.scope())

    private fun isMediaAccessible(media: Media): Boolean =
        media.memory?.let(::isMemoryVisible) == true ||
            albumMediaRepo.list("media", media).any { isMediaVisible(media, it.album) }

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

    private fun resolveAlbum(path: String?): TenantAlbum =
        (resolveVisibleContext(path) as? ResolvedExplorerLocation.Album)?.scope ?: throw NotFoundException("Album not found")

    private fun linkMedia(album: TenantAlbum, media: Media) {
        val link = albumMediaRepo.findByAlbumAndMedia(album, media)
        if (link == null) {
            albumMediaRepo.persist(AlbumMedia().also {
                it.id = AlbumMediaId(album.id, media.id)
                it.album = album
                it.media = media
                it.hidden = false
                it.position = 0
            })
        } else {
            link.hidden = false
        }
    }

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

    private fun assembleChunks(uploadId: String, chunkCount: Int, expectedSize: Long, storageKey: String) {
        val target = storagePath(storageKey)
        Files.createDirectories(target.parent)
        val temp = Files.createTempFile(target.parent, "upload-", ".tmp")
        try {
            Files.newOutputStream(temp).use { output ->
                for (index in 0 until chunkCount) {
                    val chunk = uploadDirectory(uploadId).resolve("chunks").resolve("$index.part")
                    if (!Files.isRegularFile(chunk)) throw BadRequestException("Missing upload chunk $index")
                    Files.newInputStream(chunk).use { input -> copy(input, output) }
                }
            }
            if (Files.size(temp) != expectedSize) throw BadRequestException("Upload size mismatch")
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

    private fun saveUploadSession(session: UploadSession) {
        val properties = MediaUploadSessions.toProperties(session)
        Files.newOutputStream(uploadDirectory(session.uploadId).resolve("session.properties")).use { output ->
            properties.store(output, null)
        }
    }

    private fun updateUploadSessionTimestamp(uploadId: String) {
        val path = uploadDirectory(uploadId).resolve("session.properties")
        if (!Files.isRegularFile(path)) throw NotFoundException("Upload not found")
        val properties = Properties()
        Files.newInputStream(path).use { input -> properties.load(input) }
        properties["updatedAt"] = Instant.now().toString()
        Files.newOutputStream(path).use { output -> properties.store(output, null) }
    }

    private fun loadUploadSession(uploadId: String): UploadSession {
        if (!MediaUploadSessions.hasValidUploadId(uploadId)) throw NotFoundException("Upload not found")
        val path = uploadDirectory(uploadId).resolve("session.properties")
        if (!Files.isRegularFile(path)) throw NotFoundException("Upload not found")
        return loadUploadSession(path)
    }

    private fun loadUploadSession(path: Path): UploadSession {
        val properties = Properties()
        Files.newInputStream(path).use { input -> properties.load(input) }
        val updatedAt = properties.getProperty("updatedAt")?.let { Instant.parse(it) }
            ?: Files.getLastModifiedTime(path).toInstant()
        val createdAt = properties.getProperty("createdAt")?.let { Instant.parse(it) }
            ?: updatedAt
        return try {
            MediaUploadSessions.fromProperties(
                properties = properties,
                updatedAtFallback = updatedAt,
                createdAtFallback = createdAt,
                tenantNameFallback = { tenantId -> tenantRepo.findById(tenantId)?.name },
            )
        } catch (e: Exception) {
            throw NotFoundException("Upload not found")
        }
    }

    private fun requireCurrentUploadOwner(session: UploadSession) {
        if (session.ownerUserId != user().id) throw NotFoundException("Upload not found")
    }

    private fun resolveUploadTenant(tenantName: String?): Tenant {
        val targetTenant = tenantName?.trim()?.takeIf { it.isNotBlank() }
            ?.let { tenantRepo.findByName(it) ?: throw NotFoundException("Tenant not found") }
            ?: requestContext.tenant
            ?: throw BadRequestException("tenantName is required")

        if (tenantMemberRepo.findByTenantAndUser(targetTenant, user()) == null) {
            throw ForbiddenException("User is not member of this tenant")
        }
        return targetTenant
    }

    private fun loadUploadTenant(session: UploadSession): Tenant =
        tenantRepo.findById(session.tenantId) ?: throw NotFoundException("Tenant not found")

    private fun uploadSessions(): List<UploadSession> {
        val root = storagePath("_uploads")
        if (!Files.isDirectory(root)) return emptyList()
        return Files.list(root).use { stream ->
            stream
                .filter { Files.isDirectory(it) }
                .map { it.resolve("session.properties") }
                .filter { Files.isRegularFile(it) }
                .toList()
                .mapNotNull { path ->
                    try {
                        loadUploadSession(path)
                    } catch (e: Exception) {
                        log.warn("Ignoring invalid upload session $path", e)
                        null
                    }
                }
        }
    }

    private fun UploadSession.toPendingSummary(): PendingMediaUploadSummary {
        val tenant = loadUploadTenant(this)
        val uploadedChunks = uploadedChunks(uploadId)
        val uploadedBytes = uploadedChunks.sumOf { index ->
            Files.size(uploadDirectory(uploadId).resolve("chunks").resolve("$index.part"))
        }
        return PendingMediaUploadSummary(
            uploadId = uploadId,
            tenantName = tenant.name,
            tenantDisplayName = tenant.displayName,
            albumPath = albumPath,
            filename = filename,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            title = title,
            chunkSizeBytes = mediaConfig.chunkSizeBytes,
            uploadedChunks = uploadedChunks,
            uploadedBytes = uploadedBytes.coerceAtMost(sizeBytes),
            createdAt = createdAt.toApiOffsetDateTime(),
            updatedAt = updatedAt.toApiOffsetDateTime(),
        )
    }

    private fun uploadedChunks(uploadId: String): List<Int> {
        val chunks = uploadDirectory(uploadId).resolve("chunks")
        if (!Files.isDirectory(chunks)) return emptyList()
        return Files.list(chunks).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .toList()
                .mapNotNull { path ->
                    val name = path.fileName.toString()
                    if (!name.endsWith(".part")) null else name.removeSuffix(".part").toIntOrNull()
                }
                .sorted()
        }
    }

    private fun uploadDirectory(uploadId: String): Path =
        storagePath("_uploads/$uploadId")

    private fun storagePath(storageKey: String): Path {
        val root = mediaConfig.storageDir.toAbsolutePath().normalize()
        val path = root.resolve(storageKey).normalize()
        if (!path.startsWith(root)) throw BadRequestException("Invalid media storage key")
        return path
    }

    private fun copy(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            output.write(buffer, 0, read)
        }
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

    private fun skipFully(input: InputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0 && input.read() == -1) break
            remaining -= if (skipped > 0) skipped else 1
        }
    }

    private fun Media.toStreamSummary(album: TenantAlbum?): MediaSummary =
        MediaSummary(
            id = publicId,
            title = title,
            albumPath = album?.path(),
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

    private fun Media.toUploadSummary(album: TenantAlbum): MediaSummary =
        MediaSummary(
            id = publicId,
            title = title,
            albumPath = album.path(),
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
        "/api/${url(tenant().name)}/media/${url(publicId)}/content"

    private fun Media.previewUrl(): String? =
        if (canGeneratePreview(this)) "/api/${url(tenant().name)}/media/${url(publicId)}/preview" else null

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

    private fun Memory.scope(): LibraryScopeKey =
        LibraryScopes.scope(this)

    companion object {
        private const val PREVIEW_MAX_EDGE = 720
    }
}
